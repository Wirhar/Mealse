package com.my.mealse.messenger.data.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.my.mealse.messenger.data.entity.OutboxEntity;

import java.util.List;

@Dao
public interface OutboxDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(OutboxEntity entity);

    @Update
    void update(OutboxEntity entity);

    @Delete
    void delete(OutboxEntity entity);

    @Query("SELECT * FROM outbox WHERE id = :id LIMIT 1")
    OutboxEntity getById(String id);

    @Query("SELECT * FROM outbox WHERE message_id = :messageId")
    List<OutboxEntity> getByMessage(String messageId);

    @Query("SELECT * FROM outbox WHERE target_node_key = :nodeKey")
    List<OutboxEntity> getByTarget(String nodeKey);

    @Query("SELECT * FROM outbox WHERE next_attempt_at <= :now ORDER BY next_attempt_at ASC")
    List<OutboxEntity> getDue(long now);

    @Query("SELECT * FROM outbox WHERE next_attempt_at <= :now ORDER BY next_attempt_at ASC LIMIT :limit")
    List<OutboxEntity> getDueLimited(long now, int limit);

    @Query("SELECT * FROM outbox ORDER BY next_attempt_at ASC")
    List<OutboxEntity> getAll();

    @Query("UPDATE outbox SET attempt_count = attempt_count + 1, "
        + "next_attempt_at = :nextAt, last_error = :error WHERE id = :id")
    void markAttempt(String id, long nextAt, String error);

    @Query("DELETE FROM outbox WHERE id = :id")
    void deleteById(String id);

    @Query("DELETE FROM outbox WHERE message_id = :messageId")
    void deleteByMessage(String messageId);

    @Query("DELETE FROM outbox WHERE target_node_key = :nodeKey")
    void deleteByTarget(String nodeKey);

    @Query("SELECT COUNT(*) FROM outbox")
    int count();
}