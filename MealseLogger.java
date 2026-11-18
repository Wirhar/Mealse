package com.my.mealse.util;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Централизованный логгер для Mealse.
 *
 * Особенности:
 *  - Singleton на тег: один FileWriter на тег, потокобезопасно.
 *  - Файлы: /storage/emulated/0/MealseLogs/<tag>.log (если доступно),
 *    иначе getExternalFilesDir()/MealseLogs/<tag>.log.
 *  - ОБЩИЙ ЛОГ: все экземпляры дополнительно пишут в mealse_all.txt
 *    (та же директория, что и теговые логи). Каждая строка помечена
 *    тегом источника. Файл закрывается только когда закрыт последний логгер.
 *  - Без ротации — файл растёт, чтобы можно было открыть в текстовом редакторе.
 *  - Дублирует вывод в android.util.Log (для Logcat).
 *  - Обрабатывает stack trace.
 *
 *  - Вложенный LogcatCapture: постоянный захват системного logcat
 *    (буферы main+system+events) в logcat_all.txt + выгрузка нужного окна
 *    в logcat_<from>_<to>.txt. Нужен для диагностики того, КТО гасит
 *    активити (am_destroy_activity и пр.) — файловые логи этого не показывают.
 *    ВАЖНО: без разрешения READ_LOGS видны только собственные строки:
 *        adb shell pm grant com.my.mealse android.permission.READ_LOGS
 *
 *  - Вложенный CrashHandler: пишет crash.log при необработанном исключении.
 */
public class MealseLogger {

