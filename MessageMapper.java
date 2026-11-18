package com.my.mealse.messenger.mapper;

import com.my.mealse.messenger.data.entity.MessageEntity;
import com.my.mealse.messenger.model.Message;
import com.my.mealse.messenger.model.MessageStatus;
import com.my.mealse.messenger.model.MessageType;

public final class MessageMapper {

    private MessageMapper() {
    }

    public static Message toModel(MessageEntity e) {
        if (e == null) return null;
        Message m = new Message();
        m.setId(e.id);
        m.setConversationId(e.conversationId);
        m.setSenderFingerprint(e.senderFingerprint);
        m.setSenderDeviceId(e.senderDeviceId);
        m.setSenderNodeKey(e.senderNodeKey);
        m.setType(MessageType.fromString(e.type));
        m.setBody(e.body);
        m.setEncryptedBody(e.encryptedBody);
        m.setAttachmentUri(e.attachmentUri);
        m.setAttachmentName(e.attachmentName);
        m.setAttachmentMime(e.attachmentMime);
        m.setAttachmentSize(e.attachmentSize);
        m.setCreatedAt(e.createdAt);
        m.setStatus(MessageStatus.fromString(e.status));
        m.setDeliveredAt(e.deliveredAt);
        m.setReadAt(e.readAt);
        m.setReplyToMessageId(e.replyToMessageId);
        return m;
    }

    public static MessageEntity toEntity(Message m) {
        if (m == null) return null;
        MessageEntity e = new MessageEntity();
        e.id = m.getId();
        e.conversationId = m.getConversationId();
        e.senderFingerprint = m.getSenderFingerprint();
        e.senderDeviceId = m.getSenderDeviceId();
        e.senderNodeKey = m.getSenderNodeKey();
        e.type = (m.getType() != null ? m.getType() : MessageType.TEXT).toStorageString();
        e.body = m.getBody();
        e.encryptedBody = m.getEncryptedBody();
        e.attachmentUri = m.getAttachmentUri();
        e.attachmentName = m.getAttachmentName();
        e.attachmentMime = m.getAttachmentMime();
        e.attachmentSize = m.getAttachmentSize();
        e.createdAt = m.getCreatedAt();
        e.status = (m.getStatus() != null ? m.getStatus() : MessageStatus.PENDING).toStorageString();
        e.deliveredAt = m.getDeliveredAt();
        e.readAt = m.getReadAt();
        e.replyToMessageId = m.getReplyToMessageId();
        return e;
    }
}