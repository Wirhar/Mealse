package com.my.mealse.messenger.notify;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.my.mealse.R;
import com.my.mealse.messenger.model.Message;
import com.my.mealse.util.MealseLogger;

/**
 * Уведомление о входящем сообщении.
 *
 * По образцу IncomingCallNotifier:
 *   - канал MealseMessagesChannel (IMPORTANCE_HIGH)
 *   - PendingIntent → ChatActivity (класс создаётся на Шаге 8/9;
 *     здесь используем Intent по имени класса через setClassName,
 *     чтобы не было compile-time зависимости)
 *   - группировка по conversationId (setGroup)
 *   - при вызове из ChatActivity для того же conversationId — можно
 *     не показывать (проверка через ChatActivity.isConversationVisible —
 *     реализуется на Шаге 8; пока — всегда показываем)
 *
 * ★ Сейчас никто не вызывает show() — MessageReceiver будет вызывать
 *   на Шаге правки MealseApp (регистрация receiver). Класс спит.
 */
public class IncomingMessageNotifier {

    private static final String TAG = "MessengerNotif";
    private static final String CHANNEL_ID = "MealseMessagesChannel";
    private static final String CHANNEL_NAME = "Сообщения Mealse";
    private static final String GROUP_KEY = "com.my.mealse.messenger.MESSAGES";

    private static final int BASE_NOTIF_ID = 300;

    private final Context appContext;
    private final NotificationManager nm;
    private final MealseLogger log;

    public IncomingMessageNotifier(Context context) {
        this.appContext = context.getApplicationContext();
        this.nm = (NotificationManager)
            appContext.getSystemService(Context.NOTIFICATION_SERVICE);
        this.log = MealseLogger.get(appContext, TAG);
        createChannel();
    }

    /**
     * Показывает уведомление о новом сообщении.
     *
     * @param conversationId UUID диалога (для group и notifId)
     * @param conversationTitle отображаемое имя диалога
     * @param message тело сообщения (Message с body/preview)
     * @param unreadCount всего непрочитанных в этом диалоге (для summary)
     */
    public void show(String conversationId, String conversationTitle,
                     Message message, int unreadCount) {
        if (conversationId == null || message == null) {
            log.warn("show: пустые параметры");
            return;
        }
        if (nm == null) {
            log.warn("show: NotificationManager == null");
            return;
        }

        try {
            // PendingIntent → ChatActivity (по имени класса, без compile-time связи)
            Intent chatIntent = new Intent();
            chatIntent.setClassName(appContext, "com.my.mealse.messenger.ui.ChatActivity");
            chatIntent.putExtra("conversation_id", conversationId);
            chatIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                piFlags |= PendingIntent.FLAG_IMMUTABLE;
            }
            int notifId = BASE_NOTIF_ID + Math.abs(conversationId.hashCode() % 1000);
            PendingIntent pi = PendingIntent.getActivity(
                appContext, notifId, chatIntent, piFlags);

            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                b = new Notification.Builder(appContext, CHANNEL_ID);
            } else {
                b = new Notification.Builder(appContext);
            }

            String title = (conversationTitle != null && !conversationTitle.isEmpty())
                ? conversationTitle
                : "Сообщение";
            String text = message.getPreview();

            b.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_call) // временно — иконка звонка
                .setPriority(Notification.PRIORITY_HIGH)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setGroup(GROUP_KEY);

            if (unreadCount > 1) {
                b.setSubText("+" + (unreadCount - 1) + " ещё");
            }

            // Summary-уведомление (группировка)
            if (unreadCount > 1) {
                Notification.Builder summary;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    summary = new Notification.Builder(appContext, CHANNEL_ID);
                } else {
                    summary = new Notification.Builder(appContext);
                }
                summary.setContentTitle("Mealse")
                    .setContentText("Новые сообщения: " + unreadCount)
                    .setSmallIcon(R.drawable.ic_call)
                    .setGroup(GROUP_KEY)
                    .setGroupSummary(true)
                    .setAutoCancel(true);

                nm.notify(GROUP_KEY.hashCode(), summary.build());
            }

            nm.notify(notifId, b.build());
            log.log("show: conv=" + shortId(conversationId)
                + ", unread=" + unreadCount + ", preview=" + text);

        } catch (Exception e) {
            log.logError("show", e);
        }
    }

    public void cancel(String conversationId) {
        if (conversationId == null || nm == null) return;
        try {
            int notifId = BASE_NOTIF_ID + Math.abs(conversationId.hashCode() % 1000);
            nm.cancel(notifId);
        } catch (Exception ignored) {
        }
    }

    public void cancelAll() {
        if (nm == null) return;
        try {
            nm.cancelAll();
        } catch (Exception ignored) {
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription("Уведомления о входящих сообщениях Mealse");
                channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
                channel.enableVibration(true);
                if (nm != null) {
                    nm.createNotificationChannel(channel);
                }
            } catch (Exception e) {
                log.logError("createChannel", e);
            }
        }
    }

    private static String shortId(String id) {
        if (id == null) return "?";
        return id.length() > 8 ? id.substring(0, 8) : id;
    }
}