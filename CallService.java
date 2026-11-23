package com.my.mealse;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.my.mealse.util.CallStateManager;
import com.my.mealse.util.MealseLogger;

/**
 * CallService — foreground-сервис активного звонка.
 *
 * Зачем нужен:
 *  1. Android 10+ отбирает камеру у фоновых приложений. Без FGS с
 *     foregroundServiceType="camera|microphone" видеотрек умирает,
 *     как только пользователь уходит с экрана звонка. Логи: rxV=0,
 *     vSize=480x640@0fps.
 *  2. MIUI/HyperOS с агрессивным memory manager убивает процесс
 *     CallActivity при переходе в FileManagerActivity. С FGS процесс
 *     жив, а retained-WebView (статический движок в CallActivity)
 *     продолжает держать PeerConnection.
 *  3. Постоянное уведомление «Звонок активен — вернуться» с
 *     PendingIntent на CallActivity.
 *
 * ★ P3: heartbeat живёт ЗДЕСЬ (а не в CallActivity). Сервис существует
 *   ровно столько, сколько звонок → heartbeat не держит Activity и не
 *   дублируется при пересозданиях экрана. Раз в 5 с обновляет
 *   CallStateManager, чтобы isCallActive() не сбрасывался по таймауту.
 *
 * ★ P6: остановка через stopService() (не startService из фона).
 *
 * ★ P2 (замечание 2): targetNodeId передаётся в сервис через
 *   EXTRA_TARGET_NODE и сохраняется в currentTargetNodeId. При
 *   ensureCallActive() (переустановка флага после автосброса) не
 *   теряем данные узла — берём currentTargetNodeId, иначе существующий
 *   из prefs.
 *
 * Сервис НЕ держит WebView — это делает статический retained-движок
 * в CallActivity (sWebView).
 */
public class CallService extends Service {

  private static final String TAG = "CallService";

  // ===== Actions =====
  public static final String ACTION_START = "com.my.mealse.call.START";
  public static final String ACTION_STOP  = "com.my.mealse.call.STOP";

  // ===== Extras =====
  public static final String EXTRA_CALL_ID     = "call_id";
  public static final String EXTRA_PEER_NAME   = "peer_name";
  /**
   * ★ НОВОЕ (замечание 2): targetNodeId — чтобы ensureCallActive()
   * не терял данные узла при переустановке флага после автосброса.
   */
  public static final String EXTRA_TARGET_NODE = "target_node";

  // ===== Notification =====
  private static final String CHANNEL_ID = "MealseActiveCallChannel";
  private static final int NOTIF_ID = 105;

  // ===== P3: heartbeat =====
  private static final long HEARTBEAT_INTERVAL_MS = 5_000L;

  private MealseLogger log;
  private CallStateManager callStateManager;

  private final Handler heartbeatHandler = new Handler(Looper.getMainLooper());
  private volatile boolean heartbeatRunning = false;

  private volatile boolean foregroundStarted = false;
  private String currentCallId;
  private String currentPeerName;
  /** ★ НОВОЕ (замечание 2): targetNodeId из Intent. */
  private String currentTargetNodeId;

  /** P3: цикл heartbeat. Не держит Activity; останавливается со смертью сервиса. */
  private final Runnable heartbeatRunnable = new Runnable() {
    @Override
    public void run() {
      if (!heartbeatRunning) return;
      try {
        if (callStateManager != null) callStateManager.heartbeat();
      } catch (Exception e) {
        log("heartbeat error: " + e.getMessage());
      }
      heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
    }
  };

