package com.my.mealse.messenger.model;

/**
 * Роль участника в диалоге.
 *
 * OWNER  — создатель группы / инициатор звонка.
 * MEMBER — приглашённый участник.
 *
 * Для PRIVATE / CALL роль не используется (всегда MEMBER, кроме себя — OWNER).
 */
public enum ParticipantRole {

    OWNER,
    MEMBER;

    public static ParticipantRole fromString(String s) {
        if (s == null || s.isEmpty()) return MEMBER;
        try {
            return ParticipantRole.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return MEMBER;
        }
    }

    public String toStorageString() {
        return this.name();
    }
}