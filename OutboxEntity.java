package com.my.mealse.messenger.data.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Очередь на отправку (офлайн-доставка).
 *
 * Одна запись = попытка доставить одно сообщение одному узлу.
 * Для группового чата — N записей на N участников (тот же messageId).
 *
 * nextAttemptAt — backoff (2с, 5с, 15с, 60с, 5мин, 15мин, 1ч, 6ч; кап 24ч).
 * attemptCount — сколько раз пытались.
 *
 * FK на Message с CASCADE — при удалении сообщения очередь чистится.
 * FK на Conversation НЕ делаем — conversationId денормализован,
 * но целостность обеспечивается через Message.
 */
@Entity(
    tableName = "outbox",
    foreignKeys = @ForeignKey(
        entity = MessageEntity.class,
        parentColumns = "id",
        childColumns = "message_id",
        onDelete = ForeignKey.CASCADE
    ),
    indices = {
        @Index(value = "message_id"),
        @Index(value = "next_attempt_at"),
        @Index(value = "target_node_key")
    }
)
public class OutboxEntity {

    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "id")
    public String id;

    @NonNull
    @ColumnInfo(name = "message_id")
    public String messageId;

    @NonNull
    @ColumnInfo(name = "conversation_id")
    public String conversationId;

    /** "ip:port" получателя. */
    @NonNull
    @ColumnInfo(name = "target_node_key")
    public String targetNodeKey;

    @Nullable
    @ColumnInfo(name = "target_fingerprint")
    public String targetFingerprint;

    @ColumnInfo(name = "attempt_count")
    public int attemptCount;

    @ColumnInfo(name = "next_attempt_at")
    public long nextAttemptAt;

    @Nullable
    @ColumnInfo(name = "last_error")
    public String lastError;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    public OutboxEntity() {
        // для Room
    }
}