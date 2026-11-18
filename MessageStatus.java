package com.my.mealse.messenger.model;

/**
 * Статус сообщения.
 *
 * PENDING   — создано локально, ждёт отправки (в outbox).
 * SENT      — отправлено (HTTP 200 / Wi-Fi Direct write OK).
 * DELIVERED — получатель подтвердил доставку (kind=DELIVERED).
 * READ      — получатель прочитал (kind=READ).
 * FAILED    — исчерпаны попытки / истёк срок (7 дней).
 *
 * Для входящих сообщений статус сразу DELIVERED (получили) → READ (открыли чат).
 */
public enum MessageStatus {

    PENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED;

    public static MessageStatus fromString(String s) {
        if (s == null || s.isEmpty()) return PENDING;
        try {
            return MessageStatus.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return PENDING;
        }
    }

    public String toStorageString() {
        return this.name();
    }

    /** Можно ли ещё пытаться отправить. */
    public boolean isRetryable() {
        return this == PENDING || this == FAILED;
    }

    /** Финальный статус (дальше не меняется). */
    public boolean isFinal() {
        return this == DELIVERED || this == READ;
    }
}