  private static final String TAG_BASE = "MealseLogger";
  public static final String LOG_DIR_NAME = "MealseLogs";
  private static final String SHARED_LOG_NAME = "mealse_all.txt";
  private static final SimpleDateFormat TS_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",
      Locale.getDefault());

  // Кэш логгеров: один экземпляр на тег
  private static final ConcurrentHashMap<String, MealseLogger> instances = new ConcurrentHashMap<>();

  // ---------------- Общий лог (все классы) ----------------

  private static File sharedLogFile;
  private static FileWriter sharedWriter;
  private static final Object sharedLock = new Object();
  private static final AtomicInteger activeLoggers = new AtomicInteger(0);

  private final String tag;
  private final File logFile;
  private FileWriter writer;
  private final Object writeLock = new Object();
  private volatile boolean closed = false;

  // ---------------- Singleton ----------------

  public static MealseLogger get(Context context, String tag) {
    if (tag == null || tag.isEmpty())
      tag = "default";
    final String normalizedTag = sanitizeTag(tag);
    return instances.computeIfAbsent(normalizedTag,
        t -> new MealseLogger(context.getApplicationContext(), t));
  }

  private MealseLogger(Context appContext, String tag) {
    this.tag = tag;
    this.logFile = initLogFile(appContext, tag);
    openWriter(true);
    initSharedWriter(appContext);
    activeLoggers.incrementAndGet();
  }

  // ---------------- Инициализация ----------------

  private static File initLogFile(Context context, String tag) {
    File dir = resolveLogDir(context);
    if (dir == null) {
      // Аварийный fallback: внутреннее хранилище
      dir = new File(context.getFilesDir(), LOG_DIR_NAME);
    }
    if (!dir.exists() && !dir.mkdirs()) {
      Log.e(TAG_BASE, "Не удалось создать директорию: " + dir);
    }

    File file = new File(dir, tag.toLowerCase(Locale.ROOT) + ".log");
    // Без ротации — просто убеждаемся, что файл существует
    if (!file.exists()) {
      try {
        // noinspection ResultOfMethodCallIgnored
        file.createNewFile();
      } catch (IOException e) {
        Log.e(TAG_BASE, "Не удалось создать файл лога: " + file, e);
      }
    }
    Log.d(TAG_BASE, "Лог-файл: " + file.getAbsolutePath());
    return file;
  }

  /**
   * Инициализация общего файла mealse_all.txt. Выполняется один раз
   * (первым созданным логгером), последующие экземпляры пишут в него же.
   */
  private static void initSharedWriter(Context appContext) {
    synchronized (sharedLock) {
      if (sharedWriter != null)
        return;
      try {
        File dir = resolveLogDir(appContext);
        if (dir == null) {
          dir = new File(appContext.getFilesDir(), LOG_DIR_NAME);
        }
        if (!dir.exists() && !dir.mkdirs()) {
          Log.e(TAG_BASE, "Не удалось создать директорию общего лога: " + dir);
        }
        sharedLogFile = new File(dir, SHARED_LOG_NAME);
        if (!sharedLogFile.exists()) {
          // noinspection ResultOfMethodCallIgnored
          sharedLogFile.createNewFile();
        }
        sharedWriter = new FileWriter(sharedLogFile, true);
        String header = "\n=== Mealse — общий лог (все классы) — "
            + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date())
            + " ===\n";
        sharedWriter.write(header);
        sharedWriter.flush();
        Log.d(TAG_BASE, "Общий лог-файл: " + sharedLogFile.getAbsolutePath());
      } catch (IOException e) {
        Log.e(TAG_BASE, "Не удалось открыть общий лог-файл", e);
        sharedWriter = null;
      }
    }
  }

  /**
   * Запись строки в общий лог. Тихо пропускается, если writer не открыт.
   */
  private static void writeShared(String line) {
    synchronized (sharedLock) {
      if (sharedWriter == null)
        return;
      try {
        sharedWriter.write(line);
        sharedWriter.flush();
      } catch (IOException e) {
        Log.e(TAG_BASE, "Ошибка записи в общий лог " + sharedLogFile, e);
      }
    }
  }

  /**
   * Путь к общему лог-файлу (или null, если ещё не создавался).
   */
  public static File getSharedLogFile() {
    return sharedLogFile;
  }

  /**
   * Определяет директорию для логов.
   *
   * Порядок:
   *  1. /storage/emulated/0/MealseLogs/ (публичная, если доступна)
   *  2. getExternalFilesDir()/MealseLogs/ (app-specific)
   *  3. null → вызывающий использует внутреннее хранилище
   */
  public static File resolveLogDir(Context context) {
    // 1. Пытаемся создать публичную папку в корне внешнего хранилища
    File publicDir = tryPublicLogDir();
    if (publicDir != null && canWrite(publicDir)) {
      return publicDir;
    }

    // 2. Fallback — app-specific внешнее
    try {
      File external = context.getExternalFilesDir(null);
      if (external != null
          && (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())
              || !Environment.isExternalStorageRemovable())) {
        File appDir = new File(external, LOG_DIR_NAME);
        if (!appDir.exists()) {
          // noinspection ResultOfMethodCallIgnored
          appDir.mkdirs();
        }
        if (canWrite(appDir)) {
          return appDir;
        }
      }
    } catch (Exception e) {
      Log.e(TAG_BASE, "Ошибка app-specific внешнего хранилища", e);
    }

    return null;
  }

  /**
   * Пытается получить /storage/emulated/0/MealseLogs/.
   * На API 29+ вернёт директорию, но запись в неё не пройдёт (canWrite=false).
   */
  private static File tryPublicLogDir() {
    try {
      File root = Environment.getExternalStorageDirectory();
      if (root == null)
        return null;
      File dir = new File(root, LOG_DIR_NAME);
      if (!dir.exists() && !dir.mkdirs()) {
        return null;
      }
      return dir;
    } catch (Exception e) {
      Log.e(TAG_BASE, "Ошибка публичной директории", e);
      return null;
    }
  }

  private static boolean canWrite(File dir) {
    if (dir == null || !dir.exists() || !dir.isDirectory())
      return false;
    File probe = new File(dir, ".probe_" + System.currentTimeMillis());
    try {
      if (probe.createNewFile()) {
        // noinspection ResultOfMethodCallIgnored
        probe.delete();
        return true;
      }
    } catch (IOException ignored) {
    }
    return false;
  }

  /** Директория логов с гарантированным fallback во внутреннее хранилище. */
  private static File resolveDirOrInternal(Context context) {
    File dir = resolveLogDir(context);
    if (dir == null) {
      dir = new File(context.getFilesDir(), LOG_DIR_NAME);
    }
    if (!dir.exists()) {
      // noinspection ResultOfMethodCallIgnored
      dir.mkdirs();
    }
    return dir;
  }

  private static String sanitizeTag(String tag) {
    return tag.replaceAll("[^a-zA-Z0-9_\\-]", "_");
  }

  // ---------------- Writer ----------------

  private void openWriter(boolean append) {
    synchronized (writeLock) {
      try {
        if (writer != null) {
          try {
            writer.close();
          } catch (IOException ignored) {
          }
        }
        writer = new FileWriter(logFile, append);
        writeHeader();
      } catch (IOException e) {
        Log.e(TAG_BASE, "Не удалось открыть writer для " + logFile, e);
        writer = null;
      }
    }
  }

  private void writeHeader() {
    try {
      String header = "\n=== " + tag + " — "
          + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date())
          + " ===\n";
      writer.write(header);
      writer.flush();
    } catch (IOException e) {
      Log.e(TAG_BASE, "Не удалось записать заголовок", e);
    }
  }

  // ---------------- Logging API ----------------

  public void log(String message) {
    if (closed)
      return;
    if (message == null)
      message = "null";

    String line = formatLine(message);
    synchronized (writeLock) {
      if (writer != null) {
        try {
          writer.write(line);
          writer.flush();
        } catch (IOException e) {
          Log.e(TAG_BASE, "Ошибка записи в " + logFile, e);
        }
      }
    }
    // Дублируем в общий лог с тегом источника
    writeShared(formatLine("[" + tag + "] " + message));
    Log.d(tag, message);
  }

  public void warn(String message) {
    log("⚠ " + message);
  }

  public void error(String message) {
    log("❌ " + message);
  }

  public void logError(String message, Throwable e) {
    String errorMsg = "❌ " + message + (e != null ? " — " + e.getMessage() : "");
    log(errorMsg);
    Log.e(tag, message, e);

    if (e != null) {
      StringWriter sw = new StringWriter();
      PrintWriter pw = new PrintWriter(sw);
      e.printStackTrace(pw);
      writeRaw("Stack trace:\n" + sw.toString());
    }
  }

  /**
   * @deprecated используйте {@link #logError(String, Throwable)}
   */
  @Deprecated
  public void logException(String message, Throwable e) {
    logError(message, e);
  }

  // Специализированные методы

  public void logSignal(String direction, String type, String callId) {
    log("📡 [" + direction + "] " + type + " (call=" + shortId(callId) + ")");
  }

  public void logHttp(String method, String url, int code) {
    log("🌐 " + method + " " + url + " → " + code);
  }

  public void logState(String component, String state) {
    log("🔄 [" + component + "] " + state);
  }

  // ---------------- Внутренние ----------------

  private String formatLine(String message) {
    return TS_FORMAT.format(new Date()) + " | " + message + "\n";
  }

  private void writeRaw(String text) {
    synchronized (writeLock) {
      if (writer != null) {
        try {
          writer.write(text);
          writer.flush();
        } catch (IOException e) {
          Log.e(TAG_BASE, "Ошибка записи (raw)", e);
        }
      }
    }
    // Stack trace и прочий raw — тоже в общий лог (с тегом источника)
    writeShared(formatLine("[" + tag + "] " + text));
  }

  private String shortId(String id) {
    if (id == null)
      return "?";
    return id.length() > 8 ? id.substring(0, 8) : id;
  }

  // ---------------- Экспорт логов ----------------

  public static List<File> getAllLogFiles(Context context) {
    List<File> result = new ArrayList<>();
    File dir = resolveDirOrInternal(context);
    if (dir.exists() && dir.isDirectory()) {
      File[] files = dir.listFiles();
      if (files != null) {
        for (File f : files) {
          if (f.isFile() && (f.getName().endsWith(".log")
              || f.getName().endsWith(".txt"))) {
            result.add(f);
          }
        }
      }
    }
    return result;
  }

  public static void clearAllLogs(Context context) {
    for (File f : getAllLogFiles(context)) {
      // noinspection ResultOfMethodCallIgnored
      f.delete();
    }
  }

  public static String getLogDirPath(Context context) {
    return resolveDirOrInternal(context).getAbsolutePath();
  }

  /**
   * Открывает папку с логами через файловый менеджер. Не требует FileProvider,
   * но зависит от поддержки файловым менеджером.
   */
  public static Intent buildOpenFolderIntent(Context context) {
    File dir = resolveDirOrInternal(context);

    try {
      Uri uri = Uri.parse("file://" + dir.getAbsolutePath());
      Intent intent = new Intent(Intent.ACTION_VIEW);
      intent.setDataAndType(uri, "resource/folder");
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      return intent;
    } catch (Exception e) {
      Log.e(TAG_BASE, "Не удалось создать folder intent", e);
      return null;
    }
  }

  /**
   * @deprecated Используйте {@link #buildOpenFolderIntent(Context)} или
   * {@link #buildShareIntent(Context)}. На API 24+ без FileProvider может
   * бросить FileUriExposedException.
   */
  @Deprecated
  public static Intent buildOpenIntent(Context context, File logFile) {
    if (logFile == null || !logFile.exists())
      return null;
    try {
      Uri uri = Uri.fromFile(logFile);
      Intent intent = new Intent(Intent.ACTION_VIEW);
      intent.setDataAndType(uri, "text/plain");
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      return intent;
    } catch (Exception e) {
      Log.e(TAG_BASE, "Не удалось создать open intent", e);
      return null;
    }
  }

  /**
   * Формирует шаринг-интент для списка логов (текстом, без FileProvider).
   */
  public static Intent buildShareIntent(Context context) {
    List<File> files = getAllLogFiles(context);
    if (files.isEmpty()) {
      Intent i = new Intent(Intent.ACTION_SEND);
      i.setType("text/plain");
      i.putExtra(Intent.EXTRA_TEXT, "Логи отсутствуют\nПапка: " + getLogDirPath(context));
      return Intent.createChooser(i, "Поделиться логами");
    }

    StringBuilder sb = new StringBuilder("Логи Mealse:\n\n");
    sb.append("Папка: ").append(getLogDirPath(context)).append("\n\n");
    for (File f : files) {
      sb.append(f.getName()).append(" (").append(f.length() / 1024).append(" КБ)\n");
    }

    Intent i = new Intent(Intent.ACTION_SEND);
    i.setType("text/plain");
    i.putExtra(Intent.EXTRA_SUBJECT, "Mealse logs");
    i.putExtra(Intent.EXTRA_TEXT, sb.toString());
    return Intent.createChooser(i, "Поделиться логами");
  }

  // ---------------- Закрытие ----------------

  public void close() {
    synchronized (writeLock) {
      if (writer != null) {
        try {
          writer.write("=== End of log ===\n");
          writer.flush();
          writer.close();
        } catch (IOException e) {
          Log.e(TAG_BASE, "Ошибка закрытия writer", e);
        }
        writer = null;
      }
    }
    closed = true;
    instances.remove(tag);

    // Общий лог закрываем только когда закрыт последний логгер
    int remaining = activeLoggers.decrementAndGet();
    if (remaining <= 0) {
      closeSharedWriter();
    }
  }

  private static void closeSharedWriter() {
    synchronized (sharedLock) {
      if (sharedWriter != null) {
        try {
          sharedWriter.write("=== End of shared log ===\n");
          sharedWriter.flush();
          sharedWriter.close();
        } catch (IOException e) {
          Log.e(TAG_BASE, "Ошибка закрытия общего writer", e);
        }
        sharedWriter = null;
      }
    }
  }

  public static void closeAll() {
    for (MealseLogger logger : instances.values()) {
      try {
        logger.close();
      } catch (Exception ignored) {
      }
    }
    instances.clear();
    closeSharedWriter();
  }

  /**
   * Полностью сбрасывает все логгеры и их writer'ы (включая общий лог).
   *
   * Используется, когда изменилась директория логов (например, после выдачи
   * MANAGE_EXTERNAL_STORAGE). Следующий вызов get() создаст новые writer'ы.
   */
  public static void resetAll() {
    closeAll();
    activeLoggers.set(0);
    Log.d(TAG_BASE, "Все логгеры сброшены — директория будет пересчитана");
  }

  public String getLogFilePath() {
    return logFile != null ? logFile.getAbsolutePath() : null;
  }

  // ============================================================
  // Статические обёртки над вложенными компонентами
  // ============================================================

  /** Запускает постоянный захват logcat (см. {@link LogcatCapture}). */
  public static void startLogcatCapture(Context context) {
    LogcatCapture.start(context);
  }

  /** Останавливает постоянный захват logcat. */
  public static void stopLogcatCapture() {
    LogcatCapture.stop();
  }

  /**
   * Выгружает строки системного logcat за интервал в файл
   * logcat_<from>_<to>.txt в директории логов MealseLogger.
   *
   * @param startIso "2026-11-17 21:09:00"
   * @param endIso   "2026-11-17 21:15:30"
   * @return файл или null, если строк за окно не нашлось
   */
  public static File extractLogcatWindow(Context context, String startIso, String endIso) {
    return LogcatCapture.extractWindow(context, startIso, endIso);
  }

  /** Выгрузка окна logcat по millis. */
  public static File extractLogcatWindow(Context context, long startMillis, long endMillis) {
    return LogcatCapture.extractRange(context, startMillis, endMillis);
  }

  /** Устанавливает обработчик крэшей (см. {@link CrashHandler}). */
  public static void installCrashHandler(Context context) {
    CrashHandler.install(context);
  }

  /**
   * Путь к последнему выгруженному окну logcat (или null).
   */
  public static File getLastExtractedLogcatFile() {
    return LogcatCapture.lastExtracted;
  }

  // ============================================================
  // Вложенный класс: захват системного logcat
  // ============================================================

  /**
   * Захват системного logcat в файлы /MealseLogs/.
   *
   * Зачем: файловые логи приложения не показывают, кто гасит активити.
   * Это делает ActivityManager — его события (am_destroy_activity, am_kill,
   * am_proc_died, am_low_memory) живут в системном буфере events.
   *
   * Режимы:
   *  1. start(context) — фоновый поток постоянно читает буферы main+system+events
   *     и пишет в logcat_all.txt. Ротация: при >5 МБ файл переезжает
   *     в logcat_all.1/.2/.3 (старший удаляется). Вызывать один раз
   *     в MealseApp.onCreate. Выгрузка окна — extractWindow/extractRange.
   *
   *  2. extractWindow(context, startIso, endIso) — выгружает из текущих буферов
   *     строки за указанный интервал в logcat_<from>_<to>.txt
   *     (та же директория, что и остальные логи MealseLogger).
   *
   * ВАЖНО: без разрешения READ_LOGS приложение видит через logcat только
   * СВОИ строки (main), буферы events/system будут пустыми. Выдать разово:
   *     adb shell pm grant com.my.mealse android.permission.READ_LOGS
   *
   * Формат строк с -v time: "11-17 21:09:30.123 Tag: message".
   * Строки без метки времени (продолжение stack trace) наследуются
   * от предыдущей строки: если она в окне — они тоже попадают в файл.
   */
  public static final class LogcatCapture {

    private static final String TAG = "MealseLogger.LogcatCapture";
    private static final String LIVE_FILE = "logcat_all.txt";
    private static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    private static final int KEEP_OLD = 3;

    private static final SimpleDateFormat TS_PARSE =
        new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
    private static final SimpleDateFormat TS_FILE =
        new SimpleDateFormat("HHmmss", Locale.US);
    private static final SimpleDateFormat TS_WINDOW =
        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    private static final Object lock = new Object();
    private static volatile boolean running = false;
    private static volatile Process logcatProcess;
    private static File liveFile;
    private static File lastExtracted;

    private LogcatCapture() {
    }

    /** Запускает фоновый захват logcat. Повторный вызов — no-op. */
    public static void start(Context context) {
      synchronized (lock) {
        if (running)
          return;
        File dir = resolveDirOrInternal(context);
        try {
          liveFile = new File(dir, LIVE_FILE);
          if (!liveFile.exists()) {
            // noinspection ResultOfMethodCallIgnored
            liveFile.createNewFile();
          }
        } catch (Exception e) {
          Log.e(TAG, "Не удалось создать " + liveFile, e);
          return;
        }
        running = true;
        Thread t = new Thread(LogcatCapture::captureLoop, "MealseLogcatCapture");
        t.setDaemon(true);
        t.start();
        Log.i(TAG, "Постоянный захват logcat запущен → " + liveFile.getAbsolutePath());
      }
    }

    /** Останавливает фоновый захват. */
    public static void stop() {
      synchronized (lock) {
        running = false;
        Process p = logcatProcess;
        if (p != null) {
          try {
            p.destroy();
          } catch (Throwable ignored) {
          }
        }
        logcatProcess = null;
      }
    }

    private static void captureLoop() {
      while (running) {
        Process p = null;
        Writer w = null;
        BufferedReader r = null;
        try {
          ProcessBuilder pb = new ProcessBuilder(
              "logcat", "-v", "time",
              "-b", "main", "-b", "system", "-b", "events");
          pb.redirectErrorStream(false);
          p = pb.start();
          logcatProcess = p;
          r = new BufferedReader(
              new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8), 64 * 1024);
          w = openWriter();
          String line;
          while (running && (line = r.readLine()) != null) {
            if (liveFile != null && liveFile.length() > MAX_FILE_BYTES) {
              try {
                w.flush();
                w.close();
              } catch (Throwable ignored) {
              }
              rotate();
              w = openWriter();
            }
            w.write(line);
            w.write('\n');
          }
        } catch (Throwable t) {
          Log.e(TAG, "Захват прерван: " + t);
        } finally {
          if (w != null) {
            try {
              w.flush();
              w.close();
            } catch (Throwable ignored) {
            }
          }
          if (r != null) {
            try {
              r.close();
            } catch (Throwable ignored) {
            }
          }
          if (p != null) {
            try {
              p.destroy();
            } catch (Throwable ignored) {
            }
          }
          logcatProcess = null;
        }
        if (running) {
          // logcat умер — перезапускаем через 2 секунды
          try {
            Thread.sleep(2000);
          } catch (InterruptedException e) {
            break;
          }
        }
      }
    }

    private static Writer openWriter() throws Exception {
      return new BufferedWriter(new OutputStreamWriter(
          new FileOutputStream(liveFile, true), StandardCharsets.UTF_8), 64 * 1024);
    }

    private static void rotate() throws Exception {
      File parent = liveFile.getParentFile();
      for (int i = KEEP_OLD - 1; i >= 1; i--) {
        File from = new File(parent, LIVE_FILE + "." + i);
        if (from.exists()) {
          File to = new File(parent, LIVE_FILE + "." + (i + 1));
          if (to.exists()) {
            // noinspection ResultOfMethodCallIgnored
            to.delete();
          }
          // noinspection ResultOfMethodCallIgnored
          from.renameTo(to);
        }
      }
      File first = new File(parent, LIVE_FILE + ".1");
      if (first.exists()) {
        // noinspection ResultOfMethodCallIgnored
        first.delete();
      }
      // noinspection ResultOfMethodCallIgnored
      liveFile.renameTo(first);
      liveFile = new File(parent, LIVE_FILE);
      // noinspection ResultOfMethodCallIgnored
      liveFile.createNewFile();
    }

    // --------------------------------------------------------
    // Выгрузка окна
    // --------------------------------------------------------

    /**
     * Выгружает строки logcat за интервал в отдельный файл.
     *
     * @param startIso например "2026-11-17 21:09:00"
     * @param endIso   например "2026-11-17 21:15:30"
     * @return файл logcat_<from>_<to>.txt или null при ошибке
     */
    public static File extractWindow(Context context, String startIso, String endIso) {
      try {
        Date s = TS_WINDOW.parse(startIso);
        Date e = TS_WINDOW.parse(endIso);
        if (s == null || e == null)
          return null;
        return extractRange(context, s.getTime(), e.getTime());
      } catch (ParseException pe) {
        Log.e(TAG, "Неверный формат времени: " + startIso + " / " + endIso, pe);
        return null;
      }
    }

    /**
     * Выгрузка по millis. Буферы глубиной ~несколько часов: если окно старше —
     * строки уже затёрты, вызывайте сразу после теста.
     */
    public static File extractRange(Context context, long startMillis, long endMillis) {
      File dir = resolveDirOrInternal(context);

      String fname = "logcat_"
          + TS_FILE.format(new Date(startMillis)) + "_"
          + TS_FILE.format(new Date(endMillis)) + ".txt";
      File out = new File(dir, fname);

      int kept = 0;
      Writer w = null;
      Process p = null;
      BufferedReader r = null;
      try {
        w = new BufferedWriter(new OutputStreamWriter(
            new FileOutputStream(out), StandardCharsets.UTF_8));
        w.write("# logcat с " + TS_PARSE.format(new Date(startMillis))
            + " по " + TS_PARSE.format(new Date(endMillis))
            + " (буферы main+system+events)\n");

        p = Runtime.getRuntime().exec(new String[]{
            "logcat", "-d", "-v", "time",
            "-b", "main", "-b", "system", "-b", "events"});
        r = new BufferedReader(
            new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8), 64 * 1024);

        String line;
        boolean lastKept = false;
        while ((line = r.readLine()) != null) {
          long ts = parseLogcatTime(line);
          if (ts >= 0) {
            lastKept = ts >= startMillis && ts <= endMillis;
          }
          // строки без метки времени — продолжение предыдущей (stack trace)
          if (lastKept) {
            w.write(line);
            w.write('\n');
            kept++;
          }
        }
        try {
          p.waitFor();
        } catch (InterruptedException ignored) {
        }
      } catch (Throwable t) {
        Log.e(TAG, "Ошибка выгрузки logcat", t);
      } finally {
        if (w != null) {
          try {
            w.flush();
            w.close();
          } catch (Throwable ignored) {
          }
        }
        if (r != null) {
          try {
            r.close();
          } catch (Throwable ignored) {
          }
        }
        if (p != null) {
          try {
            p.destroy();
          } catch (Throwable ignored) {
          }
        }
      }
      Log.i(TAG, "extractRange: строк=" + kept + " → " + out.getAbsolutePath());
      lastExtracted = kept > 0 ? out : null;
      return lastExtracted;
    }

    /** Разбирает "MM-dd HH:mm:ss.SSS" из начала строки (год подгоняется к текущему, с защитой от Нового года). */
    private static long parseLogcatTime(String line) {
      if (line == null || line.length() < 18)
        return -1;
      char c0 = line.charAt(0);
      if (!(c0 >= '0' && c0 <= '9'))
        return -1;
      try {
        Date d = TS_PARSE.parse(line.substring(0, 18));
        if (d == null)
          return -1;
        long now = System.currentTimeMillis();
        long year = 365L * 24 * 60 * 60 * 1000;
        long ts = d.getTime();
        if (ts > now + year / 2) {
          ts -= year;
        }
        return ts;
      } catch (ParseException e) {
        return -1;
      }
    }
  }

  // ============================================================
  // Вложенный класс: обработчик необработанных исключений
  // ============================================================

  /**
   * Обработчик необработанных исключений: пишет crash.log в директорию
   * логов MealseLogger и передаёт управление предыдущему обработчику.
   *
   * crash.log содержит: время, поток, устройство, SDK, полный stack trace.
   *
   * Установка (MealseApp.onCreate, ПОСЛЕ разрешения storage,
   * до WifiDirectManager):
   *     MealseLogger.installCrashHandler(this);
   *
   * Если в приложении уже есть свой crash handler — он заменяется,
   * сохраняя цепочку (previous handler вызывается после записи файла).
   */
  public static final class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "MealseLogger.CrashHandler";
    private static final Object lock = new Object();

    private final Context appContext;
    private final Thread.UncaughtExceptionHandler previous;

    private CrashHandler(Context appContext, Thread.UncaughtExceptionHandler previous) {
      this.appContext = appContext;
      this.previous = previous;
    }

    /** Устанавливает обработчик. Повторный вызов — no-op. */
    public static void install(Context context) {
      Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
      if (prev instanceof CrashHandler)
        return;
      Thread.setDefaultUncaughtExceptionHandler(
          new CrashHandler(context.getApplicationContext(), prev));
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
      writeCrashLog(thread, throwable);
      if (previous != null) {
        previous.uncaughtException(thread, throwable);
      }
    }

    private void writeCrashLog(Thread thread, Throwable throwable) {
      synchronized (lock) {
        try {
          File dir = resolveDirOrInternal(appContext);
          File file = new File(dir, "crash.log");

          StringWriter sw = new StringWriter();
          throwable.printStackTrace(new PrintWriter(sw));

          String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
              .format(new Date());

          StringBuilder sb = new StringBuilder();
          sb.append("\n╔════════════════════════════════════════════╗\n");
          sb.append("CRASH — ").append(ts).append('\n');
          sb.append("Поток: ").append(thread.getName()).append('\n');
          sb.append("Устройство: ").append(Build.MANUFACTURER).append(' ')
              .append(Build.MODEL).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
          sb.append("Исключение: ").append(throwable.getClass().getName()).append('\n');
          sb.append("Сообщение: ").append(throwable.getMessage()).append('\n');
          sb.append("Стек:\n").append(sw.toString());
          sb.append("╚════════════════════════════════════════════╝\n");

          try (FileWriter fw = new FileWriter(file, true)) {
            fw.write(sb.toString());
            fw.flush();
          }
          Log.e(TAG, "crash записан: " + file.getAbsolutePath());
        } catch (Throwable t) {
          // на этом этапе нельзя ничего поднимать — только логировать
          Log.e(TAG, "Не удалось записать crash.log", t);
        }
      }
    }
  }
}
