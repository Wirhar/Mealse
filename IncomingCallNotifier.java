package com.my.mealse.util;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.my.mealse.IncomingCallActivity;
import com.my.mealse.R;

/**
 * Показывает full-screen intent нотификацию для входящего звонка.
 * Работает поверх lock screen.
 */
public class IncomingCallNotifier {

  private static final String TAG = "IncomingCallNotifier";
  private static final String CHANNEL_ID = "MealseIncomingCallChannel";
  private static final int NOTIF_ID = 200;

  private final Context context;
  private final NotificationManager notificationManager;
  private final MealseLogger log;

  public IncomingCallNotifier(Context context) {
    this.context = context.getApplicationContext();
    this.notificationManager = (NotificationManager)
        context.getSystemService(Context.NOTIFICATION_SERVICE);
    this.log = MealseLogger.get(this.context, TAG);
    log.log("══ IncomingCallNotifier создан ══");
    createChannel();
  }

  /**
   * ★ РАСШИРЕННЫЙ: полная сигнатура с режимом и fingerprint.
   *
   * @param callId             UUID звонка
   * @param callerName         имя вызывающего (или IP)
   * @param callerIp           IP вызывающего
   * @param callerPort         порт
   * @param offerJson          JSON offer для передачи в CallActivity
   * @param warning            предупреждение (например, MISMATCH fingerprint), может быть null
   * @param targetMode         режим подключения: "AUTO" | "DIRECT" | "GATEWAY"
   * @param gatewayIp          IP шлюза (для GATEWAY), может быть null
   * @param gatewayPort        порт шлюза (для GATEWAY), default 8083
   * @param targetFingerprint  fingerprint вызывающего для self-call проверки
   */
  public void show(String callId, String callerName, String callerIp, int callerPort,
                   String offerJson, String warning,
                   String targetMode, String gatewayIp, int gatewayPort,
                   String targetFingerprint) {

    log.log("show() START: callId=" + shortId(callId)
        + ", callerName=" + callerName
        + ", callerIp=" + callerIp
        + ", callerPort=" + callerPort
        + ", offerJson=" + (offerJson != null ? offerJson.length() + " симв." : "null")
        + ", warning=" + warning
        + ", targetMode=" + targetMode
        + ", gatewayIp=" + gatewayIp
        + ", gatewayPort=" + gatewayPort
        + ", targetFingerprint=" + targetFingerprint);

    if (callerIp == null || callerIp.isEmpty()) {
      log.error("show(): callerIp пустой — нотификация будет бесполезной!");
    }

    // Формируем Intent для IncomingCallActivity — там должен быть callerIp,
    // чтобы CallActivity знал, куда отправлять answer/candidate/bye.
    Intent incomingIntent = IncomingCallActivity.newIntent(
        context, callId, callerName, callerIp, callerPort, offerJson, warning,
        targetMode, gatewayIp, gatewayPort, targetFingerprint);

    log.log("IncomingCallActivity.newIntent: "
        + "action=" + incomingIntent.getAction()
        + ", call_id=" + shortId(incomingIntent.getStringExtra("call_id"))
        + ", caller_ip=" + incomingIntent.getStringExtra("caller_ip")
        + ", caller_port=" + incomingIntent.getIntExtra("caller_port", -1)
        + ", target_mode=" + incomingIntent.getStringExtra("target_mode")
        + ", gateway_ip=" + incomingIntent.getStringExtra("gateway_ip")
        + ", gateway_port=" + incomingIntent.getIntExtra("gateway_port", -1)
        + ", target_fingerprint=" + incomingIntent.getStringExtra("target_fingerprint")
        + ", warning=" + incomingIntent.getStringExtra("warning"));

    // Full-screen intent
    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      flags |= PendingIntent.FLAG_IMMUTABLE;
    }
    PendingIntent fullScreenPi = PendingIntent.getActivity(
        context, callId.hashCode(), incomingIntent, flags);

