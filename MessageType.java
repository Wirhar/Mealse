package com.my.mealse.messenger.model;

/**
 * Тип сообщения.
 *
 * TEXT   — обычный текст (MVP).
 * IMAGE  — картинка (attachmentUri).
 * FILE   — файл (attachmentUri + name/mime/size).
 * SYSTEM — служебное (например, «создан групповой чат», «добавлен участник»).
 */
public enum MessageType {

    TEXT,
    IMAGE,
    FILE,
    SYSTEM;

    public static MessageType fromString(String s) {
        if (s == null || s.isEmpty()) return TEXT;
        try {
            return MessageType.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return TEXT;
        }
    }

    public String toStorageString() {
        return this.name();
    }
}