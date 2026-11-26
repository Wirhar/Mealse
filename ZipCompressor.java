package com.my.mealse.filemanager.util;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.my.mealse.util.MealseLogger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * ZipCompressor — сжатие выбранных файлов/папок в отдельные .zip.
 *
 * Каждый выбранный элемент → свой архив (имя по умолчанию = имя оригинала + ".zip").
 * Перед сжатием показывается диалог со списком редактируемых имён.
 *
 * Не дублирует существующие функции: прогресс-диалог/toast/runOnUiThread
 * приходят через интерфейс Ui (FileManagerActivity переиспользует свои).
 * Логика ZIP — новая, на встроенном java.util.zip, без внешних зависимостей.
 *
 * ★ РЕВИЗИЯ:
 *  - ScrollView с FrameLayout.LayoutParams (60% экрана) — при десятках файлов
 *    кнопки «Сжать»/«Отмена» не уезжают за границу (ревью #12).
 *  - toast «❌ Ошибок: N» при fok==0, а не «✅ Сжато: 0» (ревью #19).
 *  - finish() только если !cancelled; closeQuietly(zos) в finally без дубля
 *    (ревью #1 — чистка; при cancel .part и так удаляется).
 *  - sanitize: ограничение длины 200 + добавление .zip (ревью #4/#5).
 *    В диалоге .zip больше не дублируется.
 *  - FileInputStream открывается ДО putNextEntry — если конструктор бросит,
 *    entry ещё не открыт, .part удалится в catch compressAsync.
 *  - entryPath.isEmpty() → "root" (защита от пустого имени).
 *  - Удалены unused TAG, topSrc, idx (ревью #8/23/24/25).
 *
 * ★ НОВОЕ (единый ZIP для мультивыбора в pick-mode):
 *  - showSingleZipDialogAndCompress(...) / compressSingle(...) — собирают ОДИН
 *    архив из всех выбранных файлов и папок. Используется из FileManagerActivity
 *    в pick-mode, когда пользователь нажимает «🗜 В ZIP».
 *  - Переиспользуют те же приватные addRecursive / uniqueTarget / sanitize /
 *    sumSize / deleteQuietly / closeQuietly / BUF, что и compressAsync —
 *    дублирования логики ZIP нет.
 *  - Сигнатура addRecursive сохранена как в применённой версии (8 параметров,
 *    без topSrc) — новый код вызывает её без изменений.
 */
public final class ZipCompressor {

  private static final int BUF = 64 * 1024;

  /** Мост к UI Activity — чтобы переиспользовать её прогресс-диалог и toast. */
  public interface Ui {
    boolean isAlive();
    void showProgress(String title, String message, Runnable onCancel);
    void updateProgress(long done, long total);
    void dismissProgress();
    void toast(String msg);
    void runOnUi(Runnable r);
  }

  /** ★ НОВОЕ: колбэк «один архив готов» — используется pick-mode в менеджере. */
  public interface SingleResult {
    void onZip(File zip);
  }

  private ZipCompressor() {}

  // ============================================================
  // Диалог редактирования имён + запуск сжатия
  // ============================================================

  public static void showNameDialogAndCompress(Activity act, List<File> sources, File outDir,
      Ui ui, MealseLogger log, Runnable onDone) {
    if (act == null || sources == null || sources.isEmpty() || outDir == null) return;

    LinearLayout root = new LinearLayout(act);
    root.setOrientation(LinearLayout.VERTICAL);
    int pad = dp(act, 16);
    root.setPadding(pad, pad, pad, pad);

    final List<EditText> nameFields = new ArrayList<>();
    final List<String> defaults = new ArrayList<>();

    for (File src : sources) {
      // ★ .zip добавляет sanitize (ревью #4/#5), здесь — только базовое имя.
      String def = src.getName();
      defaults.add(def);

      TextView label = new TextView(act);
      label.setText((src.isDirectory() ? "📁 " : "📄 ") + src.getName());
      label.setTextSize(13);
      root.addView(label);

      EditText et = new EditText(act);
      et.setText(def);
      et.setSingleLine(true);
      // Выделяем всё имя — пользователю проще заменить целиком.
      et.setSelection(0, def.length());
      nameFields.add(et);
      root.addView(et);
    }

    ScrollView scroll = new ScrollView(act);
    scroll.addView(root);
    // ★ Ревью #12: ограничиваем высоту 60% экрана, чтобы кнопки не уезжали
    // при десятках файлов. AlertDialog-панель — FrameLayout, поэтому
    // FrameLayout.LayoutParams, а не LinearLayout.
    int maxH = (int) (act.getResources().getDisplayMetrics().heightPixels * 0.6f);
    scroll.setLayoutParams(new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, maxH));

    new AlertDialog.Builder(act)
        .setTitle("Сжатие в ZIP (" + sources.size() + ")")
        .setView(scroll)
        .setPositiveButton("Сжать", (d, w) -> {
          List<String> names = new ArrayList<>();
          for (int i = 0; i < sources.size(); i++) {
            String n = nameFields.get(i).getText().toString().trim();
            if (TextUtils.isEmpty(n)) n = defaults.get(i);
            n = sanitize(n);   // ★ .zip добавляется внутри
            names.add(n);
          }
          compressAsync(sources, names, outDir, ui, log, onDone);
        })
        .setNegativeButton("Отмена", null)
        .show();
  }

  // ============================================================
  // Фоновое сжатие (несколько архивов — по одному на элемент)
  // ============================================================

  public static void compressAsync(List<File> sources, List<String> names, File outDir,
      Ui ui, MealseLogger log, Runnable onDone) {
    final AtomicBoolean cancelled = new AtomicBoolean(false);
    final long total = sumSize(sources);

    ui.runOnUi(() -> {
      if (ui.isAlive())
        ui.showProgress("Сжатие", "Подготовка…", () -> cancelled.set(true));
    });

    new Thread(() -> {
      int ok = 0, fail = 0;
      long[] done = {0};
      long[] lastReport = {0};

      for (int i = 0; i < sources.size(); i++) {
        if (cancelled.get()) break;
        File src = sources.get(i);
        File target = uniqueTarget(outDir, names.get(i));
        File part = new File(target.getAbsolutePath() + ".part");

        ui.runOnUi(() -> {
          if (ui.isAlive()) ui.updateProgress(done[0], total);
        });

        ZipOutputStream zos = null;
        try {
          zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(part), BUF));
          addRecursive(zos, src, src.getName(), target, done, total, ui, cancelled, lastReport);
          // ★ Ревью #1: finish только если не отменено — избегаем лишнего
          // finish() при cancel (частичный .part всё равно удаляется).
          if (!cancelled.get()) {
            zos.finish();
            zos.flush();
          }
        } catch (Throwable t) {
          fail++;
          if (log != null) log.logError("compress: " + src.getName(), t);
          deleteQuietly(part);
        } finally {
          // ★ Ревью #1: один closeQuietly в finally, без дубля в try.
          closeQuietly(zos);
          zos = null;
        }

        if (cancelled.get()) {
          deleteQuietly(part);
          break;
        }

        if (target.exists()) deleteQuietly(target);
        if (!part.renameTo(target)) {
          deleteQuietly(part);
          fail++;
          if (log != null) log.warn("compress: rename failed " + target);
        } else {
          ok++;
          if (log != null) log.log("compress: OK " + target.getAbsolutePath());
        }
      }

      final int fok = ok, ffail = fail;
      final boolean wasCancelled = cancelled.get();
      ui.runOnUi(() -> {
        if (!ui.isAlive()) return;
        ui.dismissProgress();
        if (wasCancelled) {
          ui.toast("Сжатие отменено");
        } else if (fok == 0) {
          // ★ Ревью #19: «✅ Сжато: 0» вводит в заблуждение.
          ui.toast("❌ Ошибок: " + ffail);
        } else {
          ui.toast("✅ Сжато: " + fok + (ffail > 0 ? ", ошибок: " + ffail : ""));
        }
        if (onDone != null) onDone.run();
      });
    }, "ZipCompressor").start();
  }

  // ============================================================
  // ★ НОВОЕ: ОДИН архив на весь выбор (pick-mode)
  // ============================================================

  /**
   * Показывает диалог ввода имени и запускает сжатие всех sources в ОДИН zip.
   * Используется из FileManagerActivity в pick-mode («🗜 В ZIP»).
   *
   * @param act     Activity для диалога
   * @param sources выбранные файлы/папки (порядок сохраняется в архиве)
   * @param outDir  каталог, куда положить .zip
   * @param ui      мост к UI (прогресс/toast)
   * @param log     логгер (может быть null)
   * @param onZip   колбэк с готовым File (на UI-потоке). null допустим.
   */
  public static void showSingleZipDialogAndCompress(Activity act, List<File> sources,
      File outDir, Ui ui, MealseLogger log, SingleResult onZip) {
    if (act == null || sources == null || sources.isEmpty() || outDir == null) return;

    EditText et = new EditText(act);
    et.setSingleLine(true);
    // ★ Имя по умолчанию: если один источник — «имя_источника.zip»,
    // иначе — «archive.zip». sanitize добавит .zip, если его нет.
    String def;
    if (sources.size() == 1) {
      File only = sources.get(0);
      String n = only.getName();
      def = (n == null || n.isEmpty()) ? "archive" : n;
    } else {
      def = "archive";
    }
    et.setText(def);
    et.setSelection(0, def.length());

    int pad = dp(act, 16);
    FrameLayout wrap = new FrameLayout(act);
    wrap.setPadding(pad, pad, pad, pad);
    wrap.addView(et, new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    new AlertDialog.Builder(act)
        .setTitle("ZIP-архив (" + sources.size() + ")")
        .setView(wrap)
        .setPositiveButton("Сжать", (d, w) -> {
          String name = et.getText().toString().trim();
          if (TextUtils.isEmpty(name)) name = def;
          name = sanitize(name);   // ★ .zip добавляется внутри
          compressSingle(sources, name, outDir, ui, log, onZip);
        })
        .setNegativeButton("Отмена", null)
        .show();
  }

  /**
   * Сжимает все sources в ОДИН zip-файл name внутри outDir.
   * Асинхронно, с прогрессом через Ui. По завершении вызывает onZip на UI-потоке.
   *
   * ★ Переиспользует приватные addRecursive / uniqueTarget / sumSize /
   * deleteQuietly / closeQuietly / BUF — та же ZIP-логика, что и в compressAsync.
   * ★ Сигнатура addRecursive — как в применённой версии (8 параметров, без topSrc).
   */
  public static void compressSingle(List<File> sources, String name, File outDir,
      Ui ui, MealseLogger log, SingleResult onZip) {
    if (sources == null || sources.isEmpty() || outDir == null) return;
    final AtomicBoolean cancelled = new AtomicBoolean(false);
    final long total = sumSize(sources);
    final String safeName = sanitize(name);

    ui.runOnUi(() -> {
      if (ui.isAlive())
        ui.showProgress("Сжатие", "Подготовка…", () -> cancelled.set(true));
    });

    new Thread(() -> {
      File target = uniqueTarget(outDir, safeName);
      File part = new File(target.getAbsolutePath() + ".part");
      long[] done = {0};
      long[] lastReport = {0};
      boolean ok = false;

      ZipOutputStream zos = null;
      try {
        zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(part), BUF));
        for (File src : sources) {
          if (cancelled.get()) break;
          addRecursive(zos, src, src.getName(), target,
              done, total, ui, cancelled, lastReport);
        }
        if (!cancelled.get()) {
          zos.finish();
          zos.flush();
        }
      } catch (Throwable t) {
        if (log != null) log.logError("compressSingle", t);
        deleteQuietly(part);
      } finally {
        closeQuietly(zos);
        zos = null;
      }

      if (!cancelled.get()) {
        if (target.exists()) deleteQuietly(target);
        ok = part.renameTo(target);
        if (!ok) deleteQuietly(part);
      } else {
        deleteQuietly(part);
      }

      final boolean fok = ok;
      final boolean wasCancelled = cancelled.get();
      final File fTarget = target;
      ui.runOnUi(() -> {
        if (!ui.isAlive()) return;
        ui.dismissProgress();
        if (wasCancelled) {
          ui.toast("Сжатие отменено");
        } else if (!fok) {
          ui.toast("❌ Ошибка сжатия");
        } else {
          ui.toast("✅ ZIP создан");
          if (onZip != null) onZip.onZip(fTarget);
        }
      });
    }, "ZipSingle").start();
  }

  // ============================================================
  // Рекурсивное добавление в ZIP
  // ============================================================

  private static void addRecursive(ZipOutputStream zos, File f, String entryPath,
      File targetZip, long[] done, long total, Ui ui,
      AtomicBoolean cancelled, long[] lastReport) throws IOException {
    if (cancelled.get()) return;

    // Защита от самовключения: не добавляем создаваемый архив/.part.
    if (f.getAbsolutePath().equals(targetZip.getAbsolutePath())
        || f.getAbsolutePath().equals(targetZip.getAbsolutePath() + ".part")) return;

    // ★ Ревью #7: entryPath не должен быть пустым (File.getName() не пуст,
    // но защищаемся от экзотики).
    String safeEntry = (entryPath == null || entryPath.isEmpty()) ? "root" : entryPath;

    if (f.isDirectory()) {
      zos.putNextEntry(new ZipEntry(safeEntry + "/"));
      zos.closeEntry();
      File[] kids = f.listFiles();
      if (kids != null) {
        for (File k : kids) {
          if (cancelled.get()) return;
          addRecursive(zos, k, safeEntry + "/" + k.getName(), targetZip,
              done, total, ui, cancelled, lastReport);
        }
      }
      return;
    }

    // ★ Робастность (ревью): FileInputStream открываем ДО putNextEntry.
    // Если конструктор бросит — entry ещё не открыт, .part удалится
    // в catch compressAsync; иначе entry остался бы открытым и finish()/close()
    // могли отработать некорректно.
    InputStream in = new BufferedInputStream(new FileInputStream(f), BUF);
    try {
      ZipEntry e = new ZipEntry(safeEntry);
      e.setTime(f.lastModified());
      zos.putNextEntry(e);
      byte[] buf = new byte[BUF];
      int r;
      while ((r = in.read(buf)) != -1) {
        if (cancelled.get()) return;
        zos.write(buf, 0, r);
        done[0] += r;
        long now = System.currentTimeMillis();
        if (now - lastReport[0] > 100) {
          lastReport[0] = now;
          final long d = done[0];
          ui.runOnUi(() -> { if (ui.isAlive()) ui.updateProgress(d, total); });
        }
      }
    } finally {
      closeQuietly(in);
      zos.closeEntry();
    }
  }

  // ============================================================
  // Хелперы
  // ============================================================

  private static long sumSize(List<File> files) {
    long s = 0;
    for (File f : files) s += sizeOf(f);
    return Math.max(s, 1);
  }

  private static long sizeOf(File f) {
    if (f == null || !f.exists()) return 0;
    if (f.isFile()) return f.length();
    long s = 0;
    File[] kids = f.listFiles();
    if (kids != null) for (File k : kids) s += sizeOf(k);
    return s;
  }

  private static File uniqueTarget(File dir, String name) {
    File t = new File(dir, name);
    if (!t.exists()) return t;
    String base = name, ext = "";
    int dot = name.lastIndexOf('.');
    if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot); }
    int i = 1;
    File c;
    do { c = new File(dir, base + " (" + i + ")" + ext); i++; } while (c.exists() && i < 1000);
    return c;
  }

  /**
   * ★ Ревью #4/#5: ограничение длины (ENAMETOOLONG) + добавление .zip.
   * В диалоге .zip больше не дублируется — sanitize добавляет его сам.
   */
  private static String sanitize(String s) {
    if (s == null || s.isEmpty()) return "archive.zip";
    s = s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    if (s.length() > 200) s = s.substring(0, 200);
    if (!s.toLowerCase().endsWith(".zip")) s = s + ".zip";
    return s;
  }

  private static void deleteQuietly(File f) {
    try { if (f != null && f.exists()) f.delete(); } catch (Exception ignored) {}
  }

  private static void closeQuietly(java.io.Closeable c) {
    if (c == null) return;
    try { c.close(); } catch (Exception ignored) {}
  }

  private static int dp(Activity a, int v) {
    return Math.round(v * a.getResources().getDisplayMetrics().density);
  }
}