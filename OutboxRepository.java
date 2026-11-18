package com.my.mealse.messenger.repo;

import android.content.Context;

import androidx.annotation.Nullable;

import com.my.mealse.messenger.data.DatabaseHolder;
import com.my.mealse.messenger.data.dao.OutboxDao;
import com.my.mealse.messenger.data.entity.OutboxEntity;
import com.my.mealse.messenger.transport.Backoff;
import com.my.mealse.util.MealseLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Репозиторий очереди исходящих (офлайн-доставка).
 *
 * Запись создаётся при sendText (MessageCommands).
 * DeliveryScheduler выбирает due-записи, отправляет, при успехе — удаляет,
 * при неудаче — markAttempt(nextAttemptAt = now + backoff).
 */
public class OutboxRepository {

    private static final String TAG = "MessengerOutboxRepo";

    private final OutboxDao dao;
    private final MealseLogger log;

    public OutboxRepository(Context context) {
        Context appCtx = context.getApplicationContext();
        this.dao = DatabaseHolder.get(appCtx).outboxDao();
        this.log = MealseLogger.get(appCtx, TAG);
    }

    /**
     * Кладёт сообщение в очередь для targetNodeKey.
     * @return id записи outbox или null при ошибке
     */
    @Nullable
    public String enqueue(String messageId, String conversationId,
                          String targetNodeKey, String targetFingerprint) {
        if (messageId == null || conversationId == null || targetNodeKey == null) {
            log.warn("enqueue: не хватает параметров");
            return null;
        }

        long now = System.currentTimeMillis();
        OutboxEntity e = new OutboxEntity();
        e.id = UUID.randomUUID().toString();
        e.messageId = messageId;
        e.conversationId = conversationId;
        e.targetNodeKey = targetNodeKey;
        e.targetFingerprint = targetFingerprint;
        e.attemptCount = 0;
        e.nextAttemptAt = now;   // первый раз — сразу
        e.lastError = null;
        e.createdAt = now;

        dao.insert(e);
        log.log("enqueue: msg=" + messageId + " → " + targetNodeKey);
        return e.id;
    }

    @Nullable
    public OutboxEntity getById(String id) {
        if (id == null) return null;
        return dao.getById(id);
    }

    public List<OutboxEntity> getByMessage(String messageId) {
        if (messageId == null) return new ArrayList<>();
        return dao.getByMessage(messageId);
    }

    public List<OutboxEntity> getByTarget(String nodeKey) {
        if (nodeKey == null) return new ArrayList<>();
        return dao.getByTarget(nodeKey);
    }

    /**
     * Записи, готовые к отправке (nextAttemptAt <= now).
     */
    public List<OutboxEntity> getDue(long now) {
        return dao.getDue(now);
    }

    public List<OutboxEntity> getDueLimited(long now, int limit) {
        if (limit <= 0) limit = 20;
        return dao.getDueLimited(now, limit);
    }

    public List<OutboxEntity> getAll() {
        return dao.getAll();
    }

    public int count() {
        return dao.count();
    }

    /**
     * Отмечает неудачную попытку: attemptCount++, nextAttemptAt = now + backoff.
     * Если attemptCount >= Backoff.MAX_ATTEMPTS — возвращает true (исчерпано).
     */
    public boolean markAttempt(String outboxId, String error) {
        if (outboxId == null) return false;
        OutboxEntity e = dao.getById(outboxId);
        if (e == null) return false;

        int newCount = e.attemptCount + 1;
        long nextAt = Backoff.nextAttemptAt(newCount, System.currentTimeMillis());
        boolean exhausted = Backoff.isExhausted(newCount);

        dao.markAttempt(outboxId, nextAt, error);
        log.log("markAttempt: " + outboxId + " attempt=" + newCount
            + ", nextAt=+" + Backoff.nextIntervalMs(newCount) / 1000 + "s"
            + ", exhausted=" + exhausted + ", error=" + error);
        return exhausted;
    }

    public void remove(String outboxId) {
        if (outboxId == null) return;
        dao.deleteById(outboxId);
    }

    public void removeByMessage(String messageId) {
        if (messageId == null) return;
        dao.deleteByMessage(messageId);
    }

    public void removeByTarget(String nodeKey) {
        if (nodeKey == null) return;
        dao.deleteByTarget(nodeKey);
    }
}