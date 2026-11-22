package com.my.mealse.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.my.mealse.util.MealseLogger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Отправляет сигнальные сообщения (offer / answer / candidate / bye) на удалённое устройство по HTTP/1.1.
 *
 * ⚠️ КРИТИЧНО (Android 14+, SDK 34+): 1. startForeground() вызывается ПЕРВЫМ делом в onStartCommand(). Если этого не сделать, Android убивает процесс с ForegroundServiceDidNotStartInTimeException. 2. stopSelf() / stopForeground() вызываются только когда счётчик активных отправок дошёл до нуля. Иначе 4 параллельных потока делают 4 stopSelf() и провоцируют race condition.
 */
public class ConnectionManager extends Service {

  private static final String TAG = "ConnectionManager";

  // ===== Actions =====
  public static final String ACTION_SEND_SIGNAL = "com.my.mealse.conn.SEND_SIGNAL";
  public static final String ACTION_STOP = "com.my.mealse.conn.STOP";

  // ===== Extras =====
  public static final String EXTRA_TARGET_IP = "target_ip";
  public static final String EXTRA_TARGET_PORT = "target_port";
  public static final String EXTRA_SIGNAL_TYPE = "signal_type";
  public static final String EXTRA_SIGNAL_BODY = "signal_body";

  // ===== Notification =====
  private static final String CHANNEL_ID = "MealseConnChannel";
  private static final int NOTIF_ID = 104;

  // ===== Таймауты =====
  private static final int CONNECT_TIMEOUT_MS = 10_000;
  private static final int READ_TIMEOUT_MS = 10_000;

  private MealseLogger log;

  /**
   * ★ Счётчик активных отправок. Пока он > 0 — сервис жив. Когда опускается до 0 — можно вызывать stopForeground()/stopSelf(). Это устраняет race condition при 4 параллельных startForegroundService().
   */
  private final AtomicInteger pendingSends = new AtomicInteger(0);

  /**
   * ★ Флаг, что startForeground() уже был успешно вызван. Защищает от повторных вызовов startForeground() в onStartCommand() (Android 12+ ругается, если startForeground() вызывается повторно без остановки предыдущего уведомления, но в нашем случае это безопасно — просто обновляем уведомление).
   */
  private volatile boolean foregroundStarted = false;

