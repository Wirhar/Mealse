package com.my.mealse.messenger.data.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Диалог (приватный, групповой или чат в звонке).
 *
 * type:
 *   PRIVATE — 1-на-1 с одним узлом
 *   GROUP   — групповой чат (несколько участников)
 *   CALL    — чат во время звонка (привязан к callId)
 *
 * lastMessage* — денормализация для быстрого списка диалогов.
 * unreadCount — счётчик непрочитанных.
 */
@Entity(
    tableName = "conversations",
    indices = {
        @Index(value = "updated_at"),
        @Index(value = "call_id", unique = true)
    }
)
public class ConversationEntity {

    public static final String TYPE_PRIVATE = "PRIVATE";
    public static final String TYPE_GROUP   = "GROUP";
    public static final String TYPE_CALL    = "CALL";

    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "id")
    public String id;

    @NonNull
    @ColumnInfo(name = "type")
    public String type;

    @Nullable
    @ColumnInfo(name = "title")
    public String title;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "updated_at")
    public long updatedAt;

    @Nullable
    @ColumnInfo(name = "last_message_id")
    public String lastMessageId;

    @Nullable
    @ColumnInfo(name = "last_message_preview")
    public String lastMessagePreview;

    @ColumnInfo(name = "last_message_at")
    public long lastMessageAt;

    @ColumnInfo(name = "unread_count")
    public int unreadCount;

    /** Для PRIVATE — fingerprint собеседника. */
    @Nullable
    @ColumnInfo(name = "peer_fingerprint")
    public String peerFingerprint;

    /** Для CALL — callId звонка (уникальный индекс). */
    @Nullable
    @ColumnInfo(name = "call_id")
    public String callId;

    public ConversationEntity() {
        // для Room
    }
}