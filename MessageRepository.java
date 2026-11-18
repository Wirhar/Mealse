package com.my.mealse.messenger.repo;

import android.content.Context;

import androidx.annotation.Nullable;

import com.my.mealse.messenger.data.DatabaseHolder;
import com.my.mealse.messenger.data.dao.MessageDao;
import com.my.mealse.messenger.data.entity.MessageEntity;
import com.my.mealse.messenger.mapper.MessageMapper;
import com.my.mealse.messenger.model.Message;
import com.my.mealse.messenger.model.MessageStatus;
import com.my.mealse.util.MealseLogger;

import java.util.ArrayList;
import java.util.List;

/**
 * Репозиторий сообщений. Синхронные Room-операции.
 */
public class MessageRepository {

    private static final String TAG = "MessengerMsgRepo";

    private final MessageDao dao;
    private final MealseLogger log;

    public MessageRepository(Context context) {
        Context appCtx = context.getApplicationContext();
        this.dao = DatabaseHolder.get(appCtx).messageDao();
        this.log = MealseLogger.get(appCtx, TAG);
    }

    /**
     * Вставляет сообщение. IGNORE при конфликте — дедупликация встроена.
     * @return true если вставлено (не было раньше), false если уже было.
     */
    public boolean add(Message message) {
        if (message == null || message.getId() == null) return false;
        long rowId = dao.insert(MessageMapper.toEntity(message));
        boolean inserted = rowId > 0;
        if (!inserted) {
            log.warn("add: сообщение уже существует id=" + message.getId());
        }
        return inserted;
    }

    @Nullable
    public Message getById(String id) {
        if (id == null) return null;
        return MessageMapper.toModel(dao.getById(id));
    }

    public boolean exists(String id) {
        if (id == null) return false;
        return dao.exists(id) > 0;
    }

    /**
     * Постраничная загрузка (для ChatActivity, reverse-layout: новые сверху).
     * @param limit размер страницы
     * @param offset сколько пропустить (0 — самые новые)
     */
    public List<Message> getByConversation(String conversationId, int limit, int offset) {
        if (conversationId == null) return new ArrayList<>();
        if (limit <= 0) limit = 50;
        if (offset < 0) offset = 0;
        List<MessageEntity> entities = dao.getByConversation(conversationId, limit, offset);
        List<Message> result = new ArrayList<>(entities.size());
        for (MessageEntity e : entities) {
            result.add(MessageMapper.toModel(e));
        }
        return result;
    }

    public List<Message> getAllByConversation(String conversationId) {
        if (conversationId == null) return new ArrayList<>();
        List<MessageEntity> entities = dao.getAllByConversation(conversationId);
        List<Message> result = new ArrayList<>(entities.size());
        for (MessageEntity e : entities) {
            result.add(MessageMapper.toModel(e));
        }
        return result;
    }

    @Nullable
    public Message getLastByConversation(String conversationId) {
        if (conversationId == null) return null;
        return MessageMapper.toModel(dao.getLastByConversation(conversationId));
    }

    public int countByStatus(String conversationId, MessageStatus status) {
        if (conversationId == null || status == null) return 0;
        return dao.countByStatus(conversationId, status.toStorageString());
    }

    // ============================================================
    // Статусы
    // ============================================================

    public void markSent(String messageId) {
        if (messageId == null) return;
        dao.updateStatus(messageId, MessageStatus.SENT.toStorageString());
    }

    public void markDelivered(String messageId, long ts) {
        if (messageId == null) return;
        dao.updateStatusDelivered(messageId, MessageStatus.DELIVERED.toStorageString(), ts);
    }

    public void markRead(String messageId, long ts) {
        if (messageId == null) return;
        dao.updateStatusRead(messageId, MessageStatus.READ.toStorageString(), ts);
    }

    public void markFailed(String messageId) {
        if (messageId == null) return;
        dao.updateStatus(messageId, MessageStatus.FAILED.toStorageString());
    }

    /**
     * Условное обновление: только если текущий статус == expectedCurrent.
     * Нужно, чтобы DELIVERED не «откатывался» обратно в SENT.
     */
    public void markIfCurrent(String messageId, MessageStatus newStatus,
                              MessageStatus expectedCurrent) {
        if (messageId == null || newStatus == null || expectedCurrent == null) return;
        dao.updateStatusIfCurrent(messageId,
            newStatus.toStorageString(), expectedCurrent.toStorageString());
    }

    // ============================================================
    // Удаление
    // ============================================================

    public void deleteById(String id) {
        if (id == null) return;
        dao.deleteById(id);
    }

    public void deleteByConversation(String conversationId) {
        if (conversationId == null) return;
        dao.deleteByConversation(conversationId);
    }

    public void deleteAll() {
        dao.deleteAll();
    }
}