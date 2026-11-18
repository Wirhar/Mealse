package com.my.mealse.messenger.data.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.my.mealse.messenger.data.entity.MessageEntity;

import java.util.List;

@Dao
public interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(MessageEntity entity);

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

    @Query("DELETE FROM messages WHERE id = :id")
    void deleteById(String id);

    @Query("DELETE FROM messages WHERE conversation_id = :conversationId")
    void deleteByConversation(String conversationId);

    @Query("DELETE FROM messages")
    void deleteAll();

    /** Для дедупликации при приёме (если сообщение уже есть — не вставлять). */
    @Query("SELECT COUNT(*) FROM messages WHERE id = :id")
    int exists(String id);
}