package com.my.mealse.messenger.data.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import com.my.mealse.messenger.data.entity.MessageEntity;

import java.util.List;

/**
 * DAO для таблицы messages.
 *
 * ★ ИЗМЕНЕНО: добавлены selectUnreadIncomingIds + markReadByIds
 * (для MessageRepository.markAllRead — один SQL вместо загрузки всех
 * сообщений в память и N отдельных UPDATE).
 */
@Dao
public interface MessageDao {

    // ============================================================
    // Вставка
    // ============================================================

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(MessageEntity entity);

    // ============================================================
    // Чтение
    // ============================================================

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    MessageEntity getById(String id);

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId "
        + "ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    List<MessageEntity> getByConversation(String conversationId, int limit, int offset);

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId "
        + "ORDER BY created_at ASC")
    List<MessageEntity> getAllByConversation(String conversationId);

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId "
        + "ORDER BY created_at DESC LIMIT 1")
    MessageEntity getLastByConversation(String conversationId);

    /** Для дедупликации при приёме (если сообщение уже есть — не вставлять). */
    @Query("SELECT COUNT(*) FROM messages WHERE id = :id")
    int exists(String id);

    // ============================================================
    // Статусы (одиночные UPDATE)
    // ============================================================

    @Query("UPDATE messages SET status = :status, delivered_at = :ts WHERE id = :id")
    void updateStatusDelivered(String id, String status, long ts);

    @Query("UPDATE messages SET status = :status, read_at = :ts WHERE id = :id")
    void updateStatusRead(String id, String status, long ts);

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    void updateStatus(String id, String status);

    @Query("UPDATE messages SET status = :status WHERE id = :id AND status = :expectedCurrent")
    void updateStatusIfCurrent(String id, String status, String expectedCurrent);

    @Query("SELECT COUNT(*) FROM messages WHERE conversation_id = :conversationId AND status = :status")
    int countByStatus(String conversationId, String status);

    // ============================================================
    // ★ НОВОЕ: массовая пометка READ (для markAllRead)
    // ============================================================

    /**
     * ★ НОВОЕ: id входящих SENT/DELIVERED (для markAllRead).
     *
     * Лёгкий SELECT — только id, без body/attachment/encrypted_body.
     * Для чата с 10 000 сообщений: ~10 000 String вместо 10 000 Message
     * (в 50–100 раз меньше heap).
     *
     * @param conversationId  диалог
     * @param selfFingerprint свой fingerprint — чтобы не помечать свои же
     *                        исходящие как прочитанные
     */
    @Query("SELECT id FROM messages "
        + "WHERE conversation_id = :conversationId "
        + "AND sender_fingerprint != :selfFingerprint "
        + "AND status IN ('SENT','DELIVERED')")
    List<String> selectUnreadIncomingIds(String conversationId, String selfFingerprint);

    /**
     * ★ НОВОЕ: массовый UPDATE status='READ', read_at=? по списку id.
     *
     * @return число реально обновлённых строк (может быть < ids.size(),
     *         если часть уже была READ).
     */
    @Query("UPDATE messages SET status = 'READ', read_at = :readAt "
        + "WHERE id IN (:ids)")
    int markReadByIds(List<String> ids, long readAt);

    // ============================================================
    // Удаление
    // ============================================================

    @Query("DELETE FROM messages WHERE id = :id")
    void deleteById(String id);

    @Query("DELETE FROM messages WHERE conversation_id = :conversationId")
    void deleteByConversation(String conversationId);

    @Query("DELETE FROM messages")
    void deleteAll();
}