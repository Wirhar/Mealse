package com.my.mealse.messenger.model;

import java.util.Objects;

/**
 * Диалог для UI и репозиториев.
 *
 * Не содержит Room-аннотаций. Маппится из ConversationEntity.
 * equals/hashCode — по id.
 */
public class Conversation {

    private String id;
    private ConversationType type;
    private String title;
    private long createdAt;
    private long updatedAt;
    private String lastMessageId;
    private String lastMessagePreview;
    private long lastMessageAt;
    private int unreadCount;
    private String peerFingerprint;
    private String callId;

    public Conversation() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public ConversationType getType() {
        return type;
    }

    public void setType(ConversationType type) {
        this.type = type;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getLastMessageId() {
        return lastMessageId;
    }

    public void setLastMessageId(String lastMessageId) {
        this.lastMessageId = lastMessageId;
    }

    public String getLastMessagePreview() {
        return lastMessagePreview;
    }

    public void setLastMessagePreview(String lastMessagePreview) {
        this.lastMessagePreview = lastMessagePreview;
    }

    public long getLastMessageAt() {
        return lastMessageAt;
    }

    public void setLastMessageAt(long lastMessageAt) {
        this.lastMessageAt = lastMessageAt;
    }

    public int getUnreadCount() {
        return unreadCount;
    }

    public void setUnreadCount(int unreadCount) {
        this.unreadCount = unreadCount;
    }

    public String getPeerFingerprint() {
        return peerFingerprint;
    }

    public void setPeerFingerprint(String peerFingerprint) {
        this.peerFingerprint = peerFingerprint;
    }

    public String getCallId() {
        return callId;
    }

    public void setCallId(String callId) {
        this.callId = callId;
    }

    /** Отображаемое имя для списка диалогов. */
    public String getDisplayTitle() {
        if (title != null && !title.isEmpty()) return title;
        if (type == ConversationType.CALL && callId != null) {
            return "Звонок " + (callId.length() > 8 ? callId.substring(0, 8) : callId);
        }
        return "(без названия)";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Conversation that = (Conversation) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Conversation{" + "id='" + id + '\'' + ", type=" + type + ", title='" + title + '\''
            + ", unread=" + unreadCount + ", lastMessageAt=" + lastMessageAt + '}';
    }
}