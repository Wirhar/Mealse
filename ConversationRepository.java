package com.my.mealse.messenger.repo;

import android.content.Context;
import androidx.annotation.NonNull;

import androidx.annotation.Nullable;

import com.my.mealse.messenger.data.DatabaseHolder;
import com.my.mealse.messenger.data.dao.ConversationDao;
import com.my.mealse.messenger.data.entity.ConversationEntity;
import com.my.mealse.messenger.mapper.ConversationMapper;
import com.my.mealse.messenger.model.Conversation;
import com.my.mealse.messenger.model.ConversationType;
import com.my.mealse.util.MealseLogger;
import com.my.mealse.util.SecureAuthManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Репозиторий диалогов.
 *
 * Синхронные операции (Room). Вызывать НЕ из UI-потока — через Executor / новый Thread в MessageCommands, MessageReceiver, UI.
 */
public class ConversationRepository {

  private static final String TAG = "MessengerConvRepo";

  private final Context appContext;
  private final ConversationDao dao;
  private final MealseLogger log;

  public ConversationRepository(Context context) {
    Context appCtx = context.getApplicationContext();
    this.appContext = context.getApplicationContext();
    this.dao = DatabaseHolder.get(appCtx).conversationDao();
    this.log = MealseLogger.get(appCtx, TAG);
  }

  // ============================================================
  // Создание / получение
  // ============================================================

  /**
   * Создаёт или возвращает существующий PRIVATE-диалог с узлом (по fingerprint).
   */
  @Nullable
  public Conversation getOrCreatePrivate(String peerFingerprint, String title) {
    if (peerFingerprint == null || peerFingerprint.isEmpty()) {
      log.warn("getOrCreatePrivate: peerFingerprint пуст");
      return null;
    }

    // ★ Детерминированный convId: одинаковый у A и B
    String selfFp = getSelfFingerprint();
    String convId = deterministicPrivateId(selfFp, peerFingerprint);

    // 1. Есть по детерминированному id — возвращаем
    ConversationEntity existing = dao.getById(convId);
    if (existing != null) {
      return ConversationMapper.toModel(existing);
    }

    // 2. Fallback: legacy-поиск по peerFingerprint (для старых чатов)
    ConversationEntity legacy = dao.findPrivateByPeer(ConversationType.PRIVATE.toStorageString(),
        peerFingerprint);
    if (legacy != null) {
      return ConversationMapper.toModel(legacy);
    }

    // 3. Создаём с детерминированным id
    long now = System.currentTimeMillis();
    ConversationEntity e = new ConversationEntity();
    e.id = convId;
    e.type = ConversationType.PRIVATE.toStorageString();
    e.title = title;
    e.createdAt = now;
    e.updatedAt = now;
    e.unreadCount = 0;
    e.peerFingerprint = peerFingerprint;
    e.callId = null;
    dao.insert(e);

    log.log("getOrCreatePrivate: создан " + shortId(convId) + ", peer=" + shortId(peerFingerprint));
    return ConversationMapper.toModel(e);
  }

  private String deterministicPrivateId(String fp1, String fp2) {
    if (fp1 == null)
      fp1 = "";
    if (fp2 == null)
      fp2 = "";
    String a = fp1.compareTo(fp2) <= 0 ? fp1 : fp2;
    String b = fp1.compareTo(fp2) <= 0 ? fp2 : fp1;
    try {
      return java.util.UUID.nameUUIDFromBytes((a + "|" + b).getBytes("UTF-8")).toString();
    } catch (Exception e) {
      return java.util.UUID.randomUUID().toString();
    }
  }

  private String getSelfFingerprint() {
    try {
      String fp = new SecureAuthManager(appContext).getDeviceFingerprint();
      return fp != null ? fp : "";
    } catch (Exception e) {
      return "";
    }
  }

  /**
   * Создаёт GROUP-диалог (без участников — их добавит ParticipantRepository).
   */
  public Conversation createGroup(String title) {
    long now = System.currentTimeMillis();
    ConversationEntity e = new ConversationEntity();
    e.id = UUID.randomUUID().toString();
    e.type = ConversationType.GROUP.toStorageString();
    e.title = title;
    e.createdAt = now;
    e.updatedAt = now;
    e.unreadCount = 0;
    e.peerFingerprint = null;
    e.callId = null;
    dao.insert(e);

    log.log("createGroup: id=" + e.id + ", title=" + title);
    return ConversationMapper.toModel(e);
  }

  /**
   * Создаёт CALL-диалог для звонка или возвращает существующий. callId уникален (unique index в entity).
   */
  public Conversation getOrCreateCall(String callId, String peerFingerprint, String title) {
    if (callId == null || callId.isEmpty()) {
      log.warn("getOrCreateCall: callId пуст");
      return null;
    }

    ConversationEntity existing = dao.getByCallId(callId);
    if (existing != null) {
      log.log("getOrCreateCall: найден существующий id=" + existing.id);
      return ConversationMapper.toModel(existing);
    }

    // ★ Детерминированный id от callId — одинаковый у A и B
    String convId = deterministicCallId(callId);

    ConversationEntity byId = dao.getById(convId);
    if (byId != null) {
      byId.callId = callId;
      if (byId.peerFingerprint == null || byId.peerFingerprint.isEmpty()) {
        byId.peerFingerprint = peerFingerprint;
      }
      dao.insert(byId);
      log.log("getOrCreateCall: найден по deterministic id=" + byId.id + ", callId=" + callId);
      return ConversationMapper.toModel(byId);
    }

    long now = System.currentTimeMillis();
    ConversationEntity e = new ConversationEntity();
    e.id = convId;
    e.type = ConversationType.CALL.toStorageString();
    e.title = title;
    e.createdAt = now;
    e.updatedAt = now;
    e.unreadCount = 0;
    e.peerFingerprint = peerFingerprint;
    e.callId = callId;
    dao.insert(e);

    log.log("getOrCreateCall: создан id=" + e.id + ", callId=" + callId);
    return ConversationMapper.toModel(e);
  }

