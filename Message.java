package com.my.mealse.messenger.model;

import java.util.Objects;

/**
 * Сообщение для UI и репозиториев.
 *
 * encryptedBody — BLOB (iv + ciphertext). В MVP = null, body = plaintext.
 * Когда включим E2E — body = null, encryptedBody != null.
 *
 * equals/hashCode — по id.
 */
public class Message {

    private String id;
    private String conversationId;
    private String senderFingerprint;
    private String senderDeviceId;
    private String senderNodeKey;
    private MessageType type;
    private String body;
    private byte[] encryptedBody;
    private String attachmentUri;
    private String attachmentName;
    private String attachmentMime;
    private long attachmentSize;
    private long createdAt;
    private MessageStatus status;
    private long deliveredAt;
    private long readAt;
    private String replyToMessageId;

    public Message() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getSenderFingerprint() {
        return senderFingerprint;
    }

    public void setSenderFingerprint(String senderFingerprint) {
        this.senderFingerprint = senderFingerprint;
    }

    public String getSenderDeviceId() {
        return senderDeviceId;
    }

    public void setSenderDeviceId(String senderDeviceId) {
        this.senderDeviceId = senderDeviceId;
    }

    public String getSenderNodeKey() {
        return senderNodeKey;
    }

    public void setSenderNodeKey(String senderNodeKey) {
        this.senderNodeKey = senderNodeKey;
    }

    public MessageType getType() {
        return type;
    }

    public void setType(MessageType type) {
        this.type = type;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public byte[] getEncryptedBody() {
        return encryptedBody;
    }

    public void setEncryptedBody(byte[] encryptedBody) {
        this.encryptedBody = encryptedBody;
    }

    public String getAttachmentUri() {
        return attachmentUri;
    }

    public void setAttachmentUri(String attachmentUri) {
        this.attachmentUri = attachmentUri;
    }

    public String getAttachmentName() {
        return attachmentName;
    }

    public void setAttachmentName(String attachmentName) {
        this.attachmentName = attachmentName;
    }

    public String getAttachmentMime() {
        return attachmentMime;
    }

    public void setAttachmentMime(String attachmentMime) {
        this.attachmentMime = attachmentMime;
    }

    public long getAttachmentSize() {
        return attachmentSize;
    }

    public void setAttachmentSize(long attachmentSize) {
        this.attachmentSize = attachmentSize;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public MessageStatus getStatus() {
        return status;
    }

    public void setStatus(MessageStatus status) {
        this.status = status;
    }

    public long getDeliveredAt() {
        return deliveredAt;
    }

    public void setDeliveredAt(long deliveredAt) {
        this.deliveredAt = deliveredAt;
    }

    public long getReadAt() {
        return readAt;
    }

    public void setReadAt(long readAt) {
        this.readAt = readAt;
    }

    public String getReplyToMessageId() {
        return replyToMessageId;
    }

    public void setReplyToMessageId(String replyToMessageId) {
        this.replyToMessageId = replyToMessageId;
    }

    /** Для превью в списке диалогов. */
    public String getPreview() {
        if (type == MessageType.SYSTEM) {
            return body != null ? body : "(служебное)";
        }
        if (type == MessageType.IMAGE) {
            return "📷 Фото" + (body != null && !body.isEmpty() ? ": " + body : "");
        }
        if (type == MessageType.FILE) {
            String name = attachmentName != null ? attachmentName : "файл";
            return "📎 " + name;
        }
        if (body == null || body.isEmpty()) {
            return "(пусто)";
        }
        String trimmed = body.replace('\n', ' ').trim();
        return trimmed.length() > 80 ? trimmed.substring(0, 77) + "..." : trimmed;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Message that = (Message) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Message{" + "id='" + id + '\'' + ", conv='" + conversationId + '\''
            + ", type=" + type + ", status=" + status + ", createdAt=" + createdAt + '}';
    }
}