  /** Приёмник команды STOP (альтернативный путь; основной — stopService). */
  private final BroadcastReceiver stopReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      if (intent == null) return;
      if (ACTION_STOP.equals(intent.getAction())) {
        log("stopReceiver: ACTION_STOP — останавливаю сервис");
        stopSelfSafely();
      }
    }
  };
  private boolean stopReceiverRegistered = false;

  // ============================================================
  // Lifecycle
  // ============================================================

  @Override
  public void onCreate() {
    super.onCreate();
    log = MealseLogger.get(this, TAG);
    log.log("══ onCreate START ══");

    // startForeground() ПЕРВЫМ делом — иначе на Android 12+
    // ForegroundServiceDidNotStartInTimeException.
    try {
      createNotificationChannel();
      startForegroundWithType(buildNotification("Звонок активен"));
    } catch (Exception e) {
      Log.e(TAG, "Ошибка startForeground в onCreate", e);
    }

    callStateManager = new CallStateManager(this);
    registerStopReceiver();
    startHeartbeat();   // P3

    log.log("══ onCreate END ══");
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    log("══ onStartCommand START ══");

    // Повторный startForeground безопасен (обновляет уведомление).
    try {
      createNotificationChannel();
      startForegroundWithType(buildNotification("Звонок активен"));
    } catch (Exception e) {
      Log.e(TAG, "Ошибка startForeground в onStartCommand", e);
    }

    if (intent == null) {
      // Zombie-рестарт после смерти процесса без callId — не воскрешаем.
      if (currentCallId == null) {
        log("intent == null и callId неизвестен — останавливаюсь");
        stopSelfSafely();
        return START_NOT_STICKY;
      }
      log("intent == null — сервис жив, жду STOP");
      return START_STICKY;
    }

    String action = intent.getAction();
    log("Action: " + action);

    if (ACTION_STOP.equals(action)) {
      log("ACTION_STOP — останавливаю сервис");
      stopSelfSafely();
      return START_NOT_STICKY;
    }

    if (ACTION_START.equals(action)) {
      currentCallId = intent.getStringExtra(EXTRA_CALL_ID);
      currentPeerName = intent.getStringExtra(EXTRA_PEER_NAME);
      // ★ НОВОЕ (замечание 2): читаем targetNodeId
      currentTargetNodeId = intent.getStringExtra(EXTRA_TARGET_NODE);

      log("Звонок: callId=" + shortId(currentCallId) + ", peer=" + currentPeerName
          + ", node=" + currentTargetNodeId);

      ensureCallActive();   // P3: (ре)активируем флаг после grant/долгого ожидания
      updateNotification();
    } else {
      log("Неизвестный action — сервис жив, жду STOP");
    }

    return START_STICKY;
  }

  @Override
  public void onDestroy() {
    log("onDestroy START");
    stopHeartbeat();          // P3
    unregisterStopReceiver();
    try {
      stopForegroundCompat();
    } catch (Exception ignored) {}
    log("onDestroy END");
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null; // started-service, без bind
  }

  // ============================================================
  // P3: heartbeat
  // ============================================================

  private void startHeartbeat() {
    heartbeatHandler.removeCallbacks(heartbeatRunnable);
    heartbeatRunning = true;
    heartbeatHandler.post(heartbeatRunnable);
    log("startHeartbeat: интервал=" + HEARTBEAT_INTERVAL_MS + "мс");
  }

  private void stopHeartbeat() {
    heartbeatRunning = false;
    heartbeatHandler.removeCallbacks(heartbeatRunnable);
  }

  /**
   * Если флаг call_active успел сброситься (например, пользователь долго
   * выдавал разрешения и heartbeat ещё не шёл) — переустанавливаем его.
   *
   * ★ ИЗМЕНЕНО (замечание 2): targetNodeId НЕ теряем. Берём из
   * currentTargetNodeId (передан в Intent), иначе — существующий из prefs.
   * Раньше при автосбросе getActiveTargetNode() возвращал null, и флаг
   * переустанавливался с null → данные узла в prefs затирались.
   */
  private void ensureCallActive() {
    try {
      if (callStateManager == null) callStateManager = new CallStateManager(this);
      if (currentCallId != null && !callStateManager.isCallActive()) {
        // ★ ФИКС: не теряем targetNodeId — берём переданный, иначе существующий
        String node = (currentTargetNodeId != null && !currentTargetNodeId.isEmpty())
            ? currentTargetNodeId
            : callStateManager.getActiveTargetNode();
        callStateManager.setCallActive(currentCallId, node);
        log("ensureCallActive: флаг переустановлен для " + shortId(currentCallId)
            + ", node=" + node);
      }
    } catch (Exception e) {
      log("ensureCallActive error: " + e.getMessage());
    }
  }

  // ============================================================
  // startForeground с типом camera|microphone
  // ============================================================

  private void startForegroundWithType(Notification notif) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
          | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
      try {
        startForeground(NOTIF_ID, notif, type);
      } catch (Exception e) {
        Log.e(TAG, "startForeground(type) failed — fallback", e);
        try {
          startForeground(NOTIF_ID, notif);
        } catch (Exception e2) {
          Log.e(TAG, "startForeground fallback failed", e2);
        }
      }
    } else {
      startForeground(NOTIF_ID, notif);
    }
    foregroundStarted = true;
  }

  private void stopForegroundCompat() {
    if (!foregroundStarted) return;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      stopForeground(STOP_FOREGROUND_REMOVE);
    } else {
      //noinspection deprecation
      stopForeground(true);
    }
    foregroundStarted = false;
  }

  private void stopSelfSafely() {
    try {
      stopHeartbeat();        // P3
      stopForegroundCompat();
      stopSelf();
    } catch (Exception e) {
      if (log != null) log.logError("stopSelfSafely", e);
    }
  }

  // ============================================================
  // Уведомление «Звонок активен — вернуться»
  // ============================================================

  private void createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationChannel channel = new NotificationChannel(
          CHANNEL_ID,
          "Активный звонок Mealse",
          NotificationManager.IMPORTANCE_LOW);
      channel.setDescription("Уведомление о текущем звонке Mealse");
      channel.setShowBadge(false);
      channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
      NotificationManager nm = getSystemService(NotificationManager.class);
      if (nm != null) nm.createNotificationChannel(channel);
    }
  }

  private Notification buildNotification(String text) {
    Intent openIntent = new Intent();
    openIntent.setClassName(getPackageName(), "com.my.mealse.CallActivity");
    openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
        | Intent.FLAG_ACTIVITY_CLEAR_TOP
        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    if (currentCallId != null) {
      openIntent.putExtra("call_id", currentCallId);
    }

    int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      piFlags |= PendingIntent.FLAG_IMMUTABLE;
    }
    PendingIntent openPi = PendingIntent.getActivity(this, NOTIF_ID, openIntent, piFlags);

    // Кнопка «Завершить» — ACTION_STOP в этот же сервис (user-action, разрешён).
    Intent stopIntent = new Intent(this, CallService.class);
    stopIntent.setAction(ACTION_STOP);
    PendingIntent stopPi = PendingIntent.getService(this, NOTIF_ID + 1, stopIntent, piFlags);

    String title = "Звонок активен";
    String body = (currentPeerName != null && !currentPeerName.isEmpty())
        ? "Собеседник: " + currentPeerName + " — нажмите, чтобы вернуться"
        : text;

    NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(title)
        .setContentText(body)
        // ★ Собственные иконки вместо системных (есть в res/drawable)
        .setSmallIcon(R.drawable.ic_call)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_CALL)
        .setOngoing(true)
        .setAutoCancel(false)
        .setContentIntent(openPi)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .addAction(R.drawable.ic_call_end, "Завершить", stopPi);

    return b.build();
  }

  private void updateNotification() {
    try {
      Notification n = buildNotification("Звонок активен");
      NotificationManager nm = getSystemService(NotificationManager.class);
      if (nm != null && foregroundStarted) {
        nm.notify(NOTIF_ID, n);
      }
    } catch (Exception e) {
      if (log != null) log.logError("updateNotification", e);
    }
  }

  // ============================================================
  // stopReceiver
  // ============================================================

  private void registerStopReceiver() {
    if (stopReceiverRegistered) return;
    try {
      IntentFilter f = new IntentFilter(ACTION_STOP);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        registerReceiver(stopReceiver, f, Context.RECEIVER_NOT_EXPORTED);
      } else {
        //noinspection deprecation
        registerReceiver(stopReceiver, f);
      }
      stopReceiverRegistered = true;
    } catch (Exception e) {
      if (log != null) log.logError("registerStopReceiver", e);
    }
  }

  private void unregisterStopReceiver() {
    if (!stopReceiverRegistered) return;
    try {
      unregisterReceiver(stopReceiver);
    } catch (Exception ignored) {}
    stopReceiverRegistered = false;
  }

  // ============================================================
  // Публичные хелперы
  // ============================================================

  /** Запуск (из CallActivity после grant / onResume). Идемпотентно. */
  public static void start(Context ctx, String callId, String peerName) {
    start(ctx, callId, peerName, null);
  }

  /**
   * ★ НОВОЕ (замечание 2): перегрузка с targetNodeId.
   * Чтобы ensureCallActive() не терял данные узла при переустановке
   * флага после автосброса, CallActivity передаёт targetNodeId сюда.
   */
  public static void start(Context ctx, String callId, String peerName, String targetNodeId) {
    try {
      Intent i = new Intent(ctx, CallService.class);
      i.setAction(ACTION_START);
      if (callId != null) i.putExtra(EXTRA_CALL_ID, callId);
      if (peerName != null) i.putExtra(EXTRA_PEER_NAME, peerName);
      if (targetNodeId != null) i.putExtra(EXTRA_TARGET_NODE, targetNodeId);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ctx.startForegroundService(i);
      } else {
        ctx.startService(i);
      }
    } catch (Exception e) {
      Log.e(TAG, "CallService.start failed", e);
    }
  }

  /**
   * ★ P6: остановка через stopService() — НЕ подвержен ограничению
   * на старт сервисов из фона (Android 12+). onDestroy снимет heartbeat,
   * уведомление и foreground-статус. Двойной вызов безопасен.
   */
  public static void stop(Context ctx) {
    try {
      ctx.stopService(new Intent(ctx, CallService.class));
    } catch (Exception e) {
      Log.e(TAG, "CallService.stop failed", e);
    }
  }

  // ============================================================
  // Хелперы
  // ============================================================

  private void log(String msg) {
    if (log != null) log.log(msg);
    else Log.d(TAG, msg);
  }

  private static String shortId(String id) {
    if (id == null) return "?";
    return id.length() > 8 ? id.substring(0, 8) : id;
  }
}