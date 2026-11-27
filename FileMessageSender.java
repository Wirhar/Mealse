package com.my.mealse.messenger.transport;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.my.mealse.util.MealseLogger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

/**
 * Отправка файлов через HTTP POST /signal/file — БЕЗ base64.
 *
 * ★ Шаг 16.8.1: бинарный эндпоинт для экономии памяти.
 *
 * Было (base64): 10 МБ файл → base64 (~13.3 МБ String) → JsonObject → JSON String → byte[] (~13.4 МБ) → отправка. Итого ~63 МБ heap на отправке. Аналогично на приёме — риск OOM.
 *
 * Стало (raw bytes): Файл читается FileInputStream → OutputStream буфером 64 КБ. Пик: ~64 КБ. Экономия ~99% heap.
 *
 * ★ Улучшение №3 (прогресс): добавлен ProgressCallback. FileMessageSender.sendFile(..., ProgressCallback, ...) вызывает onProgress(written, total) не чаще, чем раз в 100 мс. DeliveryScheduler пробрасывает callback в SharedPreferences (mealse_messenger_progress) и broadcast MESSAGE_PROGRESS — UI (ChatActivity) видит прогресс в реальном времени.
 *
 * Формат запроса: POST /signal/file HTTP/1.1 Host: <ip>:<port> Content-Type: application/octet-stream Content-Length: <file.length()> X-Mealse-Envelope: <base64(envelopeJson)> — envelope без payload X-Mealse-Attachment-Name: <url-encoded name> X-Mealse-Attachment-Mime: <mime> X-Mealse-Attachment-Message-Id: <msgId>
 *
 * <raw file bytes>
 *
 * envelopeJson содержит from_device_id, from_fingerprint, to_fingerprint, conversation_id, message_id, timestamp, type=message. Payload (attachment name/mime/size) — в заголовках X-Mealse-Attachment-*.
 *
 * Ответ: 200 {"status":"ok","type":"file"} — успех 400 — нет X-Mealse-Envelope или file.length() != Content-Length 413 — file > MAX_FILE_SIZE 404 — получатель не поддерживает /signal/file (старая версия)
 *
 * Без fallback на base64: оба устройства обновляются вместе. 404 → markAttempt → 20 retry → FAILED (в логах — «получатель не обновился»).
 *
 * ⚠️ sendFile() блокирующий. Вызывается из DeliveryScheduler.executor (single-thread, фоновый). Не вызывать из main thread.
 */
public class FileMessageSender {

  private static final String TAG = "MessengerFileSender";

  private static final int CONNECT_TIMEOUT_MS = 15_000;
  private static final int READ_TIMEOUT_MS = 60_000; // файлы дольше
  private static final int BUFFER_SIZE = 64 * 1024;
  private static final long PROGRESS_REPORT_INTERVAL_MS = 100L;

  /** ★ 8 ГБ, синхронно с ServerService/FileChunkProtocol. BREAKING: обновить обе стороны. */
  public static final long MAX_FILE_SIZE = 8L * 1024 * 1024 * 1024;

  /**
   * ★ Улучшение №3: callback прогресса отправки. Вызывается из потока отправки (DeliveryScheduler.executor). Не чаще, чем раз в PROGRESS_REPORT_INTERVAL_MS = 100 мс.
   */
  public interface ProgressCallback {
    void onProgress(long written, long total);
  }

  private final Context appContext;
  private final MealseLogger log;

  public FileMessageSender(Context context) {
    this.appContext = context.getApplicationContext();
    this.log = MealseLogger.get(appContext, TAG);
  }

  // ============================================================
  // sendFile — перегрузка без прогресса (обратная совместимость)
  // ============================================================

  /**
   * Отправить файл (без callback прогресса).
   */
  public void sendFile(@NonNull String targetNodeKey, @NonNull String envelopeJson,
      @NonNull File file, @NonNull MessageSender.Callback callback) {
    sendFile(targetNodeKey, envelopeJson, file, null, callback);
  }

  // ============================================================
  // sendFile — с прогрессом
  // ============================================================

