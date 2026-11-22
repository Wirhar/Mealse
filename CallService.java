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

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.my.mealse.service.ConnectionManager;
import com.my.mealse.service.ServerService;
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
 *     PendingIntent на CallActivity: после системного убийства
 *     активити пользователь видит, что звонок идёт, и может вернуться.
 *
 * Сервис НЕ держит WebView — это делает статический retained-движок
 * в CallActivity (sWebView). Сервис только:
 *  - поднимает foreground-статус с типом camera|microphone;
 *  - показывает/обновляет уведомление;
 *  - останавливается по команде ACTION_STOP (из endCallInternal).
 */
public class CallService extends Service {

  private static final String TAG = "CallService";

  // ===== Actions =====
  public static final String ACTION_START = "com.my.mealse.call.START";
  public static final String ACTION_STOP  = "com.my.mealse.call.STOP";

  // ===== Extras =====
  public static final String EXTRA_CALL_ID     = "call_id";
  public static final String EXTRA_PEER_NAME   = "peer_name";

  // ===== Notification =====
  private static final String CHANNEL_ID = "MealseActiveCallChannel";
  private static final int NOTIF_ID = 105;

  private MealseLogger log;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());

  private volatile boolean foregroundStarted = false;
  private String currentCallId;
  private String currentPeerName;

  /**
   * ★ Приёмник команды STOP из endCallInternal (через startService/stopService
   * или прямой sendBroadcast — на выбор вызывающего). Также ловит
   * ACTION_STOP из уведомления (кнопка «Завершить»).
   */
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

    // ★ startForeground() ПЕРВЫМ делом — иначе на Android 12+
    // ForegroundServiceDidNotStartInTimeException.
    try {
      createNotificationChannel();
      startForegroundWithType(buildNotification("Звонок активен"));
    } catch (Exception e) {
      Log.e(TAG, "Ошибка startForeground в onCreate", e);
    }

    registerStopReceiver();
    log.log("══ onCreate END ══");
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    log("══ onStartCommand START ══");

    // ★ Повторный startForeground — безопасно (обновляет уведомление).
    try {
      createNotificationChannel();
      startForegroundWithType(buildNotification("Звонок активен"));
    } catch (Exception e) {
      Log.e(TAG, "Ошибка startForeground в onStartCommand", e);
    }

    if (intent == null) {
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

      log("Звонок: callId=" + shortId(currentCallId)
          + ", peer=" + currentPeerName);

      // Обновляем уведомление с именем собеседника
      updateNotification();
    } else {
      log("Неизвестный action — сервис жив, жду STOP");
    }

    return START_STICKY;
  }

  @Override
  public void onDestroy() {
    log("onDestroy START");
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
  // startForeground с типом camera|microphone
  // ============================================================

  /**
   * ★ На API 29+ передаём явный foregroundServiceType. На API 34+
   * без декларации в манифесте и без разрешений
   * FOREGROUND_SERVICE_CAMERA/MICROPHONE будет SecurityException —
   * см. AndroidManifest.xml (раздел «Сервис звонка»).
   */
  private void startForegroundWithType(Notification notif) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
          | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
      try {
        startForeground(NOTIF_ID, notif, type);
      } catch (Exception e) {
        // Некоторые прошивки требуют type без битовой маски, если
        // тип не задекларирован в манифесте. Падаем в fallback.
        Log.e(TAG, "startForeground(type) failed — fallback", e);
        startForeground(NOTIF_ID, notif);
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
      stopForegroundCompat();
      stopSelf();
    } catch (Exception e) {
      log.logError("stopSelfSafely", e);
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
          NotificationManager.IMPORTANCE_LOW); // LOW — без звука, не мешает
      channel.setDescription("Уведомление о текущем звонке Mealse");
      channel.setShowBadge(false);
      channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
      NotificationManager nm = getSystemService(NotificationManager.class);
      if (nm != null) nm.createNotificationChannel(channel);
    }
  }

  private Notification buildNotification(String text) {
    // ★ PendingIntent на CallActivity — вернуться к звонку.
    // Используем setClassName, чтобы не зависеть от compile-time импорта
    // (CallActivity — в этом же пакете, но лишняя связка не нужна).
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
    PendingIntent openPi = PendingIntent.getActivity(
        this, NOTIF_ID, openIntent, piFlags);

    // ★ Кнопка «Завершить» — отправляет ACTION_STOP в этот же сервис.
    Intent stopIntent = new Intent(this, CallService.class);
    stopIntent.setAction(ACTION_STOP);
    PendingIntent stopPi = PendingIntent.getService(
        this, NOTIF_ID + 1, stopIntent, piFlags);

    String title = "Звонок активен";
    String body = (currentPeerName != null && !currentPeerName.isEmpty())
        ? "Собеседник: " + currentPeerName + " — нажмите, чтобы вернуться"
        : text;

    NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(title)
        .setContentText(body)
        .setSmallIcon(android.R.drawable.stat_sys_phone_call)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_CALL)
        .setOngoing(true)
        .setAutoCancel(false)
        .setContentIntent(openPi)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Завершить", stopPi);

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
      log.logError("updateNotification", e);
    }
  }

  // ============================================================
  // stopReceiver (ACTION_STOP из endCallInternal / других мест)
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
      log.logError("registerStopReceiver", e);
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
  // Публичные хелперы для вызова из CallActivity / MealseApp
  // ============================================================

  /**
   * Запустить сервис (из CallActivity.onCreate, ветка new call).
   * Идемпотентно — повторный startForegroundService обновит уведомление.
   */
  public static void start(Context ctx, String callId, String peerName) {
    try {
      Intent i = new Intent(ctx, CallService.class);
      i.setAction(ACTION_START);
      if (callId != null) i.putExtra(EXTRA_CALL_ID, callId);
      if (peerName != null) i.putExtra(EXTRA_PEER_NAME, peerName);
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
   * Остановить сервис (из endCallInternal).
   * Двойной вызов безопасен.
   */
  public static void stop(Context ctx) {
    try {
      Intent i = new Intent(ctx, CallService.class);
      i.setAction(ACTION_STOP);
      ctx.startService(i); // STOP обработается в onStartCommand
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