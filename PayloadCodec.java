package com.my.mealse.messenger.crypto;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

/**
 * Кодек MessagePayload ↔ JSON.
 *
 * В MVP:
 *   encode(payload) → JSON-строка (кладётся в Message.body как plaintext)
 *   decode(json)    → MessagePayload
 *
 * В будущем (E2E):
 *   encode(payload) → JSON-строка → MessageCipher.encrypt() → BLOB
 *   BLOB → MessageCipher.decrypt() → JSON-строка → decode() → payload
 *
 * Gson без pretty-printing — экономим байты.
 * null-поля не сериализуются (Gson по умолчанию), чтобы JSON был компактнее.
 */
public final class PayloadCodec {

    private static final Gson GSON = new GsonBuilder()
        .disableHtmlEscaping()   // не экранируем < > & — для чата не нужно
        .create();

    private PayloadCodec() {
    }

    /**
     * Сериализует payload в JSON.
     * @return JSON или null, если payload == null.
     */
    @Nullable
    public static String encode(@Nullable MessagePayload payload) {
        if (payload == null) return null;
        try {
            return GSON.toJson(payload);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Парсит JSON в payload.
     * @return payload или null, если json пустой/битый.
     */
    @Nullable
    public static MessagePayload decode(@Nullable String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            return GSON.fromJson(json, MessagePayload.class);
        } catch (JsonSyntaxException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Быстрая проверка, что JSON похож на payload (есть kind).
     * Не заменяет decode, но помогает в дедупликации/фильтрации.
     */
    public static boolean looksLikePayload(@Nullable String json) {
        if (json == null || json.isEmpty()) return false;
        return json.contains("\"kind\"");
    }

    /**
     * Создаёт payload для текстового сообщения.
     * Удобный хелпер для MessageCommands (Шаг 4) и MessageReceiver (Шаг 6).
     */
    public static MessagePayload text(String messageId, String body, long sentAt,
                                      @Nullable String replyTo) {
        MessagePayload p = new MessagePayload();
        p.kind = MessagePayload.KIND_TEXT;
        p.messageId = messageId;
        p.body = body;
        p.sentAt = sentAt;
        p.replyTo = replyTo;
        return p;
    }

    /**
     * Создаёт payload для системного сообщения (например, «создан чат»).
     */
    public static MessagePayload system(String messageId, String body, long sentAt) {
        MessagePayload p = new MessagePayload();
        p.kind = MessagePayload.KIND_SYSTEM;
        p.messageId = messageId;
        p.body = body;
        p.sentAt = sentAt;
        return p;
    }

    /**
     * Создаёт payload-подтверждение доставки (DELIVERED) для messageId.
     */
    public static MessagePayload deliveredAck(String messageId, long sentAt) {
        MessagePayload p = new MessagePayload();
        p.kind = MessagePayload.KIND_DELIVERED;
        p.messageId = messageId;
        p.sentAt = sentAt;
        return p;
    }

    /**
     * Создаёт payload-подтверждение прочтения (READ) для messageId.
     */
    public static MessagePayload readAck(String messageId, long sentAt) {
        MessagePayload p = new MessagePayload();
        p.kind = MessagePayload.KIND_READ;
        p.messageId = messageId;
        p.sentAt = sentAt;
        return p;
    }
}