package com.my.mealse.messenger.transport;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonObject;
import com.my.mealse.messenger.crypto.PayloadCodec;
import com.my.mealse.messenger.crypto.MessagePayload;
import com.my.mealse.messenger.model.AttachmentInfo;
import com.my.mealse.messenger.model.Conversation;
import com.my.mealse.messenger.model.Message;
import com.my.mealse.messenger.model.MessageStatus;
import com.my.mealse.messenger.model.MessageType;
import com.my.mealse.messenger.model.Participant;
import com.my.mealse.messenger.repo.ConversationRepository;
import com.my.mealse.messenger.repo.MessageRepository;
import com.my.mealse.messenger.repo.OutboxRepository;
import com.my.mealse.messenger.repo.ParticipantRepository;
import com.my.mealse.messenger.storage.AttachmentStorage;
import com.my.mealse.model.TrustedNode;
import com.my.mealse.util.DeviceInfoHelper;
import com.my.mealse.util.MealseLogger;
import com.my.mealse.util.NodeManager;
import com.my.mealse.util.SecureAuthManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import java.util.HashSet;
import java.util.Set;

/**
 * Фасад для UI: отправка сообщений и пометка прочитанными.
 *
 * sendText(ctx, conversationId, text): Текстовое сообщение. Создаёт MessageEntity(PENDING) + Outbox для каждого получателя. DeliveryScheduler отправляет.
 *
 * ★ НОВОЕ (Шаг 16.2): sendFile(ctx, conversationId, attachment): 1. Проверяет размер (≤ MAX_ATTACHMENT_BYTES = 10 МБ). 2. Копирует файл в AttachmentStorage (getFilesDir()/messenger/ attachments/<convId>/<msgId>_<filename>) — приватное хранилище, скрытое от удалённых узлов (Шаг 15). 3. Создаёт MessageEntity(type=FILE, attachmentUri=<localPath>, attachmentName, attachmentMime, attachmentSize, status=PENDING). 4. Создаёт Outbox для каждого получателя. 5. DeliveryScheduler.doTryDeliver при отправке читает файл по attachmentUri и кодирует в base64 (Шаг 16.3).
 *
 * markRead(ctx, conversationId): resetUnread + входящие → READ + ack READ каждому автору.
 */
public final class MessageCommands {

  private static final String TAG = "MessengerCommands";

  /** ★ Шаг 16.2: лимит вложения для отправки. */
  /**
   * ★ ИЗМЕНЕНО (правка F): 50 МБ → 20 МБ.
   *
   * Причина: FileMessageSender.MAX_FILE_SIZE = 20 МБ, ServerService.MAX_FILE_SIZE = 20 МБ, FileChunkProtocol.MAX_FILE_SIZE = 20 МБ. Если sendFile пропускал 50 МБ, транспорт возвращал 413 → fatal → markFailed без retry. Лучше отклонить сразу в sendFile с понятным сообщением, чем гонять 50 МБ до 413.
   *
   * Комментарий «// 10 МБ» в старом коде был неверным — константа была 50 МБ, а UI/документация говорили 10 МБ. Теперь всё выровнено по 20 МБ.
   */
  /** Политика: чат звонка — лёгкие вложения. */
  public static final long MAX_ATTACHMENT_BYTES_CALL = 20L * 1024 * 1024;
  /** Политика: текстовый чат и «Отправить в чат» из менеджера — технический потолок. */
  public static final long MAX_ATTACHMENT_BYTES_TEXT = 8L * 1024 * 1024 * 1024; // 8 ГБ
  /** Legacy-алиас: только для чата звонка. В новом коде использовать _CALL/_TEXT. */
  public static final long MAX_ATTACHMENT_BYTES = MAX_ATTACHMENT_BYTES_CALL;