  /**
   * Отправить файл на targetNodeKey ("ip:port") бинарным POST.
   *
   * @param targetNodeKey "ip:port" получателя
   * @param envelopeJson  envelope.toJson() БЕЗ payload (только метаданные)
   * @param file          локальный файл
   * @param progress      callback прогресса (может быть null)
   * @param callback      результат
   */
  public void sendFile(@NonNull String targetNodeKey, @NonNull String envelopeJson,
      @NonNull File file, @Nullable ProgressCallback progress,
      @NonNull MessageSender.Callback callback) {

    long start = System.currentTimeMillis();

    if (!file.exists() || !file.isFile()) {
      callback
          .onResult(SendResult.error("http-file", "file not found: " + file.getAbsolutePath(), 0));
      return;
    }

    long size = file.length();
    if (size <= 0) {
      callback.onResult(SendResult.error("http-file", "file empty", 0));
      return;
    }
    if (size > MAX_FILE_SIZE) {
      callback.onResult(SendResult.error("http-file",
          "file too large: " + size + "B > " + MAX_FILE_SIZE + "B", 0));
      return;
    }

    String ip;
    int port;
    try {
      int idx = targetNodeKey.lastIndexOf(':');
      if (idx <= 0) {
        callback.onResult(SendResult.error("http-file", "invalid nodeKey: " + targetNodeKey, 0));
        return;
      }
      ip = targetNodeKey.substring(0, idx);
      port = Integer.parseInt(targetNodeKey.substring(idx + 1));
    } catch (Exception e) {
      callback.onResult(SendResult.error("http-file", "parse nodeKey: " + e.getMessage(), 0));
      return;
    }

    String urlStr = "http://" + formatHost(ip) + ":" + port + "/signal/file";
    log.log("sendFile: POST " + urlStr + ", size=" + size + "B, file=" + file.getName()
        + ", envelope=" + envelopeJson.length() + " chars");

    HttpURLConnection conn = null;
    try {
      URL url = new URL(urlStr);
      conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("POST");
      conn.setRequestProperty("Content-Type", "application/octet-stream");
      conn.setRequestProperty("Accept", "application/json");
      conn.setRequestProperty("User-Agent", "Mealse-Messenger-File/1.0");

      // Метаданные в заголовках
      conn.setRequestProperty("X-Mealse-Envelope", android.util.Base64.encodeToString(
          envelopeJson.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP));
      conn.setRequestProperty("X-Mealse-Attachment-Name", urlEncode(file.getName()));
      conn.setRequestProperty("X-Mealse-Attachment-Mime",
          com.my.mealse.messenger.model.AttachmentInfo.guessMimeByName(file.getName()));
      conn.setRequestProperty("X-Mealse-Attachment-Message-Id", extractMessageId(envelopeJson));

      conn.setDoOutput(true);
      conn.setFixedLengthStreamingMode(size);
      conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
      conn.setReadTimeout(READ_TIMEOUT_MS);

      // ★ Улучшение №3: стриминг файла → OutputStream с прогрессом.
      long written = 0;
      long lastReport = 0L;
      try (InputStream in = new BufferedInputStream(new FileInputStream(file), BUFFER_SIZE);
          OutputStream out = new BufferedOutputStream(conn.getOutputStream(), BUFFER_SIZE)) {
        byte[] buf = new byte[BUFFER_SIZE];
        int r;
        while ((r = in.read(buf)) != -1) {
          out.write(buf, 0, r);
          written += r;

          // Прогресс не чаще 10 раз в секунду
          if (progress != null) {
            long now = System.currentTimeMillis();
            if (now - lastReport >= PROGRESS_REPORT_INTERVAL_MS) {
              try {
                progress.onProgress(written, size);
              } catch (Exception pe) {
                log.warn("sendFile: progress callback error: " + pe.getMessage());
              }
              lastReport = now;
            }
          }
        }
        out.flush();
      }

      // Финальный прогресс — 100%
      if (progress != null) {
        try {
          progress.onProgress(size, size);
        } catch (Exception ignored) {
        }
      }

      if (written != size) {
        long elapsed = System.currentTimeMillis() - start;
        log.warn("sendFile: written=" + written + " != size=" + size);
        callback.onResult(
            SendResult.error("http-file", "written " + written + " != size " + size, elapsed));
        return;
      }

      int code = conn.getResponseCode();
      long elapsed = System.currentTimeMillis() - start;

      String respBody = "";
      try (InputStream in = (code >= 200 && code < 300) ? conn.getInputStream()
          : conn.getErrorStream()) {
        if (in != null) {
          byte[] buf = new byte[512];
          int r;
          StringBuilder sb = new StringBuilder();
          while ((r = in.read(buf)) != -1) {
            sb.append(new String(buf, 0, r, StandardCharsets.UTF_8));
            if (sb.length() > 512)
              break;
          }
          respBody = sb.toString();
        }
      } catch (Exception ignored) {
      }

      if (code == 200) {
        log.log("sendFile OK: " + targetNodeKey + " за " + elapsed + "мс, file=" + file.getName()
            + ", size=" + size + "B");
        callback.onResult(SendResult.ok("http-file", elapsed));
      } else if (code == 404) {
        log.warn(
            "sendFile 404: получатель не поддерживает /signal/file " + "(старая версия Mealse?)");
        callback
            .onResult(SendResult.errorHttp(404, "endpoint /signal/file not supported", elapsed));
      } else if (code == 413) {
        log.warn("sendFile 413: получатель отказал (слишком большой): " + truncate(respBody, 100));
        callback.onResult(
            SendResult.errorHttp(413, "payload too large: " + truncate(respBody, 100), elapsed));
      } else {
        log.warn("sendFile " + code + " для " + targetNodeKey + ": " + truncate(respBody, 100));
        callback.onResult(
            SendResult.errorHttp(code, "HTTP " + code + ": " + truncate(respBody, 100), elapsed));
      }

    } catch (SocketTimeoutException e) {
      long elapsed = System.currentTimeMillis() - start;
      log.warn("sendFile TIMEOUT: " + targetNodeKey + " за " + elapsed + "мс");
      callback.onResult(SendResult.error("http-file", "timeout (" + elapsed + "ms)", elapsed));
    } catch (ConnectException e) {
      long elapsed = System.currentTimeMillis() - start;
      String msg = e.getMessage() != null ? e.getMessage() : "connect refused";
      log.warn("sendFile ConnectException: " + targetNodeKey + " — " + msg);
      callback.onResult(SendResult.error("http-file", msg, elapsed));
    } catch (UnknownHostException e) {
      long elapsed = System.currentTimeMillis() - start;
      callback.onResult(SendResult.error("http-file", "unknown host: " + ip, elapsed));
    } catch (Exception e) {
      long elapsed = System.currentTimeMillis() - start;
      log.logError("sendFile failed: " + targetNodeKey, e);
      callback.onResult(SendResult.error("http-file",
          e.getMessage() != null ? e.getMessage() : "unknown error", elapsed));
    } finally {
      if (conn != null) {
        try {
          conn.disconnect();
        } catch (Throwable ignored) {
        }
      }
    }
  }

  // ============================================================
  // Хелперы
  // ============================================================

  private static String formatHost(String ip) {
    if (ip == null)
      return "";
    if (ip.contains(":") && !ip.startsWith("[")) {
      return "[" + ip + "]";
    }
    return ip;
  }

  private static String urlEncode(String s) {
    if (s == null)
      return "";
    try {
      return URLEncoder.encode(s, "UTF-8");
    } catch (Exception e) {
      return s;
    }
  }

  /** Быстрое извлечение message_id из envelope JSON (без полного парсинга). */
  private static String extractMessageId(String envelopeJson) {
    if (envelopeJson == null)
      return "";
    try {
      com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(envelopeJson)
          .getAsJsonObject();
      if (o.has("message_id") && !o.get("message_id").isJsonNull()) {
        return o.get("message_id").getAsString();
      }
    } catch (Exception ignored) {
    }
    return "";
  }

  private static String truncate(String s, int max) {
    if (s == null)
      return "null";
    return s.length() > max ? s.substring(0, max) + "..." : s;
  }
}