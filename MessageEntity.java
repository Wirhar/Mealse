package com.my.mealse.messenger.data.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Сообщение.
 *
 * type: TEXT | IMAGE | FILE | SYSTEM
 * status: PENDING | SENT | DELIVERED | READ | FAILED
 *
 * encryptedBody — BLOB (iv + ciphertext) для будущего E2E.
 * В MVP = null, body = plaintext.
 *
 * senderNodeKey — "ip:port" на момент отправки (историческая запись).
 */
@Entity(
    tableName = "messages",
    foreignKeys = @ForeignKey(
        entity = ConversationEntity.class,
        parentColumns = "id",
        childColumns = "conversation_id",
        onDelete = ForeignKey.CASCADE
    ),
    indices = {
        @Index(value = {"conversation_id", "created_at"}),
        @Index(value = {"conversation_id", "status"}),
        @Index(value = "sender_fingerprint")
    }
)
public class MessageEntity {

    public static final String TYPE_TEXT   = "TEXT";
    public static final String TYPE_IMAGE  = "IMAGE";
    public static final String TYPE_FILE   = "FILE";
    public static final String TYPE_SYSTEM = "SYSTEM";

    public static final String STATUS_PENDING   = "PENDING";
    public static final String STATUS_SENT      = "SENT";
    public static final String STATUS_DELIVERED = "DELIVERED";
    public static final String STATUS_READ      = "READ";
    public static final String STATUS_FAILED    = "FAILED";

    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "id")
    public String id;

    @NonNull
    @ColumnInfo(name = "conversation_id")
    public String conversationId;

    @Nullable
    @ColumnInfo(name = "sender_fingerprint")
    public String senderFingerprint;

    @Nullable
    @ColumnInfo(name = "sender_device_id")
    public String senderDeviceId;

    @Nullable
    @ColumnInfo(name = "sender_node_key")
    public String senderNodeKey;

    @NonNull
    @ColumnInfo(name = "type")
    public String type;

    /** Текст сообщения (MVP). Для будущего E2E — null, если encryptedBody != null. */
    @Nullable
    @ColumnInfo(name = "body")
    public String body;

    /** Шифротекст (iv + ciphertext). В MVP = null. */
    @Nullable
    @ColumnInfo(name = "encrypted_body", typeAffinity = ColumnInfo.BLOB)
    public byte[] encryptedBody;

    @Nullable
    @ColumnInfo(name = "attachment_uri")
    public String attachmentUri;

    @Nullable
    @ColumnInfo(name = "attachment_name")
    public String attachmentName;

    @Nullable
    @ColumnInfo(name = "attachment_mime")
    public String attachmentMime;

    @ColumnInfo(name = "attachment_size")
    public long attachmentSize;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @NonNull
    @ColumnInfo(name = "status")
    public String status;

    @ColumnInfo(name = "delivered_at")
    public long deliveredAt;

    @ColumnInfo(name = "read_at")
    public long readAt;

    @Nullable
    @ColumnInfo(name = "reply_to_message_id")
    public String replyToMessageId;

    public MessageEntity() {
        // для Room
    }
}