    Notification.Builder builder;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      builder = new Notification.Builder(context, CHANNEL_ID);
    } else {
      builder = new Notification.Builder(context);
    }

    // ★ ИЗМЕНЕНО: показываем режим в тексте уведомления
    String callerLine = (callerName != null ? callerName : "Неизвестный")
        + " (" + callerIp + ")";
    if ("GATEWAY".equalsIgnoreCase(targetMode)) {
      callerLine += "  ·  через шлюз";
    } else if ("DIRECT".equalsIgnoreCase(targetMode)) {
      callerLine += "  ·  напрямую";
    }

    builder
        .setContentTitle("Входящий звонок Mealse")
        .setContentText(callerLine)
        .setSmallIcon(R.drawable.ic_call_incoming)
        .setPriority(Notification.PRIORITY_HIGH)
        .setCategory(Notification.CATEGORY_CALL)
        .setAutoCancel(true)
        .setOngoing(true)
        .setContentIntent(fullScreenPi)
        .setVisibility(Notification.VISIBILITY_PUBLIC);

    // Full-screen intent (API 29+)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      builder.setFullScreenIntent(fullScreenPi, true);
      log.log("setFullScreenIntent установлен (API " + Build.VERSION.SDK_INT + ")");
    } else {
      log.log("API < 29: только heads-up, full-screen intent не поддерживается");
    }

    // Кнопка «Отклонить» — сразу отправляет bye (без открытия Activity)
    Intent declineIntent = new Intent(context, IncomingCallActivity.class);
    declineIntent.setAction(IncomingCallActivity.ACTION_DECLINE);
    declineIntent.putExtra(IncomingCallActivity.EXTRA_CALL_ID, callId);
    declineIntent.putExtra(IncomingCallActivity.EXTRA_CALLER_IP, callerIp);
    declineIntent.putExtra(IncomingCallActivity.EXTRA_CALLER_PORT, callerPort);
    // ★ Добавляем mode-параметры для консистентности (при отклонении не критично,
    //   но лучше передавать)
    declineIntent.putExtra(IncomingCallActivity.EXTRA_TARGET_MODE, targetMode);
    declineIntent.putExtra(IncomingCallActivity.EXTRA_GATEWAY_IP, gatewayIp);
    declineIntent.putExtra(IncomingCallActivity.EXTRA_GATEWAY_PORT, gatewayPort);
    declineIntent.putExtra(IncomingCallActivity.EXTRA_TARGET_FINGERPRINT, targetFingerprint);

    PendingIntent declinePi = PendingIntent.getActivity(
        context, callId.hashCode() + 1, declineIntent, flags);

    builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отклонить", declinePi);

    // Кнопка «Принять»
    PendingIntent acceptPi = PendingIntent.getActivity(
        context, callId.hashCode() + 2, incomingIntent, flags);
    builder.addAction(android.R.drawable.ic_menu_call, "Принять", acceptPi);

    Notification notif = builder.build();

    if (notificationManager != null) {
      notificationManager.notify(NOTIF_ID, notif);
      log.log("✓ Нотификация показана: callId=" + shortId(callId)
          + ", callerIp=" + callerIp + ":" + callerPort
          + ", mode=" + targetMode);
    } else {
      log.error("notificationManager == null — нотификация НЕ показана");
    }

    log.log("show() END");
  }

  /**
   * ★ ОБРАТНАЯ СОВМЕСТИМОСТЬ: старая сигнатура без режима.
   * Использует дефолтные AUTO, null, 8083, null.
   */
  @Deprecated
  public void show(String callId, String callerName, String callerIp, int callerPort,
                   String offerJson, String warning) {
    show(callId, callerName, callerIp, callerPort, offerJson, warning,
        "AUTO", null, 8083, null);
  }

  public void cancel() {
    if (notificationManager != null) {
      notificationManager.cancel(NOTIF_ID);
      log.log("cancel(): нотификация снята");
    } else {
      log.warn("cancel(): notificationManager == null");
    }
  }

  private void createChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationChannel channel = new NotificationChannel(
          CHANNEL_ID,
          "Входящие звонки Mealse",
          NotificationManager.IMPORTANCE_HIGH);  // HIGH — иначе не покажет heads-up
      channel.setDescription("Уведомления о входящих звонках");
      channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
      channel.enableVibration(true);
      channel.setBypassDnd(true);
      NotificationManager nm = context.getSystemService(NotificationManager.class);
      if (nm != null) {
        nm.createNotificationChannel(channel);
        log.log("Канал нотификаций создан: " + CHANNEL_ID);
      } else {
        log.warn("NotificationManager == null при создании канала");
      }
    } else {
      log.log("API < 26: канал не нужен");
    }
  }

  private String shortId(String id) {
    if (id == null) return "?";
    return id.length() > 8 ? id.substring(0, 8) : id;
  }
}