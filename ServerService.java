package com.my.mealse.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.my.mealse.filemanager.server.RemoteFsAuth;
import com.my.mealse.model.TrustedNode;
import com.my.mealse.util.CallStateManager;
import com.my.mealse.util.DeviceInfoHelper;
import com.my.mealse.util.FingerprintVerifier;
import com.my.mealse.util.IncomingCallNotifier;
import com.my.mealse.util.MealseLogger;
import com.my.mealse.util.NodeManager;
import com.my.mealse.util.SecureAuthManager;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTTP-сервер на порту 8081.
 *
 * Принимает: - сигналы (offer / answer / candidate / bye); - сообщения мессенджера (POST /signal/message) — JSON с base64 вложений; - ★ файлы мессенджера (POST /signal/file) — raw bytes (Шаг 16.8.5); - /ping — данные устройства; - /proxy/http — HTTP-прокси для браузера.
 *
 * ★ Шаг 16.5: MAX_BODY_SIZE для /signal/* (кроме /file) = 20 МБ. ★ Шаг 16.8.5: бинарный эндпоинт /signal/file: - envelope (base64) + attachment metadata — в заголовках X-Mealse-*; - body — raw bytes файла (Content-Type: application/octet-stream); - стриминг в getCacheDir()/incoming/<msgId>_<name> (не в heap!); - broadcast ACTION_SIGNAL с payload.localPath — MessageReceiver переместит tmp в AttachmentStorage. - очистка incoming/ при onCreate.
 *
 * ★ Прокси: - SSRF-защита: запрет file://, ftp://, gopher://, data:, javascript:, localhost, 127.0.0.1, ::1, приватных IPv4 (10/8, 172.16/12, 192.168/16, 169.254/16, 100.64/10) и IPv6 fc00::/7. - Авторизация через RemoteFsAuth (X-Mealse-Fingerprint / X-Mealse-DeviceId). - Лимиты: тело запроса и ответа ≤ 20 МБ, редиректы ≤ 5, таймауты 10/30 с. - Тело запроса/ответа — base64 в JSON.
 *
 * ★ A: Set-Cookie возвращаются клиенту отдельным массивом "set_cookie". ★ B: При follow_redirects=false шлюз НЕ следует редиректам. ★ G: лимит ответа 20 МБ синхронизирован с HttpProxyClient.MAX_PROXY_RESPONSE_SIZE. ★ H: логирование реальных размеров тела ответа до и после base64, флаг truncated.
 */
public class ServerService extends Service {

  private static final String TAG = "ServerService";

  // ===== Broadcast actions =====
  public static final String ACTION_SIGNAL = "com.my.mealse.SIGNAL";
  public static final String EXTRA_SIGNAL_JSON = "signal_json";

  // ===== Broadcast для UI =====
  public static final String ACTION_SERVER_STATE = "com.my.mealse.SERVER_STATE";
  public static final String EXTRA_STATE = "state";
  public static final String EXTRA_MESSAGE = "message";

  // ===== Константы сервера =====
  private static final int PORT = 8081;
  private static final int NOTIF_ID = 101;
  private static final String CHANNEL_ID = "MealseServerChannel";

  /**
   * Лимит тела для /signal/* (кроме /signal/file) и /ping. 20 МБ — base64 от вложения ≤ 10 МБ (≈ 13.3 МБ) + запас.
   */
  private static final int MAX_BODY_SIZE = 20 * 1024 * 1024;

  /**
   * ★ Шаг 16.8.5: лимит raw-файла для /signal/file. Синхронизирован с FileMessageSender.MAX_FILE_SIZE.
   */
  private static final long MAX_FILE_SIZE = 20L * 1024 * 1024;

  /** Лимит тела прокси-запроса. */
  private static final int MAX_PROXY_BODY_SIZE = 20 * 1024 * 1024;
  /** Лимит ответа прокси. */
  private static final int MAX_PROXY_RESPONSE_SIZE = 20 * 1024 * 1024;

  private static final int CLIENT_SO_TIMEOUT_MS = 60_000;
  private static final int SILENT_EMPTY_READLINE_EVERY = 100;

