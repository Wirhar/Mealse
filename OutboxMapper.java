package com.my.mealse.messenger.mapper;

import com.my.mealse.messenger.data.entity.OutboxEntity;

/**
 * Маппер для OutboxEntity.
 *
 * В MVP отдельной UI-модели для outbox нет — работаем напрямую с entity
 * (это внутренняя структура, не показывается пользователю).
 * Маппер здесь — на будущее, если понадобится outbox-модель.
 */
public final class OutboxMapper {

    private OutboxMapper() {
    }

    public static OutboxEntity copy(OutboxEntity src) {
        if (src == null) return null;
        OutboxEntity e = new OutboxEntity();
        e.id = src.id;
        e.messageId = src.messageId;
        e.conversationId = src.conversationId;
        e.targetNodeKey = src.targetNodeKey;
        e.targetFingerprint = src.targetFingerprint;
        e.attemptCount = src.attemptCount;
        e.nextAttemptAt = src.nextAttemptAt;
        e.lastError = src.lastError;
        e.createdAt = src.createdAt;
        return e;
    }
}