  private String deterministicCallId(String callId) {
    try {
      return java.util.UUID.nameUUIDFromBytes(("call|" + callId).getBytes("UTF-8")).toString();
    } catch (Exception e) {
      return java.util.UUID.randomUUID().toString();
    }
  }

  // ============================================================
  // Чтение
  // ============================================================

  @Nullable
  public Conversation getById(String id) {
    if (id == null)
      return null;
    return ConversationMapper.toModel(dao.getById(id));
  }

  @Nullable
  public Conversation getByCallId(String callId) {
    if (callId == null)
      return null;
    return ConversationMapper.toModel(dao.getByCallId(callId));
  }

  public List<Conversation> getAll() {
    List<ConversationEntity> entities = dao.getAll();
    List<Conversation> result = new ArrayList<>(entities.size());
    for (ConversationEntity e : entities) {
      // ★ Скрываем CALL-чаты из общего списка
      if (ConversationType.CALL.toStorageString().equals(e.type)) {
        continue;
      }
      result.add(ConversationMapper.toModel(e));
    }
    return result;
  }

  public List<Conversation> getRecent(int limit) {
    if (limit <= 0)
      limit = 50;
    List<ConversationEntity> entities = dao.getRecent(limit);
    List<Conversation> result = new ArrayList<>(entities.size());
    for (ConversationEntity e : entities) {
      result.add(ConversationMapper.toModel(e));
    }
    return result;
  }

  public int count() {
    return dao.count();
  }

  /**
   * ★ Возвращает существующий PRIVATE-чат по id, либо создаёт новый с ЯВНЫМ id (из envelope). Нужно для синхронизации conversation_id между A и B: у обоих один и тот же id для одной пары устройств.
   *
   * Если чат с таким id уже есть, но peerFingerprint отличается — возвращаем существующий (не перезаписываем).
   */
  @Nullable
  public Conversation getOrCreatePrivateWithId(@NonNull String convId,
      @NonNull String peerFingerprint, @Nullable String title) {
    if (convId == null || convId.isEmpty())
      return null;
    if (peerFingerprint == null || peerFingerprint.isEmpty())
      return null;

    // 1. Уже есть — возвращаем
    ConversationEntity existing = dao.getById(convId);
    if (existing != null) {
      log.log("getOrCreatePrivateWithId: найден существующий " + shortId(convId));
      return ConversationMapper.toModel(existing);
    }

    // 2. Создаём с явным id
    long now = System.currentTimeMillis();
    ConversationEntity e = new ConversationEntity();
    e.id = convId;
    e.type = ConversationType.PRIVATE.toStorageString();
    e.title = title;
    e.createdAt = now;
    e.updatedAt = now;
    e.unreadCount = 0;
    e.peerFingerprint = peerFingerprint;
    e.callId = null;
    dao.insert(e);

    log.log("getOrCreatePrivateWithId: создан " + shortId(convId) + ", peer="
        + shortId(peerFingerprint));
    return ConversationMapper.toModel(e);
  }

  // ============================================================
  // Обновление
  // ============================================================

  public void touchUpdatedAt(String conversationId) {
    if (conversationId == null)
      return;
    dao.touchUpdatedAt(conversationId, System.currentTimeMillis());
  }

  public void incrementUnread(String conversationId) {
    if (conversationId == null)
      return;
    dao.incrementUnread(conversationId);
  }

  public void resetUnread(String conversationId) {
    if (conversationId == null)
      return;
    dao.resetUnread(conversationId);
  }

  /**
   * Обновляет денормализованное последнее сообщение в диалоге. Вызывается после добавления/приёма сообщения.
   */
  public void updateLastMessage(String conversationId, String messageId, String preview, long ts) {
    if (conversationId == null)
      return;
    dao.updateLastMessage(conversationId, messageId, preview, ts);
  }

  public void save(Conversation conversation) {
    if (conversation == null || conversation.getId() == null)
      return;
    dao.insert(ConversationMapper.toEntity(conversation));
  }

  // ============================================================
  // Удаление
  // ============================================================

  public void deleteById(String id) {
    if (id == null)
      return;
    dao.deleteById(id);
    log.log("deleteById: " + id);
  }

  public void deleteAll() {
    dao.deleteAll();
    log.log("deleteAll");
  }

  private static String shortId(String id) {
    if (id == null)
      return "?";
    return id.length() > 8 ? id.substring(0, 8) : id;
  }

  /**
   * Возвращает ConversationEntity по callId (без маппинга в модель).
   *
   * Нужен в MessageReceiver.handleRegularMessage: при приёме сообщения с conversation_id, которого нет в БД, мы хотим понять — это CALL-чат (тогда создавать/использовать type=CALL) или обычный PRIVATE.
   *
   * @param callId UUID звонка (ConversationEntity.callId)
   * @return ConversationEntity или null, если не найден
   */
  @Nullable
  public ConversationEntity getEntityByCallId(@Nullable String callId) {
    if (callId == null || callId.isEmpty()) {
      return null;
    }
    try {
      ConversationEntity e = dao.getByCallId(callId);
      if (e != null) {
        log.log("getEntityByCallId: найден id=" + shortId(e.id) + ", callId=" + shortId(callId)
            + ", type=" + e.type);
      } else {
        log.log("getEntityByCallId: не найден callId=" + shortId(callId));
      }
      return e;
    } catch (Exception ex) {
      log.logError("getEntityByCallId: ошибка для callId=" + shortId(callId), ex);
      return null;
    }
  }
}