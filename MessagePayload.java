package com.my.mealse.messenger.crypto;

import androidx.annotation.Nullable;

/**
 * «Внутренний» payload сообщения — то, что кладётся в ciphertext
 * (в будущем, при E2E) или сериализуется как есть (в MVP).
 *
 * Отдельно от MessageEnvelope (обёртка с from/to/conversation_id/...),
 * потому что envelope виден серверу/получателю в открытом виде
 * (нужен для маршрутизации), а payload — конфиденциален.
 *
 * Поля:
 *   kind    — "TEXT" | "IMAGE" | "FILE" | "SYSTEM" | "DELIVERED" | "READ"
 *   body    — текст (для TEXT/SYSTEM) или подпись к файлу/фото
 *   replyTo — id сообщения, на которое отвечаем (null если нет)
 *   sentAt  — timestamp отправки (секунды, как в сигналинге)
 *   messageId — id сообщения (дублирует envelope.message_id, но нужен внутри
 *               ciphertext, чтобы получатель не мог подменить)
 *   attachmentName / attachmentMime / attachmentSize — для FILE/IMAGE
 *   attachmentDataBase64 — ★ legacy (WD-канал / старая версия): base64 контента
 *   localPath          — ★ НОВОЕ (Шаг 16.8.6): путь к tmp-файлу на получателе
 *                        (бинарный /signal/file). Приоритетнее attachmentDataBase64.
 */
public class MessagePayload {

    /** TEXT / IMAGE / FILE / SYSTEM — совпадает с MessageType. */
    public static final String KIND_TEXT = "TEXT";
    public static final String KIND_IMAGE = "IMAGE";
    public static final String KIND_FILE = "FILE";
    public static final String KIND_SYSTEM = "SYSTEM";

    /** Подтверждения доставки/прочтения — тоже через payload. */
    public static final String KIND_DELIVERED = "DELIVERED";
    public static final String KIND_READ = "READ";

    public String kind;
    public String body;
    public String replyTo;
    public long sentAt;
    public String messageId;

    // Для FILE/IMAGE (в MVP файлы не отправляем, но поля есть)
    public String attachmentName;
    public String attachmentMime;
    public long attachmentSize;

    /**
     * ★ Legacy (WD-канал / старая версия): base64-контент вложения.
     * Используется только если нет localPath (HTTP-приём через /signal/file).
     */
    public String attachmentDataBase64;

    /**
     * ★ НОВОЕ (Шаг 16.8.6): путь к tmp-файлу на получателе (бинарный /signal/file).
     * Если задан — MessageReceiver перемещает файл в AttachmentStorage
     * и удаляет tmp. Приоритетнее attachmentDataBase64.
     *
     * Заполняется ServerService.handleFileRoute после стриминга body
     * в getCacheDir()/incoming/<msgId>_<name>.
     */
    public String localPath;

    public MessagePayload() {
    }

    public boolean isText() {
        return KIND_TEXT.equals(kind);
    }

    public boolean isSystem() {
        return KIND_SYSTEM.equals(kind);
    }

    public boolean isDeliveredAck() {
        return KIND_DELIVERED.equals(kind);
    }

    public boolean isReadAck() {
        return KIND_READ.equals(kind);
    }

    public boolean isAck() {
        return isDeliveredAck() || isReadAck();
    }

    @Nullable
    public String getBodyOrNull() {
        return (body != null && !body.isEmpty()) ? body : null;
    }

    @Override
    public String toString() {
        return "MessagePayload{kind='" + kind + '\''
            + ", body=" + (body != null ? "'" + body.length() + " chars'" : "null")
            + ", replyTo='" + replyTo + '\''
            + ", sentAt=" + sentAt
            + ", messageId='" + messageId + '\''
            + ", attachmentName='" + attachmentName + '\''
            + ", attachmentSize=" + attachmentSize
            + ", hasBase64=" + (attachmentDataBase64 != null && !attachmentDataBase64.isEmpty())
            + ", localPath='" + localPath + '\'' + '}';
    }
}