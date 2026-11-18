package com.my.mealse.messenger.data.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Участник диалога.
 *
 * Для PRIVATE — 2 записи (я + собеседник).
 * Для GROUP   — все участники.
 * Для CALL    — 1 запись (собеседник).
 *
 * nodeKey — "ip:port" на момент добавления (ссылка на TrustedNode).
 * fingerprint / deviceId — для идентификации, если IP сменится.
 */
@Entity(
    tableName = "participants",
    foreignKeys = @ForeignKey(
        entity = ConversationEntity.class,
        parentColumns = "id",
        childColumns = "conversation_id",
        onDelete = ForeignKey.CASCADE
    ),
    indices = {
        @Index(value = "conversation_id"),
        @Index(value = "fingerprint")
    }
)
public class ParticipantEntity {

    public static final String ROLE_OWNER  = "OWNER";
    public static final String ROLE_MEMBER = "MEMBER";

    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "id")
    public String id;

    @NonNull
    @ColumnInfo(name = "conversation_id")
    public String conversationId;

    @Nullable
    @ColumnInfo(name = "node_key")
    public String nodeKey;

    @Nullable
    @ColumnInfo(name = "fingerprint")
    public String fingerprint;

    @Nullable
    @ColumnInfo(name = "device_id")
    public String deviceId;

    @Nullable
    @ColumnInfo(name = "display_name")
    public String displayName;

    @NonNull
    @ColumnInfo(name = "role")
    public String role;

    @ColumnInfo(name = "joined_at")
    public long joinedAt;

    public ParticipantEntity() {
        // для Room
    }
}