package com.my.mealse.messenger.storage;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.my.mealse.util.MealseLogger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/**
 * Хранилище вложений мессенджера.
 *
 * ★ Требование заказчика: «При использовании мессенджера, отправки сообщений, медиафайлов, нужно использовать соответствующие папки во внутреннем файловом менеджере, для хранения этих данных.»
 *
 * ★ Решение: Все вложения хранятся в getFilesDir()/messenger/attachments/<conversationId>/. Это внутри «📦 Приложение» — файловый менеджер видит их сразу. - Входящие: при приёме FILE/IMAGE сообщения сохраняем blob в attachments. - Исходящие: копируем выбранный файл в attachments (или создаём из Uri). - При удалении сообщения/диалога — AttachmentStorage.deleteFor*() чистит папку.
 *
 * ★ Структура: getFilesDir()/messenger/attachments/<convId>/<messageId>_<filename> (messageId в имени — чтобы не было коллизий между одноимёнными файлами)
 *
 * ★ В MVP (текстовые сообщения) этот класс не используется — он подготовлен под Шаг интеграции вложений. API финальный: - saveFromUri(convId, msgId, uri, displayName) → File - saveFromBytes(convId, msgId, filename, bytes) → File - getFile(convId, msgId, filename) → File (null если нет) - listByConversation(convId) → List<File> - deleteForConversation(convId) - deleteForMessage(convId, msgId) - getAttachmentsRoot()
 */
public final class AttachmentStorage {

  private static final String TAG = "MessengerAttach";
  private static final String ROOT_DIR = "messenger";
  private static final String ATTACH_DIR = "attachments";

  private AttachmentStorage() {
  }

  // ============================================================
  // Пути
  // ============================================================

  @NonNull
  public static File getAttachmentsRoot(@NonNull Context context) {
    File root = new File(context.getFilesDir(), ROOT_DIR);
    File att = new File(root, ATTACH_DIR);
    if (!att.exists()) {
      att.mkdirs();
    }
    return att;
  }

  @NonNull
  public static File getConversationDir(@NonNull Context context, @NonNull String conversationId) {
    File dir = new File(getAttachmentsRoot(context), sanitize(conversationId));
    if (!dir.exists()) {
      dir.mkdirs();
    }
    return dir;
  }

  // ============================================================
  // Сохранение
  // ============================================================

  /**
   * Сохраняет вложение из Uri в папку диалога. Имя файла = <messageId>_<displayName>.
   *
   * @return File или null при ошибке
   */
  @Nullable
  public static File saveFromUri(@NonNull Context context, @NonNull String conversationId,
      @NonNull String messageId, @NonNull Uri uri, @Nullable String displayName) {
    Context appCtx = context.getApplicationContext();
    MealseLogger log = MealseLogger.get(appCtx, TAG);

    if (displayName == null || displayName.isEmpty()) {
      displayName = "file_" + System.currentTimeMillis();
    }
    String safeName = sanitize(displayName);
    String fileName = sanitize(messageId) + "_" + safeName;

    File dir = getConversationDir(appCtx, conversationId);
    File dest = new File(dir, fileName);

    try (InputStream in = appCtx.getContentResolver().openInputStream(uri);
        OutputStream out = new BufferedOutputStream(new FileOutputStream(dest))) {
      if (in == null) {
        log.warn("saveFromUri: openInputStream == null для " + uri);
        return null;
      }
      InputStream bin = (in instanceof BufferedInputStream) ? in
          : new BufferedInputStream(in, 64 * 1024);
      byte[] buf = new byte[64 * 1024];
      int r;
      long total = 0;
      while ((r = bin.read(buf)) != -1) {
        out.write(buf, 0, r);
        total += r;
      }
      out.flush();
      log.log("saveFromUri: " + dest.getAbsolutePath() + " (" + total + "B)");
      return dest;
    } catch (IOException e) {
      log.logError("saveFromUri: " + uri, e);
      if (dest.exists())
        dest.delete();
      return null;
    }
  }