  /**
   * ★ НОВОЕ: ExecutorService вместо new Thread на каждый сигнал.
   *
   * Раньше: 6-10 потоков на один звонок (offer + answer + N candidate + bye). Потоки создаются/умирают, pendingSends скачет.
   *
   * Теперь: cached pool (потоки переиспользуются). shutdown() в onDestroy.
   */
  private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
    Thread t = new Thread(r, "ConnHttp-" + System.nanoTime());
    t.setDaemon(true);
    return t;
  });

  // ============================================================
  // Lifecycle
  // ============================================================

  @Override
  public void onCreate() {
    super.onCreate();

    // ⚠️ КРИТИЧНО: startForeground ПЕРВЫМ — до логгера, до канала.
    // На Android 8+ канал можно создать в любой момент, но уведомление
    // должно быть сразу, иначе система убьёт сервис.
    try {
      createNotificationChannel();
      startForeground(NOTIF_ID, buildNotification("Сигналинг активен"));
      foregroundStarted = true;
    } catch (Exception e) {
      // Логгера ещё нет — пишем в logcat
      Log.e(TAG, "Ошибка startForeground в onCreate", e);
    }

    // Только ПОСЛЕ startForeground — логгер
    try {
      log = MealseLogger.get(this, TAG);
      log.log("══ onCreate START ══");
      log.log("startForeground выполнен первым (onCreate)");
      log.log("══ onCreate END ══");
    } catch (Exception e) {
      Log.e(TAG, "Ошибка инициализации логгера", e);
    }
  }

  /**
   * ★ ИЗМЕНЕНО: ACTION_STOP → maybeStopSelf() вместо прямого stopForeground + stopSelf. Раньше ACTION_STOP игнорировал pendingSends — если шла активная отправка, сервис умирал, а поток продолжал работать вхолостую.
   *
   * maybeStopSelf() откладывает остановку, пока pendingSends > 0.
   */
  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    // ★ КРИТИЧНО: startForeground() должен быть ПЕРВЫМ делом в onStartCommand.
    // Это защита от ForegroundServiceDidNotStartInTimeException на Android 14+,
    // когда система запускает сервис несколько раз подряд (offer + 3 candidate).
    try {
      createNotificationChannel();
      startForeground(NOTIF_ID, buildNotification("Сигналинг активен"));
      foregroundStarted = true;
    } catch (Exception e) {
      Log.e(TAG, "Ошибка startForeground в onStartCommand", e);
    }

    log("══ onStartCommand START ══");

    try {
      if (intent == null) {
        log("intent == null — завершаем");
        maybeStopSelf();
        return START_NOT_STICKY;
      }

      String action = intent.getAction();
      log("Action: " + action);

      if (ACTION_STOP.equals(action)) {
        log("Получена команда STOP — maybeStopSelf (безопасно)");
        // ★ ИЗМЕНЕНО: maybeStopSelf вместо прямого stopSelf
        maybeStopSelf();
        return START_NOT_STICKY;
      }

      if (ACTION_SEND_SIGNAL.equals(action)) {
        handleSendSignal(intent);
      } else {
        log("Неизвестный action — завершаем");
        maybeStopSelf();
      }

      log("══ onStartCommand END ══");
      return START_NOT_STICKY;

    } catch (Exception e) {
      logError("Критическая ошибка в onStartCommand", e);
      maybeStopSelf();
      return START_NOT_STICKY;
    }
  }

  /**
   * ★ ИЗМЕНЕНО: - stopForeground только если foregroundStarted (защита от двойного вызова — onDestroy вызывается и после maybeStopSelf); - executor.shutdown() + awaitTermination(2s) — даём активным отправкам завершиться до kill процесса.
   */
  @Override
  public void onDestroy() {
    log("onDestroy");
    try {
      if (foregroundStarted) {
        stopForeground(true);
        foregroundStarted = false;
      }
    } catch (Exception ignored) {
    }

    // ★ НОВОЕ: останавливаем пул, ждём до 2 с
    try {
      executor.shutdown();
      if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
        log("onDestroy: executor не завершился за 2с — shutdownNow");
        executor.shutdownNow();
      }
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      executor.shutdownNow();
    } catch (Exception ignored) {
    }

    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  // ============================================================
  // Основная логика
  // ============================================================

  /**
   * ★ ИЗМЕНЕНО: executor.execute вместо new Thread. Меньше создания потоков (cached pool переиспользует), executor.shutdown() в onDestroy.
   */
  private void handleSendSignal(Intent intent) {
    String targetIp = intent.getStringExtra(EXTRA_TARGET_IP);
    int targetPort = intent.getIntExtra(EXTRA_TARGET_PORT, 8081);
    String type = intent.getStringExtra(EXTRA_SIGNAL_TYPE);
    String body = intent.getStringExtra(EXTRA_SIGNAL_BODY);

    log("Параметры: ip=" + targetIp + ", port=" + targetPort + ", type=" + type + ", body="
        + (body != null ? body.length() + " симв." : "null"));

    if (targetIp == null || targetIp.isEmpty() || type == null || type.isEmpty() || body == null
        || body.isEmpty()) {
      logError("Не хватает параметров для отправки сигнала", null);
      maybeStopSelf();
      return;
    }

    // ★ Увеличиваем счётчик ДО запуска потока, чтобы onStartCommand
    // не успел вызвать maybeStopSelf() пока поток ещё не стартовал.
    pendingSends.incrementAndGet();
    log("pendingSends=" + pendingSends.get() + " (после increment)");

    // Отправка в фоновом потоке (cached pool)
    final String fIp = targetIp;
    final int fPort = targetPort;
    final String fType = type;
    final String fBody = body;

    // ★ ИЗМЕНЕНО: executor.execute вместо new Thread
    try {
      executor.execute(() -> sendSignalHttp(fIp, fPort, fType, fBody));
    } catch (Exception e) {
      logError("handleSendSignal: executor.execute", e);
      // Откат счётчика, если execute упал
      int remaining = pendingSends.decrementAndGet();
      log("pendingSends=" + remaining + " (после отката)");
      maybeStopSelf();
    }
  }

  // ============================================================
  // HTTP-клиент
  // ============================================================

  private void sendSignalHttp(String ip, int port, String type, String jsonBody) {
    String urlStr = "http://" + ip + ":" + port + "/signal/" + type;
    log("════ sendSignalHttp START (" + type + ") ════");
    log("POST " + urlStr);
    log("Body (" + jsonBody.length() + " симв.): " + truncate(jsonBody, 200));

    HttpURLConnection conn = null;
    long startTime = System.currentTimeMillis();

    try {
      URL url = new URL(urlStr);
      conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("POST");
      conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
      conn.setRequestProperty("Accept", "application/json");
      conn.setRequestProperty("User-Agent", "Mealse/1.0");
      conn.setDoOutput(true);
      conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
      conn.setReadTimeout(READ_TIMEOUT_MS);

      byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
      conn.setFixedLengthStreamingMode(bodyBytes.length);

      log("Открываю OutputStream...");
      try (BufferedWriter writer = new BufferedWriter(
          new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8))) {
        writer.write(jsonBody);
        writer.flush();
      }
      log("Тело запроса отправлено, жду ответ...");

      int code = conn.getResponseCode();
      long elapsed = System.currentTimeMillis() - startTime;
      log("HTTP " + code + " от " + ip + ":" + port + " за " + elapsed + " мс");

      // Читаем ответ
      if (code >= 200 && code < 300) {
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
          StringBuilder sb = new StringBuilder();
          String line;
          while ((line = reader.readLine()) != null)
            sb.append(line);
          log("✓ Ответ (" + type + "): " + truncate(sb.toString(), 300));
        }
      } else {
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
          StringBuilder sb = new StringBuilder();
          String line;
          while ((line = reader.readLine()) != null)
            sb.append(line);
          logError("✗ HTTP " + code + " (" + type + "): " + truncate(sb.toString(), 300), null);
        } catch (Exception e) {
          logError("HTTP " + code + ", не удалось прочитать errorStream", e);
        }
      }

    } catch (java.net.SocketTimeoutException e) {
      long elapsed = System.currentTimeMillis() - startTime;
      logError("TIMEOUT подключения к " + ip + ":" + port + " за " + elapsed + " мс (" + type + ")",
          e);

    } catch (java.net.ConnectException e) {
      String msg = e.getMessage() != null ? e.getMessage() : "";
      logError("ConnectException к " + ip + ":" + port + ": " + msg + " (" + type + ")", e);

    } catch (java.net.UnknownHostException e) {
      logError("UnknownHost: " + ip + " (" + type + ")", e);

    } catch (IOException e) {
      String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
      logError("IOException: " + msg + " (" + type + ")", e);

    } catch (Exception e) {
      logError("Неожиданная ошибка отправки (" + type + ")", e);

    } finally {
      if (conn != null) {
        try {
          conn.disconnect();
        } catch (Exception ignored) {
        }
      }

      log("════ sendSignalHttp END (" + type + ") ════");

      // ★ Уменьшаем счётчик. Только когда он дошёл до нуля — останавливаем сервис.
      int remaining = pendingSends.decrementAndGet();
      log("pendingSends=" + remaining + " (после decrement, type=" + type + ")");
      if (remaining <= 0) {
        log("Все отправки завершены — останавливаю сервис");
        maybeStopSelf();
      } else {
        log("Ещё есть активные отправки (" + remaining + ") — сервис продолжает работу");
      }
    }
  }

  // ============================================================
  // Остановка сервиса
  // ============================================================

  /**
   * ★ Безопасная остановка: только если счётчик активных отправок == 0. Если > 0 — не трогаем, поток сам вызовет maybeStopSelf() в finally.
   */
  /**
   * ★ ИЗМЕНЕНО: stopForeground только если foregroundStarted — защита от двойного вызова (onDestroy + maybeStopSelf).
   */
  private void maybeStopSelf() {
    if (pendingSends.get() > 0) {
      log("maybeStopSelf: pendingSends=" + pendingSends.get() + " > 0 — откладываю остановку");
      return;
    }
    try {
      log("maybeStopSelf: pendingSends=0 — останавливаю сервис");
      if (foregroundStarted) {
        stopForeground(true);
        foregroundStarted = false;
      }
      stopSelf();
    } catch (Exception e) {
      logError("Ошибка stopSelf", e);
    }
  }

  // ============================================================
  // Notification
  // ============================================================

  private void createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Mealse Сигналинг",
          NotificationManager.IMPORTANCE_LOW);
      channel.setDescription("Отправка сигналов для звонков Mealse");
      NotificationManager nm = getSystemService(NotificationManager.class);
      if (nm != null) {
        nm.createNotificationChannel(channel);
      }
    }
  }

  private Notification buildNotification(String text) {
    return new Notification.Builder(this, CHANNEL_ID).setContentTitle("Mealse").setContentText(text)
        .setSmallIcon(android.R.drawable.stat_sys_upload).setOngoing(true).build();
  }

  // ============================================================
  // Хелперы логирования
  // ============================================================

  private void log(String message) {
    if (log != null) {
      log.log(message);
    } else {
      Log.d(TAG, message);
    }
  }

  private void logError(String message, Throwable e) {
    if (log != null) {
      log.logError(message, e);
    } else {
      Log.e(TAG, message, e);
    }
  }

  private String truncate(String s, int max) {
    if (s == null)
      return "null";
    return s.length() > max ? s.substring(0, max) + "..." : s;
  }
}