  private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "MessengerCommands");
    t.setDaemon(true);
    return t;
  });

  private MessageCommands() {
  }

  // ============================================================
  // Отправка текста
  // ============================================================

  /**
   * Отправить текстовое сообщение.
   *
   * @return messageId (UUID) — созданный локально. Может быть null при ошибке.
   */
  @Nullable
  public static String sendText(@NonNull Context context, @NonNull String conversationId,
      @NonNull String text) {
    return sendText(context, conversationId, text, null);
  }

  @Nullable
  public static String sendText(@NonNull Context context, @NonNull String conversationId,
      @NonNull String text, @Nullable String callId) {
    if (conversationId == null || conversationId.isEmpty())
      return null;
    if (text == null || text.trim().isEmpty())
      return null;

    Context appCtx = context.getApplicationContext();
    MealseLogger log = MealseLogger.get(appCtx, TAG);

    String messageId = UUID.randomUUID().toString();
    long now = System.currentTimeMillis();

    final String finalMessageId = messageId;
    final long finalNow = now;
    final String finalCallId = callId;

    EXECUTOR.execute(() -> {
      try {
        ConversationRepository convRepo = new ConversationRepository(appCtx);
        MessageRepository msgRepo = new MessageRepository(appCtx);
        ParticipantRepository partRepo = new ParticipantRepository(appCtx);
        OutboxRepository outboxRepo = new OutboxRepository(appCtx);

        Conversation conv = convRepo.getById(conversationId);
        if (conv == null) {
          log.warn("sendText: диалог не найден " + conversationId);
          return;
        }

        String selfFp = getSelfFingerprint(appCtx);
        String selfDeviceId = getSelfDeviceId(appCtx);

        Message msg = new Message();
        msg.setId(finalMessageId);
        msg.setConversationId(conversationId);
        msg.setSenderFingerprint(selfFp);
        msg.setSenderDeviceId(selfDeviceId);
        msg.setSenderNodeKey(null);
        msg.setType(MessageType.TEXT);
        msg.setBody(text);
        msg.setEncryptedBody(null);
        msg.setCreatedAt(finalNow);
        msg.setStatus(MessageStatus.PENDING);

        boolean inserted = msgRepo.add(msg);
        if (!inserted) {
          log.warn("sendText: сообщение уже было " + finalMessageId);
          return;
        }

        convRepo.updateLastMessage(conversationId, finalMessageId, msg.getPreview(), finalNow);
        convRepo.touchUpdatedAt(conversationId);

        enqueueForAllParticipants(appCtx, log, convRepo, partRepo, outboxRepo, conversationId,
            finalMessageId, selfFp, selfDeviceId, finalCallId);

        log.log("sendText: msg=" + shortId(finalMessageId) + ", conv=" + shortId(conversationId)
            + ", callId=" + shortId(finalCallId) + ", text=" + text.length() + " chars");

      } catch (Exception e) {
        log.logError("sendText error", e);
      }
    });

    return messageId;
  }

  /** Текстовый чат / «Отправить в чат» из менеджера — без искусственного лимита (8 ГБ). */
  @Nullable
  public static String sendFile(@NonNull Context context, @NonNull String conversationId,
      @NonNull AttachmentInfo attachment) {
    return sendFile(context, conversationId, attachment, null, MAX_ATTACHMENT_BYTES_TEXT);
  }

  /** Чат звонка — лимит 20 МБ. Единственный вызов с callId — из CallActivity. */
  @Nullable
  public static String sendFile(@NonNull Context context, @NonNull String conversationId,
      @NonNull AttachmentInfo attachment, @Nullable String callId) {
    return sendFile(context, conversationId, attachment, callId, MAX_ATTACHMENT_BYTES_CALL);
  }

  // ============================================================
  // ★ НОВОЕ (Шаг 16.2): отправка файла
  // ============================================================

  /**
   * Отправить файл (вложение).
   *
   * Шаги: 1. Проверка размера (≤ MAX_ATTACHMENT_BYTES). 2. Копия файла в AttachmentStorage (приватно, скрыто). 3. MessageEntity(type=FILE). 4. Outbox для каждого получателя.
   *
   * @param context        контекст (берётся appContext)
   * @param conversationId UUID диалога
   * @param attachment     вложение (имя, MIME, размер, localPath)
   * @return messageId или null при ошибке
   */
  /**
   * ★ ИЗМЕНЕНО: - проверка convRepo.getById(conversationId) ДО saveFromFile — иначе файл-сирота в attachments/<convId>/, если диалог не существует; - при ошибке msgRepo.add после saveFromFile — очистка файла.
   *
   * @param context        контекст (берётся appContext)
   * @param conversationId UUID диалога
   * @param attachment     вложение (имя, MIME, размер, localPath)
   * @param callId         опциональный callId (для чата звонка)
   * @return messageId или null при ошибке
   */
  @Nullable
  public static String sendFile(@NonNull Context context, @NonNull String conversationId,
      @NonNull AttachmentInfo attachment, @Nullable String callId, long maxBytes) {
    if (conversationId == null || conversationId.isEmpty())
      return null;
    if (attachment == null)
      return null;

    Context appCtx = context.getApplicationContext();
    MealseLogger log = MealseLogger.get(appCtx, TAG);

    if (!attachment.isReady()) {
      log.warn("sendFile: вложение не готово: " + attachment);
      return null;
    }
    // ★ Единственная проверка размера — по явному лимиту (политика контекста:
    // 3-арг. sendFile → MAX_ATTACHMENT_BYTES_TEXT, 4-арг. → MAX_ATTACHMENT_BYTES_CALL).
    if (attachment.getSizeBytes() > maxBytes) {
      log.warn("sendFile: размер " + attachment.getSizeBytes() + " > лимита " + maxBytes
          + " (callId=" + (callId != null ? "set" : "null") + ")");
      return null;
    }

    File srcFile = attachment.getLocalFile();
    if (srcFile == null || !srcFile.exists()) {
      log.warn("sendFile: исходный файл исчез " + attachment.getLocalPath());
      return null;
    }

    // ★ НОВОЕ: проверяем диалог ДО saveFromFile — иначе файл-сирота
    // в attachments/<convId>/, если conversationId не существует.
    ConversationRepository convRepo = new ConversationRepository(appCtx);
    Conversation conv = convRepo.getById(conversationId);
    if (conv == null) {
      log.warn("sendFile: диалог не найден " + conversationId + " — файл не копируем");
      return null;
    }

    // ★ НОВОЕ: StatFs по ЦЕЛЕВОЙ папке — ПОСЛЕ проверки диалога, чтобы
    // getConversationDir(convId) не создавал каталог-сироту для невалидного convId.
    // need = length + запас (НЕ 2*length): srcFile уже занимает место и
    // исключён из getAvailableBytes(); для копии нужен ещё один length.
    try {
      File targetDir = AttachmentStorage.getConversationDir(appCtx, conversationId);
      android.os.StatFs st = new android.os.StatFs(targetDir.getAbsolutePath());
      long need = srcFile.length() + (16L * 1024 * 1024);
      long avail = st.getAvailableBytes();
      if (avail < need) {
        log.warn("sendFile: мало места в " + targetDir + ": avail=" + avail + ", need=" + need);
        return null;
      }
    } catch (Exception e) {
      // StatFs может бросить на экзотических ФС — не блокируем отправку,
      // копия всё равно упадёт с IOException, если места нет.
      log.warn("sendFile: StatFs failed: " + e.getMessage());
    }

    String messageId = UUID.randomUUID().toString();
    long now = System.currentTimeMillis();

    // ★ Копируем СИНХРОННО, чтобы вызывающий мог удалить tmp сразу
    // после возврата messageId — копия уже в attachments/, tmp больше не нужен.
    File saved;
    try {
      saved = AttachmentStorage.saveFromFile(appCtx, conversationId, messageId, srcFile);
    } catch (Exception e) {
      log.logError("sendFile: saveFromFile", e);
      return null;
    }
    if (saved == null) {
      log.warn("sendFile: AttachmentStorage.saveFromFile вернул null");
      return null;
    }

    final String finalMessageId = messageId;
    final long finalNow = now;
    final File finalSaved = saved;
    final String finalCallId = callId;

    EXECUTOR.execute(() -> {
      try {
        MessageRepository msgRepo = new MessageRepository(appCtx);
        ParticipantRepository partRepo = new ParticipantRepository(appCtx);
        OutboxRepository outboxRepo = new OutboxRepository(appCtx);

        // conv уже проверен выше — но перечитываем, т.к. за время
        // saveFromFile диалог мог быть удалён.
        Conversation conv2 = convRepo.getById(conversationId);
        if (conv2 == null) {
          log.warn("sendFile: диалог исчез за время копирования " + conversationId);
          // ★ очистка файла-сироты
          try {
            AttachmentStorage.deleteForConversation(appCtx, conversationId);
          } catch (Exception ignored) {
          }
          return;
        }

        String selfFp = getSelfFingerprint(appCtx);
        String selfDeviceId = getSelfDeviceId(appCtx);

        Message msg = new Message();
        msg.setId(finalMessageId);
        msg.setConversationId(conversationId);
        msg.setSenderFingerprint(selfFp);
        msg.setSenderDeviceId(selfDeviceId);
        msg.setSenderNodeKey(null);
        msg.setType(MessageType.FILE);
        msg.setBody(attachment.getFileName());
        msg.setEncryptedBody(null);
        msg.setAttachmentUri(finalSaved.getAbsolutePath());
        msg.setAttachmentName(attachment.getFileName());
        msg.setAttachmentMime(attachment.getMimeType());
        msg.setAttachmentSize(finalSaved.length());
        msg.setCreatedAt(finalNow);
        msg.setStatus(MessageStatus.PENDING);

        boolean inserted = msgRepo.add(msg);
        if (!inserted) {
          log.warn("sendFile: сообщение уже было " + finalMessageId);
          // ★ очистка файла-сироты (msgRepo.add упал — Message не создан)
          try {
            if (finalSaved.exists())
              finalSaved.delete();
          } catch (Exception ignored) {
          }
          return;
        }

        convRepo.updateLastMessage(conversationId, finalMessageId, msg.getPreview(), finalNow);
        convRepo.touchUpdatedAt(conversationId);

        enqueueForAllParticipants(appCtx, log, convRepo, partRepo, outboxRepo, conversationId,
            finalMessageId, selfFp, selfDeviceId, finalCallId);

        log.log("sendFile: msg=" + shortId(finalMessageId) + ", conv=" + shortId(conversationId)
            + ", name=" + attachment.getFileName() + ", size="
            + AttachmentInfo.formatSize(finalSaved.length()) + ", saved="
            + finalSaved.getAbsolutePath());

      } catch (Exception e) {
        log.logError("sendFile error", e);
      }
    });

    return messageId;
  }

  // ============================================================
  // markRead
  // ============================================================

  /**
   * Пометить диалог прочитанным: 1. resetUnread. 2. Входящие DELIVERED/SENT → READ. 3. ack READ каждому автору.
   */
  /**
   * ★ ИЗМЕНЕНО: markAllRead одним SQL вместо getAllByConversation.
   *
   * Было: List<Message> all = msgRepo.getAllByConversation(convId) — загрузка ВСЕХ сообщений в память. Для чата с 10 000 сообщений — OOM на слабых устройствах.
   *
   * Стало: msgRepo.markAllRead(convId, now, selfFp) возвращает List<String> messageIds (только те, что обновились). Их передаём в sendReadAck по списку.
   *
   * Требует MessageRepository.markAllRead(String convId, long now, String selfFp).
   */
  public static void markRead(@NonNull Context context, @NonNull String conversationId) {
    if (conversationId == null)
      return;
    final Context appCtx = context.getApplicationContext();
    final MealseLogger log = MealseLogger.get(appCtx, TAG);

    EXECUTOR.execute(() -> {
      try {
        ConversationRepository convRepo = new ConversationRepository(appCtx);
        MessageRepository msgRepo = new MessageRepository(appCtx);

        convRepo.resetUnread(conversationId);

        long now = System.currentTimeMillis();
        String selfFp = getSelfFingerprint(appCtx);

        // ★ Один SQL: UPDATE messages SET status='READ', read_at=?
        // WHERE conversation_id=? AND sender_fingerprint != ?
        // AND status IN ('SENT','DELIVERED')
        // Возвращает messageIds, которые реально обновились.
        List<String> markedIds = msgRepo.markAllRead(conversationId, now, selfFp);
        int marked = markedIds != null ? markedIds.size() : 0;

        // ack READ по списку (пока по одному; batched ack — отдельный шаг)
        if (markedIds != null) {
          for (String id : markedIds) {
            try {
              sendReadAckById(appCtx, log, id, conversationId);
            } catch (Exception e) {
              log.warn("markRead: sendReadAck " + shortId(id) + " — " + e.getMessage());
            }
          }
        }

        log.log("markRead: conv=" + shortId(conversationId) + ", marked=" + marked);
      } catch (Exception e) {
        log.logError("markRead error", e);
      }
    });
  }

  /**
   * ★ НОВОЕ: ack READ по messageId без загрузки всех сообщений. Используется из markRead (после markAllRead).
   */
  private static void sendReadAckById(Context appCtx, MealseLogger log, String messageId,
      String conversationId) {
    try {
      if (messageId == null || messageId.isEmpty())
        return;
      MessageRepository msgRepo = new MessageRepository(appCtx);
      Message m = msgRepo.getById(messageId);
      if (m == null) {
        log.warn("sendReadAckById: сообщение " + shortId(messageId) + " не найдено");
        return;
      }
      sendReadAck(appCtx, log, m);
    } catch (Exception e) {
      log.logError("sendReadAckById", e);
    }
  }

  // ============================================================
  // Внутренние: enqueue для всех участников
  // ============================================================

  /**
   * Раскладывает одно сообщение в outbox для каждого участника (кроме self).
   *
   * ★ ИСПРАВЛЕНО (шаг 17.1): nodeKey для участника выбирается с приоритетом Wi-Fi Direct, если к этому же deviceId есть активное WD-соединение. Раньше при participants.nodeKey = HTTP (192.168.1.123:8081), а WD был подключён — первые попытки летели по HTTP, падали (ConnectException), и только после 2 retry (44 сек) шли через WD.
   */
  /**
   * ★ ИЗМЕНЕНО: + failover существующих pending-сообщений с HTTP-nodeKey на WD-nodeKey, если WD подключён к тому же deviceId.
   *
   * Было: WD-приоритет срабатывал только при новом enqueue. Если сообщение уже висело в HTTP-outbox (attempt 1/2/3), а WD подключился позже — DeliveryScheduler продолжал HTTP-retry (в логах: 21:15:21 → 21:16:17, attempt 1/2/3), пока не истекал backoff.
   *
   * Стало: после основного enqueue — перекладываем pending-записи с HTTP-nodeKey на WD-nodeKey. Дополняет failover WD → HTTP в DeliveryScheduler.onSendResult (теперь есть оба направления).
   */
  private static void enqueueForAllParticipants(Context appCtx, MealseLogger log,
      ConversationRepository convRepo, ParticipantRepository partRepo, OutboxRepository outboxRepo,
      String conversationId, String messageId, String selfFp, String selfDeviceId,
      @Nullable String callId) {
    List<Participant> participants = partRepo.getByConversation(conversationId);
    int enqueued = 0;

    // ★ получаем WD-соединение один раз на всех участников
    String wdConnectedDeviceId = null;
    try {
      if (appCtx instanceof com.my.mealse.MealseApp) {
        com.my.mealse.util.WifiDirectManager wdm = ((com.my.mealse.MealseApp) appCtx)
            .getWifiDirectManager();
        if (wdm != null && wdm.isConnected()) {
          com.my.mealse.util.WifiDirectManager.Peer remote = wdm.getRemotePeer();
          if (remote != null && remote.deviceId != null && !remote.deviceId.isEmpty()) {
            wdConnectedDeviceId = remote.deviceId;
          }
        }
      }
    } catch (Exception e) {
      log.warn("enqueue: getWifiDirectManager: " + e.getMessage());
    }
    if (wdConnectedDeviceId != null) {
      log.log("enqueue: WD подключён к deviceId=" + wdConnectedDeviceId);
    }

    for (Participant p : participants) {
      if (isSelf(p, selfFp, selfDeviceId))
        continue;

      String nodeKey = p.getNodeKey();

      // ★ если WD подключён к тому же deviceId — приоритет WD-nodeKey
      if (wdConnectedDeviceId != null && wdConnectedDeviceId.equals(p.getDeviceId())) {
        String wdKey = findWifiDirectNodeKey(appCtx, p.getDeviceId(), p.getFingerprint());
        if (wdKey != null && !wdKey.isEmpty()) {
          log.log("enqueue: participant deviceId=" + p.getDeviceId() + " → приоритет WD-nodeKey="
              + wdKey + " (было nodeKey=" + nodeKey + ")");
          nodeKey = wdKey;
        }
      }

      if (nodeKey == null || nodeKey.isEmpty()) {
        log.warn("enqueue: у participant нет nodeKey, пропускаем");
        continue;
      }
      String outboxId = outboxRepo.enqueue(messageId, conversationId, nodeKey, p.getFingerprint());
      if (outboxId != null) {
        DeliveryScheduler.get(appCtx).enqueue(outboxId);
        enqueued++;
      }
    }

    // ★ НОВОЕ: failover существующих pending на WD.
    // Если WD подключён — перекладываем HTTP-nodeKey на WD-nodeKey.
    if (wdConnectedDeviceId != null) {
      try {
        failoverPendingToWifiDirect(appCtx, log, outboxRepo, participants, wdConnectedDeviceId);
      } catch (Exception e) {
        log.warn("enqueue: failoverPendingToWifiDirect: " + e.getMessage());
      }
    }

    log.log("enqueue: msg=" + shortId(messageId) + ", targets=" + enqueued + ", callId="
        + shortId(callId));
  }

  /**
   * ★ НОВОЕ: перекладывает pending-сообщения с HTTP-nodeKey на WD-nodeKey для участников, к чьему deviceId подключён WD.
   *
   * Использует OutboxRepository.getByTarget(httpNodeKey) — стандартный метод. Для каждого pending-сообщения: remove → enqueue с WD-nodeKey.
   */
  private static void failoverPendingToWifiDirect(Context appCtx, MealseLogger log,
      OutboxRepository outboxRepo, List<Participant> participants, String wdConnectedDeviceId) {
    if (participants == null || participants.isEmpty())
      return;

    // Собираем все HTTP-nodeKey участников, к чьему deviceId подключён WD.
    java.util.Set<String> httpNodeKeys = new java.util.HashSet<>();
    for (Participant p : participants) {
      if (p.getDeviceId() == null || !wdConnectedDeviceId.equals(p.getDeviceId()))
        continue;
      String httpKey = p.getNodeKey();
      if (httpKey != null && !httpKey.isEmpty())
        httpNodeKeys.add(httpKey);
    }
    if (httpNodeKeys.isEmpty())
      return;

    for (String httpKey : httpNodeKeys) {
      List<com.my.mealse.messenger.data.entity.OutboxEntity> pending = outboxRepo
          .getByTarget(httpKey);
      if (pending == null || pending.isEmpty())
        continue;

      // WD-nodeKey для того же deviceId
      String wdKey = null;
      for (Participant p : participants) {
        if (p.getDeviceId() == null || !wdConnectedDeviceId.equals(p.getDeviceId()))
          continue;
        wdKey = findWifiDirectNodeKey(appCtx, p.getDeviceId(), p.getFingerprint());
        if (wdKey != null && !wdKey.isEmpty())
          break;
      }
      if (wdKey == null || wdKey.isEmpty() || wdKey.equals(httpKey))
        continue;

      int moved = 0;
      for (com.my.mealse.messenger.data.entity.OutboxEntity e : pending) {
        try {
          outboxRepo.remove(e.id);
          String newId = outboxRepo.enqueue(e.messageId, e.conversationId, wdKey,
              e.targetFingerprint);
          if (newId != null) {
            DeliveryScheduler.get(appCtx).enqueue(newId);
            moved++;
          }
        } catch (Exception ex) {
          log.warn("failoverPendingToWifiDirect: " + shortId(e.id) + " — " + ex.getMessage());
        }
      }
      if (moved > 0) {
        log.log("failoverPendingToWifiDirect: " + httpKey + " → " + wdKey + ", moved=" + moved);
      }
    }
  }

  /**
   * Ищет nodeKey ("mac:0") WD-узла по deviceId или fingerprint. Возвращает null, если WD-узла нет в NodeManager.
   */
  @Nullable
  private static String findWifiDirectNodeKey(Context appCtx, @Nullable String deviceId,
      @Nullable String fingerprint) {
    try {
      NodeManager nm = new NodeManager(appCtx);
      List<TrustedNode> byDevice = deviceId != null && !deviceId.isEmpty()
          ? nm.findAllByDeviceId(deviceId)
          : new ArrayList<>();
      for (TrustedNode n : byDevice) {
        if (n.getMode() == TrustedNode.Mode.WIFI_DIRECT && n.getIpAddress() != null
            && !n.getIpAddress().isEmpty()) {
          return n.getKey(); // "mac:0"
        }
      }
      List<TrustedNode> byFp = fingerprint != null && !fingerprint.isEmpty()
          ? nm.findAllByFingerprint(fingerprint)
          : new ArrayList<>();
      for (TrustedNode n : byFp) {
        if (n.getMode() == TrustedNode.Mode.WIFI_DIRECT && n.getIpAddress() != null
            && !n.getIpAddress().isEmpty()) {
          return n.getKey();
        }
      }
    } catch (Exception ignored) {
    }
    return null;
  }

  // ============================================================
  // Отправка ack READ
  // ============================================================

  private static void sendReadAck(Context appCtx, MealseLogger log, Message m) {
    try {
      if (m == null)
        return;
      String toFingerprint = m.getSenderFingerprint();
      if (toFingerprint == null || toFingerprint.isEmpty())
        return;

      NodeManager nm = new NodeManager(appCtx);
      TrustedNode target = nm.findByFingerprint(toFingerprint);
      if (target == null && m.getSenderDeviceId() != null && !m.getSenderDeviceId().isEmpty()) {
        List<TrustedNode> byDevice = nm.findAllByDeviceId(m.getSenderDeviceId());
        if (!byDevice.isEmpty())
          target = byDevice.get(0);
      }
      if (target == null || target.getIpAddress() == null || target.getIpAddress().isEmpty()) {
        log.warn("sendReadAck: узел не найден для " + shortId(m.getId()));
        return;
      }

      String nodeKey = target.getKey();
      MessagePayload payload = PayloadCodec.readAck(m.getId(), System.currentTimeMillis() / 1000L);
      String json = PayloadCodec.encode(payload);
      if (json == null)
        return;
      JsonObject payloadObj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();

      String selfFp = getSelfFingerprint(appCtx);
      String selfDeviceId = getSelfDeviceId(appCtx);

      MessageEnvelope ack = MessageEnvelope.build(selfDeviceId, selfFp, toFingerprint,
          m.getConversationId(), m.getId(), false, payloadObj);

      MessageTransport transport = new MessageTransport(appCtx);
      transport.send(nodeKey, ack, new MessageSender.Callback() {
        @Override
        public void onResult(SendResult result) {
          if (result.isSuccess()) {
            log.log("sendReadAck OK: " + shortId(m.getId()) + " → " + nodeKey);
          } else {
            log.warn("sendReadAck FAIL: " + shortId(m.getId()) + " — " + result.getErrorMessage());
          }
        }
      });
    } catch (Exception e) {
      log.logError("sendReadAck", e);
    }
  }

  // ============================================================
  // Хелперы
  // ============================================================

  private static boolean isSelf(Participant p, String selfFp, String selfDeviceId) {
    if (p.getFingerprint() != null && !p.getFingerprint().isEmpty()
        && p.getFingerprint().equals(selfFp)) {
      return true;
    }
    if (p.getDeviceId() != null && !p.getDeviceId().isEmpty()
        && p.getDeviceId().equals(selfDeviceId)) {
      return true;
    }
    return false;
  }

  private static String getSelfFingerprint(Context appCtx) {
    try {
      String fp = new SecureAuthManager(appCtx).getDeviceFingerprint();
      return fp != null ? fp : "";
    } catch (Exception e) {
      return "";
    }
  }

  private static String getSelfDeviceId(Context appCtx) {
    try {
      String id = DeviceInfoHelper.getDeviceId(appCtx);
      return id != null ? id : "";
    } catch (Exception e) {
      return "";
    }
  }

  private static String shortId(String id) {
    if (id == null)
      return "?";
    return id.length() > 8 ? id.substring(0, 8) : id;
  }
}