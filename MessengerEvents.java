package com.my.mealse.messenger;

import android.content.Context;
import android.content.Intent;

/**
 * Константы broadcast-событий мессенджера.
 *
 * MESSAGE_NEW            — новое сообщение (входящее или созданное локально).
 *                          extras: conversation_id, message_id
 * MESSAGE_STATUS_CHANGED — статус сообщения изменился (SENT→DELIVERED→READ).
 *                          extras: conversation_id, message_id, status
 * CONVERSATION_CHANGED   — диалог изменился (новое сообщение, unread, lastMessage).
 *                          extras: conversation_id
 * MESSAGE_PROGRESS       — ★ Улучшение №3: прогресс отправки FILE.
 *                          extras: conversation_id, message_id, percent (0..100)
 *
 * Все broadcast'ы — только внутри приложения (setPackage).
 */
public final class MessengerEvents {

    public static final String ACTION_MESSAGE_NEW =
        "com.my.mealse.messenger.MESSAGE_NEW";

    public static final String ACTION_MESSAGE_STATUS_CHANGED =
        "com.my.mealse.messenger.MESSAGE_STATUS_CHANGED";

    public static final String ACTION_CONVERSATION_CHANGED =
        "com.my.mealse.messenger.CONVERSATION_CHANGED";

    /** ★ Улучшение №3: прогресс отправки файла. */
    public static final String ACTION_MESSAGE_PROGRESS =
        "com.my.mealse.messenger.MESSAGE_PROGRESS";

    public static final String EXTRA_CONVERSATION_ID = "conversation_id";
    public static final String EXTRA_MESSAGE_ID = "message_id";
    public static final String EXTRA_STATUS = "status";
    /** ★ Улучшение №3: 0..100. */
    public static final String EXTRA_PERCENT = "percent";

    private MessengerEvents() {
    }

    public static void sendMessageNew(Context context, String conversationId, String messageId) {
        try {
            Intent i = new Intent(ACTION_MESSAGE_NEW);
            i.putExtra(EXTRA_CONVERSATION_ID, conversationId);
            i.putExtra(EXTRA_MESSAGE_ID, messageId);
            i.setPackage(context.getPackageName());
            context.sendBroadcast(i);
        } catch (Exception ignored) {
        }
    }

    public static void sendMessageStatusChanged(Context context, String conversationId,
                                                 String messageId, String status) {
        try {
            Intent i = new Intent(ACTION_MESSAGE_STATUS_CHANGED);
            i.putExtra(EXTRA_CONVERSATION_ID, conversationId);
            i.putExtra(EXTRA_MESSAGE_ID, messageId);
            i.putExtra(EXTRA_STATUS, status);
            i.setPackage(context.getPackageName());
            context.sendBroadcast(i);
        } catch (Exception ignored) {
        }
    }

    public static void sendConversationChanged(Context context, String conversationId) {
        try {
            Intent i = new Intent(ACTION_CONVERSATION_CHANGED);
            i.putExtra(EXTRA_CONVERSATION_ID, conversationId);
            i.setPackage(context.getPackageName());
            context.sendBroadcast(i);
        } catch (Exception ignored) {
        }
    }

    /**
     * ★ Улучшение №3: прогресс отправки FILE (0..100).
     * Шлётся DeliveryScheduler'ом из ProgressCallback FileMessageSender
     * (не чаще 10 раз в секунду).
     */
    public static void sendMessageProgress(Context context, String conversationId,
                                            String messageId, int percent) {
        try {
            Intent i = new Intent(ACTION_MESSAGE_PROGRESS);
            i.putExtra(EXTRA_CONVERSATION_ID, conversationId);
            i.putExtra(EXTRA_MESSAGE_ID, messageId);
            i.putExtra(EXTRA_PERCENT, percent);
            i.setPackage(context.getPackageName());
            context.sendBroadcast(i);
        } catch (Exception ignored) {
        }
    }
}