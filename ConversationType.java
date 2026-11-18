package com.my.mealse.messenger.model;

/**
 * Тип диалога.
 *
 * PRIVATE — 1-на-1 с одним узлом.
 * GROUP   — групповой чат (несколько участников, рассылка per-participant).
 * CALL    — чат во время звонка (привязан к callId, транспорт — сигналинг звонка).
 */
public enum ConversationType {

    PRIVATE,
    GROUP,
    CALL;

    public static ConversationType fromString(String s) {
        if (s == null || s.isEmpty()) return PRIVATE;
        try {
            return ConversationType.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return PRIVATE;
        }
    }

    public String toStorageString() {
        return this.name();
    }
}