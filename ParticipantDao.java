package com.my.mealse.messenger.data.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.my.mealse.messenger.data.entity.ParticipantEntity;

import java.util.List;

@Dao
public interface ParticipantDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(ParticipantEntity entity);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<ParticipantEntity> entities);

    @Delete
    void delete(ParticipantEntity entity);

    @Query("SELECT * FROM participants WHERE conversation_id = :conversationId")
    List<ParticipantEntity> getByConversation(String conversationId);

    @Query("SELECT * FROM participants WHERE conversation_id = :conversationId AND fingerprint = :fingerprint LIMIT 1")
    ParticipantEntity findByFingerprint(String conversationId, String fingerprint);

    @Query("SELECT COUNT(*) FROM participants WHERE conversation_id = :conversationId")
    int countByConversation(String conversationId);

    @Query("DELETE FROM participants WHERE conversation_id = :conversationId")
    void deleteByConversation(String conversationId);

    @Query("DELETE FROM participants WHERE id = :id")
    void deleteById(String id);
}