  /**
   * Сохраняет вложение из байтов (для будущего приёма через Wi-Fi Direct, когда blob приходит в кадре).
   */
  @Nullable
  public static File saveFromBytes(@NonNull Context context, @NonNull String conversationId,
      @NonNull String messageId, @NonNull String fileName, @NonNull byte[] bytes) {
    Context appCtx = context.getApplicationContext();
    MealseLogger log = MealseLogger.get(appCtx, TAG);

    File dir = getConversationDir(appCtx, conversationId);
    File dest = new File(dir, sanitize(messageId) + "_" + sanitize(fileName));
    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(dest))) {
      out.write(bytes);
      out.flush();
      log.log("saveFromBytes: " + dest.getAbsolutePath() + " (" + bytes.length + "B)");
      return dest;
    } catch (IOException e) {
      log.logError("saveFromBytes", e);
      if (dest.exists())
        dest.delete();
      return null;
    }
  }

  /**
   * Копирует локальный файл (например, из другого источника) в attachments.
   */
  @Nullable
  public static File saveFromFile(@NonNull Context context, @NonNull String conversationId,
      @NonNull String messageId, @NonNull File src) {
    Context appCtx = context.getApplicationContext();
    MealseLogger log = MealseLogger.get(appCtx, TAG);

    if (!src.exists() || !src.isFile()) {
      log.warn("saveFromFile: не файл " + src);
      return null;
    }
    File dir = getConversationDir(appCtx, conversationId);
    File dest = new File(dir, sanitize(messageId) + "_" + sanitize(src.getName()));
    try (InputStream in = new BufferedInputStream(new FileInputStream(src));
        OutputStream out = new BufferedOutputStream(new FileOutputStream(dest))) {
      byte[] buf = new byte[64 * 1024];
      int r;
      while ((r = in.read(buf)) != -1) {
        out.write(buf, 0, r);
      }
      out.flush();
      log.log("saveFromFile: " + src + " → " + dest.getAbsolutePath());
      return dest;
    } catch (IOException e) {
      log.logError("saveFromFile", e);
      if (dest.exists())
        dest.delete();
      return null;
    }
  }

  /**
   * ★ Шаг 16.8.2: перемещает файл в AttachmentStorage (renameTo с fallback на copy+delete для cross-FS).
   *
   * Используется MessageReceiver при приёме FILE через /signal/file: ServerService пишет в getCacheDir()/incoming/, MessageReceiver перемещает в getFilesDir()/messenger/attachments/<convId>/.
   *
   * Обычно getCacheDir() и getFilesDir() на одной ФС (/data) — renameTo срабатывает мгновенно (без копирования 10 МБ). Если ФС разные — fallback на copy+delete.
   *
   * @return File в AttachmentStorage или null при ошибке
   */
  @Nullable
  public static File moveFromFile(@NonNull Context context, @NonNull String conversationId,
      @NonNull String messageId, @NonNull File src) {
    Context appCtx = context.getApplicationContext();
    MealseLogger log = MealseLogger.get(appCtx, TAG);

    if (!src.exists() || !src.isFile()) {
      log.warn("moveFromFile: не файл " + src);
      return null;
    }
    File dir = getConversationDir(appCtx, conversationId);
    File dest = new File(dir, sanitize(messageId) + "_" + sanitize(src.getName()));

    // Попытка renameTo (мгновенно, если одна ФС)
    if (src.renameTo(dest)) {
      log.log("moveFromFile: renameTo OK " + src + " → " + dest.getAbsolutePath());
      return dest;
    }

    // Fallback: copy + delete
    log.log("moveFromFile: renameTo failed, fallback на copy+delete");
    File saved = saveFromFile(appCtx, conversationId, messageId, src);
    if (saved != null) {
      // noinspection ResultOfMethodCallIgnored
      src.delete();
      log.log("moveFromFile: copy+delete OK " + saved.getAbsolutePath());
    }
    return saved;
  }

  // ============================================================
  // Чтение
  // ============================================================

  /**
   * Ищет файл по имени (или по messageId-префиксу). Если filename == null — возвращает первый файл с префиксом messageId_.
   */
  @Nullable
  public static File getFile(@NonNull Context context, @NonNull String conversationId,
      @NonNull String messageId, @Nullable String filename) {
    File dir = getConversationDir(context.getApplicationContext(), conversationId);
    if (filename != null) {
      File f = new File(dir, sanitize(messageId) + "_" + sanitize(filename));
      return f.exists() ? f : null;
    }
    // Поиск по префиксу
    String prefix = sanitize(messageId) + "_";
    File[] files = dir.listFiles();
    if (files != null) {
      for (File f : files) {
        if (f.getName().startsWith(prefix)) {
          return f;
        }
      }
    }
    return null;
  }

  @NonNull
  public static File[] listByConversation(@NonNull Context context,
      @NonNull String conversationId) {
    File dir = getConversationDir(context.getApplicationContext(), conversationId);
    File[] files = dir.listFiles();
    return files != null ? files : new File[0];
  }

  // ============================================================
  // Удаление
  // ============================================================

  public static void deleteForMessage(@NonNull Context context, @NonNull String conversationId,
      @NonNull String messageId) {
    File dir = getConversationDir(context.getApplicationContext(), conversationId);
    String prefix = sanitize(messageId) + "_";
    File[] files = dir.listFiles();
    if (files != null) {
      for (File f : files) {
        if (f.getName().startsWith(prefix)) {
          if (f.delete()) {
            MealseLogger.get(context.getApplicationContext(), TAG)
                .log("deleteForMessage: " + f.getAbsolutePath());
          }
        }
      }
    }
  }

  public static void deleteForConversation(@NonNull Context context,
      @NonNull String conversationId) {
    File dir = getConversationDir(context.getApplicationContext(), conversationId);
    File[] files = dir.listFiles();
    if (files != null) {
      for (File f : files) {
        f.delete();
      }
    }
    dir.delete();
  }

  // ============================================================
  // Утилиты
  // ============================================================

  /**
   * Санитизация имени: убираем / \ : * ? " < > |, пробелы оставляем. Пустое имя заменяем на UUID.
   */
  private static String sanitize(String input) {
    if (input == null || input.isEmpty()) {
      return "file_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
    String s = input.replaceAll("[\\\\/:*?\"<>|]", "_");
    s = s.trim();
    if (s.isEmpty()) {
      s = "file_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
    if (s.length() > 80) {
      s = s.substring(0, 80);
    }
    return s;
  }

  /**
   * ★ НОВОЕ: пакует директорию в zip-архив во временный файл.
   * 
   * @return File zip или null при ошибке
   */
  @Nullable
  public static File zipDirectory(@NonNull Context context, @NonNull File dir) {
    if (dir == null || !dir.isDirectory())
      return null;
    File cacheDir = new File(context.getCacheDir(), "zip_tmp");
    if (!cacheDir.exists())
      cacheDir.mkdirs();
    String baseName = dir.getName();
    if (baseName == null || baseName.isEmpty())
      baseName = "folder";
    File zipFile = new File(cacheDir, baseName + "_" + System.currentTimeMillis() + ".zip");
    try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
        new java.io.BufferedOutputStream(new java.io.FileOutputStream(zipFile), 64 * 1024))) {
      zipRecursive(zos, dir, dir.getName() + "/");
      zos.finish();
      MealseLogger.get(context, TAG).log("zipDirectory: " + dir + " → " + zipFile.getAbsolutePath()
          + " (" + zipFile.length() + "B)");
      return zipFile;
    } catch (Exception e) {
      MealseLogger.get(context, TAG).logError("zipDirectory", e);
      if (zipFile.exists())
        zipFile.delete();
      return null;
    }
  }

  private static void zipRecursive(java.util.zip.ZipOutputStream zos, File file, String entryName)
      throws java.io.IOException {
    if (file.isDirectory()) {
      File[] children = file.listFiles();
      if (children == null || children.length == 0) {
        // Пустая папка — добавляем пустую entry
        zos.putNextEntry(new java.util.zip.ZipEntry(entryName));
        zos.closeEntry();
        return;
      }
      for (File child : children) {
        zipRecursive(zos, child, entryName + child.getName() + (child.isDirectory() ? "/" : ""));
      }
    } else {
      byte[] buf = new byte[64 * 1024];
      try (java.io.InputStream in = new java.io.BufferedInputStream(
          new java.io.FileInputStream(file), 64 * 1024)) {
        zos.putNextEntry(new java.util.zip.ZipEntry(entryName));
        int r;
        while ((r = in.read(buf)) != -1)
          zos.write(buf, 0, r);
        zos.closeEntry();
      }
    }
  }
}