  // ★ Настройки прокси
  private static final int PROXY_CONNECT_TIMEOUT_MS = 10_000;
  private static final int PROXY_READ_TIMEOUT_MS = 45_000;
  private static final int PROXY_MAX_REDIRECTS = 5;
  private static final String PROXY_USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) "
      + "Chrome/120.0.0.0 Mobile Safari/537.36 Mealse/1.0";

  private static final String[] BLOCKED_REQUEST_HEADERS = { "host", "connection", "keep-alive",
      "proxy-connection", "transfer-encoding", "upgrade", "te", "trailer", "proxy-authorization",
      "proxy-authenticate", "content-length", "content-encoding" };

  private static final String[] BLOCKED_RESPONSE_HEADERS = { "connection", "keep-alive",
      "proxy-connection", "transfer-encoding", "upgrade", "te", "trailer", "content-length",
      "content-encoding" };

  // ===== Состояние =====
  private ServerSocket serverSocket;
  private ExecutorService threadPool;
  private volatile boolean isRunning = false;
  private final Gson gson = new Gson();
  private MealseLogger log;

  private final AtomicInteger emptyRequestLineCounter = new AtomicInteger(0);

  private volatile String cachedSelfFingerprint = null;
  private volatile String cachedSelfDeviceId = null;
  private volatile String cachedSelfIp = null;
  private volatile String cachedSelfIpV6 = null;
  private volatile boolean selfCacheInitialized = false;

  private RemoteFsAuth proxyAuth;

  /**
   * Исключение для слишком большого body (JSON /signal/*, /ping). Ловится в ClientHandler и превращается в 413.
   */
  private static class BodyTooLargeException extends IOException {
    BodyTooLargeException(String message) {
      super(message);
    }
  }

  // ============================================================
  // Lifecycle
  // ============================================================

  @Override
  public void onCreate() {
    super.onCreate();

    try {
      createNotificationChannel();
      startForeground(NOTIF_ID, buildNotification("Сервер запущен"));
    } catch (Exception e) {
      Log.e(TAG, "Ошибка startForeground", e);
    }

    try {
      log = MealseLogger.get(this, TAG);
      log.log("══ onCreate START ══");
      log.log("startForeground выполнен первым");
      log.log("MAX_BODY_SIZE=" + MAX_BODY_SIZE + " (" + (MAX_BODY_SIZE / (1024 * 1024)) + " МБ)");
      log.log("MAX_FILE_SIZE=" + MAX_FILE_SIZE + " (" + (MAX_FILE_SIZE / (1024 * 1024)) + " МБ)");
      log.log("══ onCreate END ══");
    } catch (Exception e) {
      Log.e(TAG, "Ошибка инициализации логгера", e);
    }

    // ★ Шаг 16.8.5: очистка tmp-файлов от предыдущих сессий
    cleanupIncomingDir();

    threadPool = Executors.newCachedThreadPool();
    ensureSelfCache();

    try {
      proxyAuth = new RemoteFsAuth(this);
      if (log != null)
        log.log("proxyAuth OK");
    } catch (Throwable t) {
      if (log != null)
        log.logError("proxyAuth FAILED", t);
      proxyAuth = null;
    }
  }

  /**
   * ★ Шаг 16.8.5: очищает getCacheDir()/incoming/ от «зависших» tmp-файлов. tmp создаётся handleFileRoute и удаляется MessageReceiver (moveFromFile). Если процесс упал между этими шагами — файл остаётся; удаляем при старте.
   */
  private void cleanupIncomingDir() {
    try {
      File incomingDir = new File(getCacheDir(), "incoming");
      if (!incomingDir.exists())
        return;
      File[] files = incomingDir.listFiles();
      if (files == null)
        return;
      int n = 0;
      for (File f : files) {
        if (f.isFile() && f.delete())
          n++;
      }
      if (log != null)
        log.log("cleanupIncomingDir: удалено " + n + " tmp-файлов");
    } catch (Exception e) {
      if (log != null)
        log.warn("cleanupIncomingDir: " + e.getMessage());
    }
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    log("══ onStartCommand START ══");

    try {
      startForeground(NOTIF_ID, buildNotification("Сервер запущен"));
      log("startForeground OK");
      broadcastState("running", "Сервер запущен на порту " + PORT);

      if (!isRunning) {
        isRunning = true;
        startServer(PORT);
      } else {
        log("Сервер уже запущен");
      }

      log("══ onStartCommand END ══");
      return START_STICKY;

    } catch (Exception e) {
      logError("Критическая ошибка в onStartCommand", e);
      broadcastState("error", "Ошибка запуска: " + e.getMessage());
      return START_STICKY;
    }
  }

  @Override
  public void onDestroy() {
    log("onDestroy START");
    isRunning = false;

    try {
      if (serverSocket != null && !serverSocket.isClosed()) {
        serverSocket.close();
        log("ServerSocket закрыт");
      }
    } catch (IOException e) {
      logError("Ошибка закрытия ServerSocket", e);
    }

    if (threadPool != null) {
      threadPool.shutdownNow();
      log("threadPool остановлен");
    }

    broadcastState("stopped", "Сервер остановлен");
    log("onDestroy END");
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  // ============================================================
  // Self-данные
  // ============================================================

  private void ensureSelfCache() {
    if (selfCacheInitialized)
      return;
    synchronized (this) {
      if (selfCacheInitialized)
        return;
      try {
        SecureAuthManager auth = new SecureAuthManager(this);
        cachedSelfFingerprint = auth.getDeviceFingerprint();
      } catch (Exception e) {
        logError("ensureSelfCache: не удалось получить self fingerprint", e);
        cachedSelfFingerprint = null;
      }
      try {
        cachedSelfDeviceId = DeviceInfoHelper.getDeviceId(this);
      } catch (Exception e) {
        logError("ensureSelfCache: не удалось получить self deviceId", e);
        cachedSelfDeviceId = null;
      }
      try {
        cachedSelfIp = DeviceInfoHelper.getSelfIp(this);
      } catch (Exception e) {
        logError("ensureSelfCache: не удалось получить self IP", e);
        cachedSelfIp = null;
      }
      try {
        cachedSelfIpV6 = DeviceInfoHelper.getIpv6Address(this);
      } catch (Exception e) {
        cachedSelfIpV6 = null;
      }
      selfCacheInitialized = true;
      log("ensureSelfCache: selfFp=" + cachedSelfFingerprint + ", selfDeviceId="
          + cachedSelfDeviceId + ", selfIp=" + cachedSelfIp);
    }
  }

  public void invalidateSelfCache() {
    synchronized (this) {
      selfCacheInitialized = false;
      cachedSelfFingerprint = null;
      cachedSelfDeviceId = null;
      cachedSelfIp = null;
      log("invalidateSelfCache: self-кэш сброшен");
    }
  }

  // ============================================================
  // HTTP-сервер
  // ============================================================

  private void startServer(int port) {
    log("startServer START, port=" + port);

    threadPool.submit(() -> {
      try {
        serverSocket = new ServerSocket(port);
        log("✓ HTTP-сервер слушает на порту " + port);
        broadcastState("running", "Сервер работает на порту " + port);

        while (isRunning) {
          try {
            Socket client = serverSocket.accept();
            String clientIp = client.getInetAddress().getHostAddress();
            log("📥 accept: клиент " + clientIp + ":" + client.getPort());
            threadPool.submit(new ClientHandler(client));
          } catch (IOException e) {
            if (isRunning) {
              logError("Ошибка accept", e);
            }
          }
        }

        log("Цикл accept завершён (isRunning=false)");

      } catch (IOException e) {
        if (isRunning) {
          logError("Не удалось запустить ServerSocket", e);
          broadcastState("error", "Не удалось запустить: " + e.getMessage());
        }
      }
    });
  }

  // ============================================================
  // Обработчик клиента
  // ============================================================

  private class ClientHandler implements Runnable {
    private final Socket client;

    ClientHandler(Socket client) {
      this.client = client;
    }

    @Override
    public void run() {
      String clientIp = "unknown";
      long startTime = System.currentTimeMillis();

      try {
        client.setSoTimeout(CLIENT_SO_TIMEOUT_MS);
        clientIp = client.getInetAddress().getHostAddress();

        log("══ ClientHandler START для " + clientIp + " ══");

        BufferedInputStream rawIn = new BufferedInputStream(client.getInputStream());
        BufferedWriter out = new BufferedWriter(
            new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8));

        // ===== 1. Request line =====
        String requestLine = readLine(rawIn);
        if (requestLine == null || requestLine.isEmpty()) {
          int n = emptyRequestLineCounter.incrementAndGet();
          if (n == 1 || n % SILENT_EMPTY_READLINE_EVERY == 0) {
            log("Пустой request line от " + clientIp + " (TCP-check, всего таких: " + n
                + ") — завершаем");
          }
          return;
        }

        log("Request: " + requestLine);

        String[] parts = requestLine.split(" ");
        if (parts.length < 3) {
          log("Некорректный request line");
          sendJson(out, 400, errorJson("Некорректный request line"));
          return;
        }
        String method = parts[0];
        String path = parts[1];

        // ===== 2. Headers =====
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = readLine(rawIn)) != null) {
          if (line.isEmpty())
            break;
          int idx = line.indexOf(':');
          if (idx > 0) {
            headers.put(line.substring(0, idx).trim().toLowerCase(Locale.ROOT),
                line.substring(idx + 1).trim());
          }
        }
        log("Headers: " + headers);

        // ===== ★ 2.5 Шаг 16.8.5: бинарный /signal/file — body читается в tmp =====
        // Выделено ОТДЕЛЬНО, чтобы не читать 10 МБ raw в byte[] / String.
        if ("POST".equals(method) && "/signal/file".equals(path)) {
          handleFileRoute(rawIn, headers, out, clientIp);
          long elapsed = System.currentTimeMillis() - startTime;
          log("══ ClientHandler END (file) для " + clientIp + " за " + elapsed + " мс ══");
          return;
        }

        // ===== 3. Body (JSON) =====
        boolean isProxy = path != null && path.startsWith("/proxy/");
        int maxBody = isProxy ? MAX_PROXY_BODY_SIZE : MAX_BODY_SIZE;

        byte[] bodyBytes;
        try {
          bodyBytes = readBodyBytes(rawIn, headers, maxBody);
        } catch (BodyTooLargeException e) {
          log.warn("Body too large от " + clientIp + ": " + e.getMessage() + " (max=" + maxBody
              + "B, path=" + path + ")");
          sendJson(out, 413, errorJson("payload too large: " + e.getMessage()));
          return;
        }
        String body = bodyBytes != null ? new String(bodyBytes, StandardCharsets.UTF_8) : "";

        // ===== 4. Маршрутизация =====
        if (isProxy) {
          handleProxyRoute(method, path, body, headers, out, clientIp);
        } else {
          handleRoute(method, path, body, out, clientIp);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log("══ ClientHandler END для " + clientIp + " за " + elapsed + " мс ══");

      } catch (java.net.SocketTimeoutException e) {
        logError("Timeout при чтении от " + clientIp, e);
      } catch (IOException e) {
        logError("IOException в ClientHandler (" + clientIp + ")", e);
      } catch (Exception e) {
        logError("Неожиданная ошибка в ClientHandler (" + clientIp + ")", e);
      } finally {
        try {
          client.close();
        } catch (IOException ignored) {
        }
      }
    }
  }

  // ============================================================
  // ★ Шаг 16.8.5: бинарный эндпоинт /signal/file
  // ============================================================

  /**
   * POST /signal/file — raw bytes + метаданные в заголовках.
   *
   * Заголовки: X-Mealse-Envelope — base64(JSON envelope без payload) X-Mealse-Attachment-Name — url-encoded имя файла X-Mealse-Attachment-Mime — MIME X-Mealse-Attachment-Message-Id — message_id Content-Length — размер файла Content-Type — application/octet-stream
   *
   * Тело: raw bytes.
   *
   * Шаги: 1. Декодируем envelope из base64. 2. Self-loop проверка (from_fingerprint / from_device_id). 3. Content-Length ≤ MAX_FILE_SIZE. 4. Стримим body в getCacheDir()/incoming/<msgId>_<name> (без загрузки в heap). 5. broadcast ACTION_SIGNAL с envelope + payload {kind:FILE, localPath:tmp, ...}. 6. 200 OK.
   */
  /**
   * POST /signal/file — raw bytes + метаданные в заголовках.
   *
   * Заголовки: X-Mealse-Envelope — base64(JSON envelope без payload) X-Mealse-Attachment-Name — url-encoded имя файла X-Mealse-Attachment-Mime — MIME X-Mealse-Attachment-Message-Id — message_id Content-Length — размер файла Content-Type — application/octet-stream
   *
   * Тело: raw bytes.
   *
   * ★ ИСПРАВЛЕНО (шаг 17.1): broadcast пропускается, если broadcastJson > 512 КБ (Binder ~1 МБ, но подстраховываемся). Прямой вызов MessageReceiver — всегда (это локальный Intent, без Binder-лимита).
   */
  /**
   * POST /signal/file — raw bytes + метаданные в заголовках.
   *
   * Заголовки: X-Mealse-Envelope — base64(JSON envelope без payload) X-Mealse-Attachment-Name — url-encoded имя файла X-Mealse-Attachment-Mime — MIME X-Mealse-Attachment-Message-Id — message_id Content-Length — размер файла Content-Type — application/octet-stream
   *
   * Тело: raw bytes.
   *
   * ★ ИСПРАВЛЕНО (шаг 17.2): broadcast для /signal/file УБРАН. Причина: MessageReceiver.onReceive вызывался дважды (broadcast + direct), второй вызов attachFileIfPresent → moveFromFile получал несуществующий tmp (уже перемещён первым вызовом) → warning «localPath не существует». Теперь только прямой вызов — он не ограничен Binder-лимитом (локальный Intent) и идемпотентен по msgRepo.exists().
   */
  private void handleFileRoute(BufferedInputStream rawIn, Map<String, String> headers,
      BufferedWriter out, String clientIp) throws IOException {
    log.log("══ handleFileRoute START ══");
    log.log("  clientIp=" + clientIp);

    // ===== 1. Envelope из заголовка =====
    String envB64 = headers.get("x-mealse-envelope");
    if (envB64 == null || envB64.isEmpty()) {
      log.warn("handleFileRoute: нет X-Mealse-Envelope");
      sendJson(out, 400, errorJson("X-Mealse-Envelope required"));
      return;
    }
    String envelopeJson;
    try {
      byte[] envBytes = android.util.Base64.decode(envB64, android.util.Base64.NO_WRAP);
      envelopeJson = new String(envBytes, StandardCharsets.UTF_8);
    } catch (Exception e) {
      log.warn("handleFileRoute: X-Mealse-Envelope не base64");
      sendJson(out, 400, errorJson("X-Mealse-Envelope base64 invalid"));
      return;
    }

    // ===== 2. Метаданные =====
    String attachName = urlDecode(headers.get("x-mealse-attachment-name"));
    String attachMime = headers.get("x-mealse-attachment-mime");
    String msgId = headers.get("x-mealse-attachment-message-id");
    if (attachName == null || attachName.isEmpty())
      attachName = "file.bin";
    if (attachMime == null || attachMime.isEmpty())
      attachMime = "application/octet-stream";

    // ===== 3. Content-Length =====
    String clStr = headers.get("content-length");
    if (clStr == null) {
      log.warn("handleFileRoute: нет Content-Length");
      sendJson(out, 400, errorJson("Content-Length required"));
      return;
    }
    long length;
    try {
      length = Long.parseLong(clStr);
    } catch (NumberFormatException e) {
      log.warn("handleFileRoute: bad Content-Length");
      sendJson(out, 400, errorJson("bad Content-Length"));
      return;
    }
    if (length <= 0 || length > MAX_FILE_SIZE) {
      log.warn("handleFileRoute: size " + length + " вне [1, " + MAX_FILE_SIZE + "]");
      drainInputStream(rawIn, length);
      sendJson(out, 413, errorJson("file size " + length + " out of [1, " + MAX_FILE_SIZE + "]"));
      return;
    }

    // ===== 4. Парсим envelope =====
    JsonObject env;
    try {
      env = gson.fromJson(envelopeJson, JsonObject.class);
    } catch (Exception e) {
      log.warn("handleFileRoute: envelope JSON invalid");
      drainInputStream(rawIn, length);
      sendJson(out, 400, errorJson("envelope JSON invalid"));
      return;
    }
    if (env == null) {
      drainInputStream(rawIn, length);
      sendJson(out, 400, errorJson("envelope null"));
      return;
    }

    String fromFp = optString(env, "from_fingerprint");
    String fromDeviceId = optString(env, "from_device_id");
    String envelopeMsgId = optString(env, "message_id");

    if (msgId == null || msgId.isEmpty()) {
      msgId = envelopeMsgId;
    }
    if (msgId == null || msgId.isEmpty()) {
      msgId = "msg_" + System.currentTimeMillis();
    }

    // ===== 5. Self-loop =====
    ensureSelfCache();
    if (fromFp != null && cachedSelfFingerprint != null && cachedSelfFingerprint.equals(fromFp)) {
      log.warn("handleFileRoute: ❌ SELF-LOOP по fingerprint — отклоняем");
      drainInputStream(rawIn, length);
      sendJson(out, 200, "{\"status\":\"ignored\",\"reason\":\"self_loop\"}");
      return;
    }
    if (fromDeviceId != null && cachedSelfDeviceId != null
        && cachedSelfDeviceId.equals(fromDeviceId)) {
      log.warn("handleFileRoute: ❌ SELF-LOOP по deviceId — отклоняем");
      drainInputStream(rawIn, length);
      sendJson(out, 200, "{\"status\":\"ignored\",\"reason\":\"self_loop\"}");
      return;
    }

    // ===== 6. Пишем body в tmp =====
    File incomingDir = new File(getCacheDir(), "incoming");
    if (!incomingDir.exists()) {
      // noinspection ResultOfMethodCallIgnored
      incomingDir.mkdirs();
    }
    String safeMsgId = sanitizeFileName(msgId);
    String safeName = sanitizeFileName(attachName);
    File tmpFile = new File(incomingDir, safeMsgId + "_" + safeName);

    log.log("handleFileRoute: пишу в tmp " + tmpFile.getAbsolutePath() + " (" + length + "B)");

    long written = readBodyToFile(rawIn, length, tmpFile);
    if (written != length) {
      log.warn("handleFileRoute: written=" + written + " != length=" + length);
      // noinspection ResultOfMethodCallIgnored
      tmpFile.delete();
      sendJson(out, 400, errorJson("incomplete body"));
      return;
    }

    // ===== 7. Обогащаем envelope payload =====
    String directJson;
    try {
      JsonObject payload = new JsonObject();
      payload.addProperty("kind", "FILE");
      payload.addProperty("messageId", msgId);
      payload.addProperty("sentAt",
          env.has("timestamp") && !env.get("timestamp").isJsonNull()
              ? env.get("timestamp").getAsLong()
              : System.currentTimeMillis() / 1000L);
      payload.addProperty("body", attachName);
      payload.addProperty("attachmentName", attachName);
      payload.addProperty("attachmentMime", attachMime);
      payload.addProperty("attachmentSize", length);
      payload.addProperty("localPath", tmpFile.getAbsolutePath());
      env.add("payload", payload);

      directJson = gson.toJson(env);
    } catch (Exception e) {
      logError("handleFileRoute: build payload", e);
      // noinspection ResultOfMethodCallIgnored
      tmpFile.delete();
      sendJson(out, 500, errorJson("internal: " + e.getMessage()));
      return;
    }

    // ===== 8. ★ ТОЛЬКО прямой вызов MessageReceiver (broadcast убран) =====
    // Локальный Intent — не Binder, размер не ограничен.
    // Идемпотентен: msgRepo.exists() отсечёт дубликат.
    try {
      log.log("handleFileRoute: → прямой вызов MessageReceiver");
      Intent directIntent = new Intent(ACTION_SIGNAL);
      directIntent.putExtra(EXTRA_SIGNAL_JSON, directJson);
      new com.my.mealse.messenger.receiver.MessageReceiver().onReceive(this, directIntent);
      log.log("✓ handleFileRoute: MessageReceiver.onReceive выполнен");
    } catch (Exception e) {
      logError("handleFileRoute: прямой вызов MessageReceiver", e);
    }

    sendJson(out, 200, "{\"status\":\"ok\",\"type\":\"file\"}");
    log.log("══ handleFileRoute END: 200 ══");
  }

  // ============================================================
  // Маршрутизация — обычные сигналы
  // ============================================================

  private void handleRoute(String method, String path, String body, BufferedWriter out,
      String clientIp) throws IOException {
    log("handleRoute: " + method + " " + path + " от " + clientIp);

    if ("GET".equals(method) && "/ping".equals(path)) {
      JsonObject resp = new JsonObject();
      resp.addProperty("status", "ok");
      resp.addProperty("device_id", DeviceInfoHelper.getDeviceId(this));
      resp.addProperty("fingerprint", cachedSelfFingerprint != null ? cachedSelfFingerprint : "");
      resp.addProperty("ip", cachedSelfIp != null ? cachedSelfIp : "");
      resp.addProperty("port", PORT);
      resp.addProperty("mode", "AUTO");

      try {
        String ipv6 = DeviceInfoHelper.getIpv6Address(this);
        if (ipv6 != null && !ipv6.isEmpty()) {
          resp.addProperty("ipv6", ipv6);
        }
      } catch (Exception e) {
        log.warn("getIpv6Address: " + e.getMessage());
      }
      try {
        String netType = DeviceInfoHelper.getConnectionType(this);
        if (netType != null && !netType.isEmpty()) {
          resp.addProperty("network_type", netType);
        }
      } catch (Exception ignored) {
      }

      sendJson(out, 200, resp.toString());
      log("✓ Ответ /ping: " + resp);
      return;
    }

    if ("POST".equals(method) && path.startsWith("/signal/")) {
      handleSignal(path, body, out, clientIp);
      return;
    }

    log("Маршрут не найден: " + method + " " + path);
    sendJson(out, 404, errorJson("Маршрут не найден: " + method + " " + path));
  }

  // ============================================================
  // Маршрутизация — прокси
  // ============================================================

  private void handleProxyRoute(String method, String path, String body,
      Map<String, String> headers, BufferedWriter out, String clientIp) throws IOException {

    log("handleProxyRoute: " + method + " " + path + " от " + clientIp);

    if (!"POST".equals(method)) {
      sendJson(out, 405, errorJson("Только POST для /proxy/*"));
      return;
    }

    if (!"/proxy/http".equals(path)) {
      sendJson(out, 404, errorJson("Неизвестный прокси-маршрут: " + path));
      return;
    }

    if (proxyAuth != null) {
      String fp = headers.get("x-mealse-fingerprint");
      String did = headers.get("x-mealse-deviceid");
      RemoteFsAuth.AuthResult ar = proxyAuth.authorize(fp, did, clientIp);
      if (!ar.allowed) {
        log.warn("proxy: auth denied: " + ar.reason);
        sendJson(out, 403, errorJson("access denied: " + ar.reason));
        return;
      }
      log.log("proxy: auth OK (" + (ar.node != null ? ar.node.getName() : "?") + ")");
    }

    handleProxyHttp(body, out, clientIp);
  }

  // ============================================================
  // Прокси-обработчик
  // ============================================================

  private void handleProxyHttp(String body, BufferedWriter out, String clientIp)
      throws IOException {
    long reqStart = System.currentTimeMillis();

    JsonObject req;
    try {
      req = gson.fromJson(body, JsonObject.class);
    } catch (Exception e) {
      sendJson(out, 400, errorJson("proxy: некорректный JSON: " + e.getMessage()));
      return;
    }
    if (req == null) {
      sendJson(out, 400, errorJson("proxy: пустой запрос"));
      return;
    }

    String method = req.has("method") && !req.get("method").isJsonNull()
        ? req.get("method").getAsString().toUpperCase(Locale.ROOT)
        : "GET";
    String urlStr = req.has("url") && !req.get("url").isJsonNull() ? req.get("url").getAsString()
        : null;
    if (urlStr == null || urlStr.isEmpty()) {
      sendJson(out, 400, errorJson("proxy: url обязателен"));
      return;
    }

    String blockReason = checkSsrf(urlStr);
    if (blockReason != null) {
      log.warn("proxy: SSRF blocked: " + blockReason + " (" + urlStr + ")");
      sendJson(out, 403, errorJson("proxy: URL запрещён: " + blockReason));
      return;
    }

    Map<String, String> reqHeaders = new HashMap<>();
    if (req.has("headers") && req.get("headers").isJsonObject()) {
      JsonObject h = req.getAsJsonObject("headers");
      for (String key : h.keySet()) {
        if (h.get(key).isJsonNull())
          continue;
        String lk = key.toLowerCase(Locale.ROOT);
        if (isBlockedRequestHeader(lk))
          continue;
        reqHeaders.put(key, h.get(key).getAsString());
      }
    }

    byte[] reqBodyBytes = null;
    if (req.has("body_base64") && !req.get("body_base64").isJsonNull()) {
      String b64 = req.get("body_base64").getAsString();
      if (b64 != null && !b64.isEmpty()) {
        try {
          reqBodyBytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP);
        } catch (Exception e) {
          sendJson(out, 400, errorJson("proxy: body_base64 не декодируется"));
          return;
        }
        if (reqBodyBytes.length > MAX_PROXY_BODY_SIZE) {
          sendJson(out, 413, errorJson("proxy: тело запроса слишком велико"));
          return;
        }
      }
    }

    boolean followRedirects = req.has("follow_redirects")
        && !req.get("follow_redirects").isJsonNull() && req.get("follow_redirects").getAsBoolean();

    log.log("proxy: " + method + " " + redactUrl(urlStr) + " (body="
        + (reqBodyBytes != null ? reqBodyBytes.length + "B" : "0") + ", follow_redirects="
        + followRedirects + ")");

    HttpURLConnection conn = null;
    try {
      URL url = new URL(urlStr);
      conn = (HttpURLConnection) url.openConnection();
      conn.setConnectTimeout(PROXY_CONNECT_TIMEOUT_MS);
      conn.setReadTimeout(PROXY_READ_TIMEOUT_MS);
      conn.setRequestMethod(method);
      conn.setUseCaches(false);
      conn.setInstanceFollowRedirects(false);

      for (Map.Entry<String, String> e : reqHeaders.entrySet()) {
        conn.setRequestProperty(e.getKey(), e.getValue());
      }
      if (!reqHeaders.containsKey("User-Agent") && !reqHeaders.containsKey("user-agent")) {
        conn.setRequestProperty("User-Agent", PROXY_USER_AGENT);
      }
      if (!reqHeaders.containsKey("Accept-Language")
          && !reqHeaders.containsKey("accept-language")) {
        conn.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8");
      }

      if (reqBodyBytes != null && reqBodyBytes.length > 0) {
        conn.setDoOutput(true);
        conn.setFixedLengthStreamingMode(reqBodyBytes.length);
        try (OutputStream os = conn.getOutputStream()) {
          os.write(reqBodyBytes);
          os.flush();
        }
      }

      int status = conn.getResponseCode();
      String finalUrl = conn.getURL().toString();
      int redirects = 0;

      while (followRedirects
          && (status == 301 || status == 302 || status == 303 || status == 307 || status == 308)
          && redirects < PROXY_MAX_REDIRECTS) {

        String location = conn.getHeaderField("Location");
        if (location == null || location.isEmpty())
          break;

        String resolved = resolveRedirectUrl(finalUrl, location);
        if (resolved == null)
          break;

        String redirectBlock = checkSsrf(resolved);
        if (redirectBlock != null) {
          log.warn("proxy: redirect SSRF blocked: " + redirectBlock + " → " + resolved);
          break;
        }

        log.log("proxy: redirect [" + status + "] → " + redactUrl(resolved));
        conn.disconnect();

        URL nextUrl = new URL(resolved);
        conn = (HttpURLConnection) nextUrl.openConnection();
        conn.setConnectTimeout(PROXY_CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(PROXY_READ_TIMEOUT_MS);
        conn.setUseCaches(false);
        conn.setInstanceFollowRedirects(false);

        if (status == 303) {
          conn.setRequestMethod("GET");
        } else {
          conn.setRequestMethod(method);
        }

        for (Map.Entry<String, String> e : reqHeaders.entrySet()) {
          conn.setRequestProperty(e.getKey(), e.getValue());
        }

        finalUrl = resolved;
        status = conn.getResponseCode();
        redirects++;
      }

      byte[] respBytes;
      try {
        InputStream respStream = (status >= 200 && status < 400) ? conn.getInputStream()
            : conn.getErrorStream();
        if (respStream == null) {
          respBytes = new byte[0];
        } else {
          respBytes = readAllLimited(respStream, MAX_PROXY_RESPONSE_SIZE);
        }
      } catch (IOException e) {
        respBytes = new byte[0];
      }

      boolean truncated = respBytes.length >= MAX_PROXY_RESPONSE_SIZE;

      JsonObject respHeaders = new JsonObject();
      Map<String, List<String>> headerFields = conn.getHeaderFields();
      if (headerFields != null) {
        for (Map.Entry<String, List<String>> e : headerFields.entrySet()) {
          String key = e.getKey();
          if (key == null)
            continue;
          String lk = key.toLowerCase(Locale.ROOT);
          if (isBlockedResponseHeader(lk))
            continue;
          List<String> values = e.getValue();
          if (values == null || values.isEmpty())
            continue;
          respHeaders.addProperty(key, values.get(0));
        }
      }

      JsonArray setCookieArr = new JsonArray();
      if (headerFields != null) {
        for (Map.Entry<String, List<String>> e : headerFields.entrySet()) {
          String key = e.getKey();
          if (key == null)
            continue;
          String lk = key.toLowerCase(Locale.ROOT);
          if (!"set-cookie".equals(lk))
            continue;
          List<String> values = e.getValue();
          if (values == null)
            continue;
          for (String v : values) {
            if (v != null && !v.isEmpty()) {
              setCookieArr.add(v);
            }
          }
        }
      }

      String bodyBase64 = android.util.Base64.encodeToString(respBytes,
          android.util.Base64.NO_WRAP);

      JsonObject result = new JsonObject();
      result.addProperty("status", status);
      result.add("headers", respHeaders);
      result.addProperty("body_base64", bodyBase64);
      result.addProperty("final_url", finalUrl);
      result.addProperty("truncated", truncated);
      if (setCookieArr.size() > 0) {
        result.add("set_cookie", setCookieArr);
      }

      long elapsed = System.currentTimeMillis() - reqStart;
      log.log("proxy: ← " + status + " (raw=" + respBytes.length + "B, b64=" + bodyBase64.length()
          + " chars, " + redirects + " redirect, " + elapsed + "ms, cookies=" + setCookieArr.size()
          + ", truncated=" + truncated + ")");

      sendJson(out, 200, result.toString());

    } catch (java.net.SocketTimeoutException e) {
      long elapsed = System.currentTimeMillis() - reqStart;
      log.warn("proxy: TIMEOUT после " + elapsed + "ms: " + redactUrl(urlStr));
      sendJson(out, 504, errorJson("proxy: таймаут (" + elapsed + "мс)"));
    } catch (java.net.UnknownHostException e) {
      log.warn("proxy: UnknownHost: " + redactUrl(urlStr));
      sendJson(out, 502, errorJson("proxy: неизвестный хост"));
    } catch (java.net.ConnectException e) {
      log.warn("proxy: ConnectException: " + redactUrl(urlStr) + " — " + e.getMessage());
      sendJson(out, 502, errorJson("proxy: соединение отклонено"));
    } catch (Exception e) {
      logError("proxy: критическая ошибка для " + redactUrl(urlStr), e);
      sendJson(out, 500, errorJson("proxy: " + e.getMessage()));
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
  // SSRF-защита
  // ============================================================

  private String checkSsrf(String urlStr) {
    if (urlStr == null || urlStr.isEmpty())
      return "пустой URL";

    String lower = urlStr.toLowerCase(Locale.ROOT);
    if (lower.startsWith("file:"))
      return "file:// запрещён";
    if (lower.startsWith("ftp:"))
      return "ftp:// запрещён";
    if (lower.startsWith("gopher:"))
      return "gopher:// запрещён";
    if (lower.startsWith("data:"))
      return "data: запрещён";
    if (lower.startsWith("javascript:"))
      return "javascript: запрещён";
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
      return "разрешены только http/https";
    }

    String host;
    try {
      URL u = new URL(urlStr);
      host = u.getHost();
    } catch (Exception e) {
      return "некорректный URL";
    }
    if (host == null || host.isEmpty())
      return "пустой host";

    String h = host.toLowerCase(Locale.ROOT);
    if (h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1")
        || h.endsWith(".localhost")) {
      return "localhost запрещён";
    }

    try {
      InetAddress addr = InetAddress.getByName(host);
      if (isPrivateAddress(addr)) {
        return "приватный адрес: " + addr.getHostAddress();
      }
    } catch (Exception e) {
    }

    return null;
  }

  private static boolean isPrivateAddress(InetAddress addr) {
    if (addr.isLoopbackAddress())
      return true;
    if (addr.isLinkLocalAddress())
      return true;
    if (addr.isSiteLocalAddress())
      return true;
    if (addr.isAnyLocalAddress())
      return true;
    if (addr.isMulticastAddress())
      return true;

    byte[] b = addr.getAddress();
    if (b.length == 4) {
      int b0 = b[0] & 0xFF;
      int b1 = b[1] & 0xFF;
      if (b0 == 0)
        return true;
      if (b0 == 127)
        return true;
      if (b0 == 10)
        return true;
      if (b0 == 172 && b1 >= 16 && b1 <= 31)
        return true;
      if (b0 == 192 && b1 == 168)
        return true;
      if (b0 == 169 && b1 == 254)
        return true;
      if (b0 == 100 && b1 >= 64 && b1 <= 127)
        return true;
    } else if (b.length == 16) {
      int b0 = b[0] & 0xFF;
      if ((b0 & 0xFE) == 0xFC)
        return true;
    }
    return false;
  }

  private String resolveRedirectUrl(String baseUrl, String location) {
    try {
      URL base = new URL(baseUrl);
      URL resolved = new URL(base, location);
      return resolved.toString();
    } catch (Exception e) {
      return null;
    }
  }

  private boolean isBlockedRequestHeader(String lowerKey) {
    for (String h : BLOCKED_REQUEST_HEADERS) {
      if (h.equals(lowerKey))
        return true;
    }
    return false;
  }

  private boolean isBlockedResponseHeader(String lowerKey) {
    for (String h : BLOCKED_RESPONSE_HEADERS) {
      if (h.equals(lowerKey))
        return true;
    }
    return false;
  }

  // ============================================================
  // Обработка сигналов (offer / answer / candidate / bye / message)
  // ============================================================

  private void handleSignal(String path, String body, BufferedWriter out, String clientIp)
      throws IOException {

    log.log("══ handleSignal START ══");
    log.log("  path=" + path);
    log.log("  clientIp=" + clientIp);
    log.log("  body.length=" + (body != null ? body.length() : -1));

    String type;
    switch (path) {
      case "/signal/offer":
        type = "offer";
        break;
      case "/signal/answer":
        type = "answer";
        break;
      case "/signal/candidate":
        type = "candidate";
        break;
      case "/signal/bye":
        type = "bye";
        break;
      case "/signal/message":
        type = "message";
        break;
      default:
        log.warn("Неизвестный путь: " + path);
        sendJson(out, 404, errorJson("Неизвестный тип сигнала"));
        return;
    }

    if (body == null || body.isEmpty()) {
      log.warn("Пустое тело запроса");
      sendJson(out, 400, errorJson("Пустое тело запроса"));
      return;
    }

    JsonObject obj;
    try {
      obj = gson.fromJson(body, JsonObject.class);
      if (obj == null || !obj.has("type")) {
        log.warn("Отсутствует поле type");
        sendJson(out, 400, errorJson("Отсутствует поле type"));
        return;
      }
      String jsonType = obj.get("type").getAsString();
      if (!type.equals(jsonType)) {
        log.warn("type не совпадает: " + jsonType + " != " + type);
        sendJson(out, 400, errorJson("type не совпадает с маршрутом"));
        return;
      }
    } catch (Exception e) {
      log.logError("Некорректный JSON", e);
      sendJson(out, 400, errorJson("Некорректный JSON: " + e.getMessage()));
      return;
    }

    String callId = obj.has("call_id") && !obj.get("call_id").isJsonNull()
        ? obj.get("call_id").getAsString()
        : null;
    String fromDeviceId = obj.has("from_device_id") && !obj.get("from_device_id").isJsonNull()
        ? obj.get("from_device_id").getAsString()
        : null;
    String fromFingerprint = obj.has("from_fingerprint")
        && !obj.get("from_fingerprint").isJsonNull() ? obj.get("from_fingerprint").getAsString()
            : null;

    log.log("handleSignal: call_id=" + shortId(callId) + ", from_device_id=" + fromDeviceId
        + ", from_fingerprint=" + fromFingerprint);

    // Self-loop
    ensureSelfCache();
    if (fromFingerprint != null && cachedSelfFingerprint != null
        && cachedSelfFingerprint.equals(fromFingerprint)) {
      log.warn("❌ SELF-LOOP: from_fingerprint == self");
      JsonObject resp = new JsonObject();
      resp.addProperty("status", "ignored");
      resp.addProperty("reason", "self_loop");
      resp.addProperty("type", type);
      sendJson(out, 200, resp.toString());
      return;
    }
    if (fromDeviceId != null && cachedSelfDeviceId != null
        && cachedSelfDeviceId.equals(fromDeviceId)) {
      log.warn("❌ SELF-LOOP: from_device_id == self");
      JsonObject resp = new JsonObject();
      resp.addProperty("status", "ignored");
      resp.addProperty("reason", "self_loop");
      resp.addProperty("type", type);
      sendJson(out, 200, resp.toString());
      return;
    }

    try {
      enrichFromSignal(clientIp, fromDeviceId, fromFingerprint);
    } catch (Exception e) {
      log.logError("Ошибка enrichFromSignal", e);
    }

    if ("offer".equals(type)) {
      try {
        handleIncomingOffer(obj, body, clientIp);
      } catch (Exception e) {
        log.logError("Ошибка handleIncomingOffer", e);
      }
    } else if ("message".equals(type)) {
      // ★ НОВОЕ: прямой вызов MessageReceiver вместо broadcast
      try {
        log.log("handleSignal: message → прямой вызов MessageReceiver");
        Intent intent = new Intent(ACTION_SIGNAL);
        intent.putExtra(EXTRA_SIGNAL_JSON, body);
        new com.my.mealse.messenger.receiver.MessageReceiver().onReceive(this, intent);
        log.log("✓ handleSignal: MessageReceiver.onReceive выполнен");
      } catch (Exception e) {
        log.logError("Ошибка прямого вызова MessageReceiver", e);
      }
    } else {
      try {
        broadcastSignal(body, type);
      } catch (Exception e) {
        log.logError("Ошибка broadcastSignal", e);
      }
    }

    JsonObject resp = new JsonObject();
    resp.addProperty("status", "ok");
    resp.addProperty("type", type);
    sendJson(out, 200, resp.toString());
    log.log("✓ handleSignal END: 200, type=" + type);
  }

  private void enrichFromSignal(String clientIp, String fromDeviceId, String fromFingerprint) {
    if (clientIp == null || "unknown".equals(clientIp))
      return;

    ensureSelfCache();
    if (cachedSelfIp != null && cachedSelfIp.equals(clientIp)) {
      log.log("enrichFromSignal: clientIp == self IP (" + clientIp + ") — пропускаем");
      return;
    }

    NodeManager nodeManager = new NodeManager(this);
    TrustedNode existing = nodeManager.findByIpPort(clientIp, PORT);
    if (existing == null) {
      log.log("enrichFromSignal: узел с (ip=" + clientIp + ", port=" + PORT
          + ") не найден в списке — только lastSeen не обновляем");
      return;
    }

    String oldDeviceId = existing.getDeviceId();
    String oldFp = existing.getFingerprint();

    boolean hasNewDeviceId = fromDeviceId != null && !fromDeviceId.isEmpty()
        && !fromDeviceId.equals(oldDeviceId);
    boolean hasNewFp = fromFingerprint != null && !fromFingerprint.isEmpty()
        && !fromFingerprint.equals(oldFp);

    boolean fingerprintConflict = false;
    if (hasNewFp && oldFp != null && !oldFp.isEmpty()) {
      fingerprintConflict = true;
      log.warn("enrichFromSignal: FINGERPRINT ИЗМЕНИЛСЯ у key=" + existing.getKey() + " старый="
          + oldFp + " новый=" + fromFingerprint + " — НЕ перезаписываю");
    }

    if (hasNewDeviceId) {
      log.log("enrichFromSignal: deviceId " + oldDeviceId + " → " + fromDeviceId + " (key="
          + existing.getKey() + ")");
      existing.setDeviceId(fromDeviceId);
    }
    if (hasNewFp && !fingerprintConflict) {
      log.log("enrichFromSignal: fingerprint " + oldFp + " → " + fromFingerprint + " (key="
          + existing.getKey() + ")");
      existing.setFingerprint(fromFingerprint);
    }

    existing.touchLastSeen(existing.getLastSeenNetwork());
    nodeManager.saveNode(existing);

    log.log("enrichFromSignal: узел обновлён key=" + existing.getKey() + ", name="
        + existing.getName() + (hasNewDeviceId ? ", +deviceId" : "")
        + (hasNewFp && !fingerprintConflict ? ", +fingerprint" : "")
        + (fingerprintConflict ? ", ⚠ fingerprint conflict" : ""));
  }

  private void handleIncomingOffer(JsonObject obj, String rawJson, String clientIp) {
    log("handleIncomingOffer START");

    String callId = obj.has("call_id") && !obj.get("call_id").isJsonNull()
        ? obj.get("call_id").getAsString()
        : null;
    String fromDeviceId = obj.has("from_device_id") && !obj.get("from_device_id").isJsonNull()
        ? obj.get("from_device_id").getAsString()
        : null;
    String fromFingerprint = obj.has("from_fingerprint")
        && !obj.get("from_fingerprint").isJsonNull() ? obj.get("from_fingerprint").getAsString()
            : null;

    String modeFromSignal = obj.has("mode") && !obj.get("mode").isJsonNull()
        ? obj.get("mode").getAsString()
        : null;

    log("callId=" + shortId(callId));
    log("fromDeviceId=" + fromDeviceId);
    log("fromFingerprint=" + fromFingerprint);
    log("modeFromSignal=" + modeFromSignal);
    log("clientIp=" + clientIp);

    CallStateManager callState = new CallStateManager(this);
    boolean isCallActive = callState.isCallActive();
    String activeCallId = callState.getActiveCallId();

    log("Активный звонок: isCallActive=" + isCallActive + ", activeCallId="
        + shortId(activeCallId));

    if (isCallActive) {
      if (callId != null && callId.equals(activeCallId)) {
        log("Offer для АКТИВНОГО звонка — пробрасываем");
        broadcastSignal(rawJson, "offer");
      } else {
        log("❌ ЗАНЯТО: активен " + shortId(activeCallId) + ", входящий " + shortId(callId));
        try {
          sendBusyBye(fromFingerprint, clientIp, callId);
        } catch (Exception e) {
          logError("Ошибка отправки busy-bye", e);
        }
      }
      return;
    }

    FingerprintVerifier verifier = new FingerprintVerifier(this);
    FingerprintVerifier.VerificationResult vr = verifier.verify(fromDeviceId, fromFingerprint);

    log("FingerprintVerifier результат: " + vr.result);
    log("Message: " + vr.message);
    if (vr.node != null) {
      log("Node: " + vr.node.getName() + " (" + vr.node.getIpAddress() + ":" + vr.node.getPort()
          + ")" + ", id=" + vr.node.getId() + ", localMode=" + vr.node.getMode() + ", gatewayIp="
          + vr.node.getGatewayIp() + ":" + vr.node.getGatewayPort());
    }

    NodeManager nodeManager = new NodeManager(this);

    if (vr.node != null && nodeManager.isSelfNode(vr.node)) {
      log.warn("❌ SELF-LOOP на этапе верификации: vr.node == self (call_id=" + shortId(callId)
          + ") — игнорирую offer");
      return;
    }

    String callerName;
    String warning = null;

    switch (vr.result) {
      case MATCH:
        callerName = vr.node != null ? vr.node.getName() : "Устройство";
        log("→ MATCH: " + callerName);
        break;
      case FIRST_CONTACT:
        callerName = vr.node != null ? vr.node.getName() : "Устройство";
        warning = "Первое подключение — fingerprint сохранён";
        log("→ FIRST_CONTACT: " + callerName);
        break;
      case MISMATCH:
        callerName = vr.node != null ? vr.node.getName() : "Устройство";
        warning = "⚠ Fingerprint НЕ совпадает!\nВозможна MITM-атака.";
        log("→ MISMATCH: " + callerName);
        break;
      case UNKNOWN:
      default:
        callerName = fromDeviceId != null && fromDeviceId.length() >= 6
            ? "Устройство " + fromDeviceId.substring(0, 6)
            : "Неизвестный";
        warning = "Неизвестное устройство";
        log("→ UNKNOWN: " + callerName);
        break;
    }

    String responseIp = null;
    int responsePort = 8081;

    TrustedNode fromThisIp = nodeManager.findByIpPort(clientIp, PORT);
    if (fromThisIp != null && fromThisIp.getIpAddress() != null
        && !fromThisIp.getIpAddress().isEmpty()) {
      responseIp = fromThisIp.getIpAddress();
      responsePort = fromThisIp.getPort();
      log("IP для ответа (из findByIpPort): " + responseIp + ":" + responsePort);
    } else if (vr.node != null && vr.node.getIpAddress() != null
        && !vr.node.getIpAddress().isEmpty()) {
      responseIp = vr.node.getIpAddress();
      responsePort = vr.node.getPort();
      log("IP для ответа (из fingerprint-верификации): " + responseIp + ":" + responsePort);
    } else if (clientIp != null && !"unknown".equals(clientIp)) {
      responseIp = clientIp;
      responsePort = 8081;
      log("IP для ответа (из сокета): " + responseIp + ":" + responsePort);
    } else {
      log("⚠ IP для ответа НЕ определён");
    }

    ensureSelfCache();
    if (responseIp != null && cachedSelfIp != null && cachedSelfIp.equals(responseIp)) {
      log.warn("❌ IP для ответа совпадает с self IP (" + responseIp
          + ") — отменяю показ нотификации (call_id=" + shortId(callId) + ")");
      return;
    }

    String targetMode;
    if (modeFromSignal != null && !modeFromSignal.isEmpty()) {
      targetMode = modeFromSignal;
      log("Режим (из сигнала): " + targetMode);
    } else if (vr.node != null && vr.node.getMode() != null) {
      targetMode = vr.node.getMode().name();
      log("Режим (из узла): " + targetMode);
    } else {
      targetMode = "AUTO";
      log("Режим (fallback): AUTO");
    }

    String gatewayIp = (vr.node != null) ? vr.node.getGatewayIp() : null;
    int gatewayPort = (vr.node != null && vr.node.getGatewayPort() > 0) ? vr.node.getGatewayPort()
        : 8083;

    log("Параметры для IncomingCallNotifier:");
    log("  targetMode=" + targetMode);
    log("  gatewayIp=" + gatewayIp);
    log("  gatewayPort=" + gatewayPort);
    log("  targetFingerprint=" + fromFingerprint);

    try {
      log("Показываю IncomingCallNotifier...");
      IncomingCallNotifier notifier = new IncomingCallNotifier(this);
      notifier.show(callId, callerName, responseIp, responsePort, rawJson, warning, targetMode,
          gatewayIp, gatewayPort, fromFingerprint);
      log("✓ Нотификация показана");
    } catch (Exception e) {
      logError("Ошибка показа нотификации", e);
    }

    log("handleIncomingOffer END");
  }

  // ============================================================
  // Broadcast
  // ============================================================

  private void broadcastSignal(String rawJson, String type) {
    try {
      Intent intent = new Intent(ACTION_SIGNAL);
      intent.putExtra(EXTRA_SIGNAL_JSON, rawJson);
      intent.setPackage(getPackageName());
      sendBroadcast(intent);
      log("📡 broadcastSignal: type=" + type + ", json=" + truncate(rawJson, 150));
    } catch (Exception e) {
      logError("Ошибка broadcastSignal", e);
    }
  }

  private void broadcastState(String state, String message) {
    try {
      Intent i = new Intent(ACTION_SERVER_STATE);
      i.putExtra(EXTRA_STATE, state);
      i.putExtra(EXTRA_MESSAGE, message);
      i.setPackage(getPackageName());
      sendBroadcast(i);
      log("📡 broadcastState: state=" + state + ", msg=" + message);
    } catch (Exception e) {
      logError("Ошибка broadcastState", e);
    }
  }

  // ============================================================
  // busy-bye
  // ============================================================

  private void sendBusyBye(String fromFingerprint, String clientIp, String callId) {
    log("sendBusyBye START: fromFingerprint=" + fromFingerprint + ", clientIp=" + clientIp);

    ensureSelfCache();
    if (fromFingerprint != null && !fromFingerprint.isEmpty() && cachedSelfFingerprint != null
        && cachedSelfFingerprint.equals(fromFingerprint)) {
      log.warn("sendBusyBye: fromFingerprint == self — не отправляем");
      return;
    }

    NodeManager nodeManager = new NodeManager(this);

    TrustedNode node = null;
    if (clientIp != null && !"unknown".equals(clientIp)) {
      node = nodeManager.findByIpPort(clientIp, PORT);
    }

    if (node == null && fromFingerprint != null && !fromFingerprint.isEmpty()) {
      log.log("sendBusyBye: узел по (clientIp, 8081) не найден — фолбэк на fingerprint");
      node = nodeManager.findByFingerprint(fromFingerprint);
    }

    if (node == null || node.getIpAddress() == null || node.getIpAddress().isEmpty()) {
      log("sendBusyBye: узел не найден — bye не отправляем");
      return;
    }

    if (cachedSelfIp != null && cachedSelfIp.equals(node.getIpAddress())) {
      log.warn("sendBusyBye: node.ip == self IP (" + cachedSelfIp + ") — не отправляем");
      return;
    }

    try {
      JsonObject bye = new JsonObject();
      bye.addProperty("type", "bye");
      if (callId != null) {
        bye.addProperty("call_id", callId);
      }
      bye.addProperty("from_device_id", DeviceInfoHelper.getDeviceId(this));
      bye.addProperty("timestamp", System.currentTimeMillis() / 1000);

      JsonObject payload = new JsonObject();
      payload.addProperty("reason", "busy");
      bye.add("payload", payload);

      Intent svc = new Intent(this, ConnectionManager.class);
      svc.setAction(ConnectionManager.ACTION_SEND_SIGNAL);
      svc.putExtra(ConnectionManager.EXTRA_TARGET_IP, node.getIpAddress());
      svc.putExtra(ConnectionManager.EXTRA_TARGET_PORT, node.getPort());
      svc.putExtra(ConnectionManager.EXTRA_SIGNAL_TYPE, "bye");
      svc.putExtra(ConnectionManager.EXTRA_SIGNAL_BODY, bye.toString());
      startForegroundService(svc);

      log("✓ busy-bye отправлен " + node.getName() + " (" + node.getKey() + ")");
    } catch (Exception e) {
      logError("Ошибка отправки busy-bye", e);
    }
  }

  // ============================================================
  // HTTP-парсинг запроса
  // ============================================================

  private static String readLine(InputStream in) throws IOException {
    StringBuilder sb = new StringBuilder(64);
    int b;
    while ((b = in.read()) != -1) {
      if (b == '\n')
        break;
      if (b == '\r')
        continue;
      sb.append((char) b);
      if (sb.length() > 16 * 1024)
        throw new IOException("line too long");
    }
    if (sb.length() == 0 && b == -1)
      return null;
    return sb.toString();
  }

  /**
   * Читает JSON-body как байты (для /signal/*, /proxy/*). При length > maxSize бросает BodyTooLargeException → 413.
   */
  private byte[] readBodyBytes(InputStream in, Map<String, String> headers, int maxSize)
      throws IOException, BodyTooLargeException {
    String clStr = headers.get("content-length");
    if (clStr == null)
      return new byte[0];

    int length;
    try {
      length = Integer.parseInt(clStr);
    } catch (NumberFormatException e) {
      return new byte[0];
    }

    if (length < 0) {
      throw new BodyTooLargeException("negative content-length: " + length);
    }
    if (length > maxSize) {
      throw new BodyTooLargeException("body " + length + "B > max " + maxSize + "B");
    }

    byte[] buf = new byte[length];
    int read = 0;
    while (read < length) {
      int r = in.read(buf, read, length - read);
      if (r < 0)
        break;
      read += r;
    }
    if (read < length) {
      byte[] shorter = new byte[read];
      System.arraycopy(buf, 0, shorter, 0, read);
      return shorter;
    }
    return buf;
  }

  /**
   * ★ Шаг 16.8.5: читает body в файл (стриминг, без heap).
   * 
   * @return количество записанных байт (-1 при ошибке записи)
   */
  private static long readBodyToFile(InputStream in, long length, File dest) {
    long written = 0;
    try (FileOutputStream fos = new FileOutputStream(dest)) {
      byte[] buf = new byte[64 * 1024];
      while (written < length) {
        int toRead = (int) Math.min(buf.length, length - written);
        int r = in.read(buf, 0, toRead);
        if (r < 0)
          break;
        fos.write(buf, 0, r);
        written += r;
      }
      fos.flush();
    } catch (IOException e) {
      return -1;
    }
    return written;
  }

  /** Сливает N байт из InputStream (чтобы connection не завис). */
  private static void drainInputStream(InputStream in, long length) {
    try {
      byte[] buf = new byte[8192];
      long remaining = length;
      while (remaining > 0) {
        int toRead = (int) Math.min(buf.length, remaining);
        int r = in.read(buf, 0, toRead);
        if (r < 0)
          break;
        remaining -= r;
      }
    } catch (Exception ignored) {
    }
  }

  private byte[] readAllLimited(InputStream in, int maxBytes) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
    byte[] buf = new byte[8192];
    int total = 0;
    int r;
    while ((r = in.read(buf)) != -1) {
      int toWrite = Math.min(r, maxBytes - total);
      if (toWrite <= 0)
        break;
      out.write(buf, 0, toWrite);
      total += toWrite;
      if (total >= maxBytes)
        break;
    }
    return out.toByteArray();
  }

  private static String urlDecode(String s) {
    if (s == null)
      return null;
    try {
      return URLDecoder.decode(s, "UTF-8");
    } catch (Exception e) {
      return s;
    }
  }

  private static String sanitizeFileName(String s) {
    if (s == null || s.isEmpty())
      return "file";
    return s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
  }

  private static String optString(JsonObject o, String key) {
    if (o == null || !o.has(key) || o.get(key).isJsonNull())
      return null;
    try {
      return o.get(key).getAsString();
    } catch (Exception e) {
      return null;
    }
  }

  // ============================================================
  // HTTP-ответы
  // ============================================================

  private void sendJson(BufferedWriter out, int code, String json) throws IOException {
    byte[] bodyBytes = json.getBytes(StandardCharsets.UTF_8);
    out.write("HTTP/1.1 " + code + " " + httpStatusText(code) + "\r\n");
    out.write("Content-Type: application/json; charset=utf-8\r\n");
    out.write("Content-Length: " + bodyBytes.length + "\r\n");
    out.write("Connection: close\r\n");
    out.write("\r\n");
    out.write(json);
    out.flush();
  }

  private String errorJson(String message) {
    JsonObject obj = new JsonObject();
    obj.addProperty("status", "error");
    obj.addProperty("message", message);
    return obj.toString();
  }

  private String httpStatusText(int code) {
    switch (code) {
      case 200:
        return "OK";
      case 400:
        return "Bad Request";
      case 403:
        return "Forbidden";
      case 404:
        return "Not Found";
      case 405:
        return "Method Not Allowed";
      case 413:
        return "Payload Too Large";
      case 500:
        return "Internal Server Error";
      case 502:
        return "Bad Gateway";
      case 504:
        return "Gateway Timeout";
      default:
        return "Unknown";
    }
  }

  // ============================================================
  // Notification
  // ============================================================

  private void createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Mealse Server",
          NotificationManager.IMPORTANCE_LOW);
      channel.setDescription("Фоновый сервер Mealse");
      NotificationManager nm = getSystemService(NotificationManager.class);
      if (nm != null)
        nm.createNotificationChannel(channel);
    }
  }

  private Notification buildNotification(String text) {
    return new Notification.Builder(this, CHANNEL_ID).setContentTitle("Mealse").setContentText(text)
        .setSmallIcon(android.R.drawable.ic_dialog_info).setOngoing(true).build();
  }

  // ============================================================
  // Хелперы
  // ============================================================

  private void log(String message) {
    if (log != null)
      log.log(message);
    else
      Log.d(TAG, message);
  }

  private void logError(String message, Throwable e) {
    if (log != null)
      log.logError(message, e);
    else
      Log.e(TAG, message, e);
  }

  private String truncate(String s, int max) {
    if (s == null)
      return "null";
    return s.length() > max ? s.substring(0, max) + "..." : s;
  }

  private String shortId(String id) {
    if (id == null)
      return "?";
    return id.length() > 8 ? id.substring(0, 8) : id;
  }

  private static String redactUrl(String url) {
    if (url == null)
      return "null";
    try {
      URL u = new URL(url);
      String host = u.getHost();
      String path = u.getPath();
      String shown = (u.getProtocol() != null ? u.getProtocol() : "http") + "://" + host
          + (path != null ? path : "/");
      if (shown.length() > 200)
        shown = shown.substring(0, 200) + "...";
      return shown;
    } catch (Exception e) {
      return url.length() > 120 ? url.substring(0, 120) + "..." : url;
    }
  }
}