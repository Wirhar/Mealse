package com.my.mealse.messenger.data.dao;

import androidx.annotation.Nullable;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.my.mealse.messenger.data.entity.ConversationEntity;

import java.util.List;

@Dao
public interface ConversationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(ConversationEntity entity);

    @Update
    void update(ConversationEntity entity);

    @Delete
    void delete(ConversationEntity entity);

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    ConversationEntity getById(String id);

    @Query("SELECT * FROM conversations WHERE call_id = :callId LIMIT 1")
    ConversationEntity getByCallId(String callId);

    @Query("SELECT * FROM conversations WHERE type = :type AND peer_fingerprint = :fingerprint LIMIT 1")
    ConversationEntity findPrivateByPeer(@Nullable String type, String fingerprint);

    @Query("SELECT * FROM conversations ORDER BY updated_at DESC")
    List<ConversationEntity> getAll();

    @Query("SELECT * FROM conversations ORDER BY updated_at DESC LIMIT :limit")
    List<ConversationEntity> getRecent(int limit);

    @Query("UPDATE conversations SET updated_at = :ts WHERE id = :id")
    void touchUpdatedAt(String id, long ts);

    @Query("UPDATE conversations SET unread_count = unread_count + 1 WHERE id = :id")
    void incrementUnread(String id);

    @Query("UPDATE conversations SET unread_count = 0 WHERE id = :id")
    void resetUnread(String id);

    @Query("UPDATE conversations SET "
        + "last_message_id = :messageId, "
        + "last_message_preview = :preview, "
        + "last_message_at = :ts, "
        + "updated_at = :ts "
        + "WHERE id = :id")
    void updateLastMessage(String id, String messageId, String preview, long ts);

    @Query("SELECT COUNT(*) FROM conversations")
    int count();

    @Query("DELETE FROM conversations WHERE id = :id")
    void deleteById(String id);

    @Query("DELETE FROM conversations")
    void deleteAll();
}