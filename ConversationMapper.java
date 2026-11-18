package com.my.mealse.messenger.mapper;

import com.my.mealse.messenger.data.entity.ConversationEntity;
import com.my.mealse.messenger.model.Conversation;
import com.my.mealse.messenger.model.ConversationType;

/**
 * Маппер ConversationEntity ↔ Conversation.
 * Без логирования (п.4 правил проекта).
 */
public final class ConversationMapper {

    private ConversationMapper() {
    }

    public static Conversation toModel(ConversationEntity e) {
        if (e == null) return null;
        Conversation c = new Conversation();
        c.setId(e.id);
        c.setType(ConversationType.fromString(e.type));
        c.setTitle(e.title);
        c.setCreatedAt(e.createdAt);
        c.setUpdatedAt(e.updatedAt);
        c.setLastMessageId(e.lastMessageId);
        c.setLastMessagePreview(e.lastMessagePreview);
        c.setLastMessageAt(e.lastMessageAt);
        c.setUnreadCount(e.unreadCount);
        c.setPeerFingerprint(e.peerFingerprint);
        c.setCallId(e.callId);
        return c;
    }

    public static ConversationEntity toEntity(Conversation c) {
        if (c == null) return null;
        ConversationEntity e = new ConversationEntity();
        e.id = c.getId();
        e.type = (c.getType() != null ? c.getType() : ConversationType.PRIVATE).toStorageString();
        e.title = c.getTitle();
        e.createdAt = c.getCreatedAt();
        e.updatedAt = c.getUpdatedAt();
        e.lastMessageId = c.getLastMessageId();
        e.lastMessagePreview = c.getLastMessagePreview();
        e.lastMessageAt = c.getLastMessageAt();
        e.unreadCount = c.getUnreadCount();
        e.peerFingerprint = c.getPeerFingerprint();
        e.callId = c.getCallId();
        return e;
    }
}