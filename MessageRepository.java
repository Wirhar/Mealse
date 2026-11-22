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
 *
 * ★ ИЗМЕНЕНО: реализован markAllRead(convId, readAt, selfFp).
 *
 *   Раньше markRead в MessageCommands грузил ВСЕ сообщения через
 *   getAllByConversation и помечал каждое в Java-цикле. Для чата с 10 000
 *   сообщений это OOM-риск на слабых устройствах + N отдельных UPDATE.
 *
 *   Теперь:
 *     1. SELECT id входящих SENT/DELIVERED (лёгкий — только id);
 *     2. один UPDATE ... WHERE id IN (...) SET status='READ', read_at=?;
 *     3. возвращаем список id для ack READ.
 *
 *   Требует два новых метода в MessageDao:
 *     - List<String> selectUnreadIncomingIds(convId, selfFp)
 *     - int markReadByIds(List<String> ids, readAt)
 *   Либо один @Transaction-метод в DAO. Если DAO ещё не готов —
 *   markAllRead вернёт пустой список (безопасно, ack не отправится,
 *   но статусы не обновятся — нужно добавить DAO-методы).
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

  // ============================================================
  // Вставка / чтение
  // ============================================================

  /**
   * Вставляет сообщение. IGNORE при конфликте — дедупликация встроена.
   *
   * @return true если вставлено (не было раньше), false если уже было.
   */
  public boolean add(Message message) {
    if (message == null || message.getId() == null)
      return false;
    long rowId = dao.insert(MessageMapper.toEntity(message));
    boolean inserted = rowId > 0;
    if (!inserted) {
      log.warn("add: сообщение уже существует id=" + message.getId());
    }
    return inserted;
  }

  @Nullable
  public Message getById(String id) {
    if (id == null)
      return null;
    return MessageMapper.toModel(dao.getById(id));
  }

  public boolean exists(String id) {
    if (id == null)
      return false;
    return dao.exists(id) > 0;
  }

  /**
   * Постраничная загрузка (для ChatActivity, reverse-layout: новые сверху).
   *
   * @param limit  размер страницы
   * @param offset сколько пропустить (0 — самые новые)
   */
  public List<Message> getByConversation(String conversationId, int limit, int offset) {
    if (conversationId == null)
      return new ArrayList<>();
    if (limit <= 0)
      limit = 50;
    if (offset < 0)
      offset = 0;
    List<MessageEntity> entities = dao.getByConversation(conversationId, limit, offset);
    List<Message> result = new ArrayList<>(entities.size());
    for (MessageEntity e : entities) {
      result.add(MessageMapper.toModel(e));
    }
    return result;
  }

  public List<Message> getAllByConversation(String conversationId) {
    if (conversationId == null)
      return new ArrayList<>();
    List<MessageEntity> entities = dao.getAllByConversation(conversationId);
    List<Message> result = new ArrayList<>(entities.size());
    for (MessageEntity e : entities) {
      result.add(MessageMapper.toModel(e));
    }
    return result;
  }

  @Nullable
  public Message getLastByConversation(String conversationId) {
    if (conversationId == null)
      return null;
    return MessageMapper.toModel(dao.getLastByConversation(conversationId));
  }

  public int countByStatus(String conversationId, MessageStatus status) {
    if (conversationId == null || status == null)
      return 0;
    return dao.countByStatus(conversationId, status.toStorageString());
  }

  // ============================================================
  // ★ НОВОЕ: markAllRead одним SQL
  // ============================================================

  /**
   * ★ НОВОЕ: пометить все входящие SENT/DELIVERED → READ одним SQL.
   *
   * Шаги:
   *   1. SELECT id входящих SENT/DELIVERED (лёгкий — только id, не тащим
   *      body/attachment).
   *   2. Если список пуст — return [] (нечего ack'ать).
   *   3. Один UPDATE ... WHERE id IN (...) SET status='READ', read_at=?.
   *   4. Return список id для ack READ.
   *
   * Требует в MessageDao:
   *   @Query("SELECT id FROM messages WHERE conversation_id = :convId
   *           AND sender_fingerprint != :selfFp
   *           AND status IN ('SENT','DELIVERED')")
   *   List<String> selectUnreadIncomingIds(String convId, String selfFp);
   *
   *   @Query("UPDATE messages SET status = 'READ', read_at = :readAt
   *           WHERE id IN (:ids)")
   *   int markReadByIds(List<String> ids, long readAt);
   *
   * @param conversationId  диалог
   * @param readAt          timestamp (millis)
   * @param selfFingerprint свой fingerprint — чтобы не помечать свои же
   *                        исходящие как прочитанные
   * @return messageIds, которые реально обновились (для ack).
   *         Пустой список — если нечего обновлять или DAO-методы не реализованы.
   */
  public List<String> markAllRead(String conversationId, long readAt, String selfFingerprint) {
    if (conversationId == null || conversationId.isEmpty())
      return new ArrayList<>();
    if (selfFingerprint == null) selfFingerprint = "";

    try {
      // 1. SELECT id входящих SENT/DELIVERED
      List<String> ids = dao.selectUnreadIncomingIds(conversationId, selfFingerprint);
      if (ids == null || ids.isEmpty()) {
        log.log("markAllRead: нечего помечать, conv=" + conversationId);
        return new ArrayList<>();
      }

      // 2. Один UPDATE ... WHERE id IN (...)
      int updated = dao.markReadByIds(ids, readAt);
      log.log("markAllRead: conv=" + conversationId + ", ids=" + ids.size()
          + ", updated=" + updated);

      // 3. Return ids для ack (даже если updated < ids.size — Room
      //    обычно возвращает число реально обновлённых строк; если
      //    статус уже был READ, updated будет меньше. Возвращаем ids
      //    целиком — ack по уже-прочитанным безвреден, MessageReceiver
      //    на той стороне отсечёт по статусу).
      return ids;
    } catch (Exception e) {
      log.logError("markAllRead", e);
      return new ArrayList<>();
    }
  }

  // ============================================================
  // Статусы
  // ============================================================

  public void markSent(String messageId) {
    if (messageId == null)
      return;
    dao.updateStatus(messageId, MessageStatus.SENT.toStorageString());
  }

  public void markDelivered(String messageId, long ts) {
    if (messageId == null)
      return;
    dao.updateStatusDelivered(messageId, MessageStatus.DELIVERED.toStorageString(), ts);
  }

  public void markRead(String messageId, long ts) {
    if (messageId == null)
      return;
    dao.updateStatusRead(messageId, MessageStatus.READ.toStorageString(), ts);
  }

  public void markFailed(String messageId) {
    if (messageId == null)
      return;
    dao.updateStatus(messageId, MessageStatus.FAILED.toStorageString());
  }

  /**
   * Условное обновление: только если текущий статус == expectedCurrent.
   * Нужно, чтобы DELIVERED не «откатывался» обратно в SENT.
   */
  public void markIfCurrent(String messageId, MessageStatus newStatus,
      MessageStatus expectedCurrent) {
    if (messageId == null || newStatus == null || expectedCurrent == null)
      return;
    dao.updateStatusIfCurrent(messageId, newStatus.toStorageString(),
        expectedCurrent.toStorageString());
  }

  // ============================================================
  // Удаление
  // ============================================================

  public void deleteById(String id) {
    if (id == null)
      return;
    dao.deleteById(id);
  }

  public void deleteByConversation(String conversationId) {
    if (conversationId == null)
      return;
    dao.deleteByConversation(conversationId);
  }

  public void deleteAll() {
    dao.deleteAll();
  }
}