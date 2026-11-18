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
  public static final long MAX_ATTACHMENT_BYTES = 20L * 1024 * 1024;

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

  @Nullable
  public static String sendFile(@NonNull Context context, @NonNull String conversationId,
      @NonNull AttachmentInfo attachment) {
    return sendFile(context, conversationId, attachment, null);
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
  @Nullable
  public static String sendFile(@NonNull Context context, @NonNull String conversationId,
      @NonNull AttachmentInfo attachment, @Nullable String callId) {
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
    if (attachment.getSizeBytes() > MAX_ATTACHMENT_BYTES) {
      log.warn("sendFile: файл больше лимита");
      return null;
    }

    File srcFile = attachment.getLocalFile();
    if (srcFile == null) {
      log.warn("sendFile: исходный файл исчез " + attachment.getLocalPath());
      return null;
    }

    String messageId = UUID.randomUUID().toString();
    long now = System.currentTimeMillis();

    // ★ ФИКС: копируем СИНХРОННО, чтобы вызывающий мог удалить tmp сразу
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
        ConversationRepository convRepo = new ConversationRepository(appCtx);
        MessageRepository msgRepo = new MessageRepository(appCtx);
        ParticipantRepository partRepo = new ParticipantRepository(appCtx);
        OutboxRepository outboxRepo = new OutboxRepository(appCtx);

        Conversation conv = convRepo.getById(conversationId);
        if (conv == null) {
          log.warn("sendFile: диалог не найден " + conversationId);
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

        List<Message> all = msgRepo.getAllByConversation(conversationId);
        long now = System.currentTimeMillis();
        String selfFp = getSelfFingerprint(appCtx);

        int marked = 0;
        for (Message m : all) {
          if (!selfFp.isEmpty() && selfFp.equals(m.getSenderFingerprint())) {
            continue;
          }
          if (m.getStatus() == MessageStatus.DELIVERED) {
            msgRepo.markRead(m.getId(), now);
            marked++;
            sendReadAck(appCtx, log, m);
          } else if (m.getStatus() == MessageStatus.SENT) {
            msgRepo.markRead(m.getId(), now);
            marked++;
            sendReadAck(appCtx, log, m);
          }
        }
        log.log("markRead: conv=" + shortId(conversationId) + ", marked=" + marked);
      } catch (Exception e) {
        log.logError("markRead error", e);
      }
    });
  }

  // ============================================================
  // Внутренние: enqueue для всех участников
  // ============================================================

  /**
   * Раскладывает одно сообщение в outbox для каждого участника (кроме self).
   *
   * ★ ИСПРАВЛЕНО (шаг 17.1): nodeKey для участника выбирается с приоритетом Wi-Fi Direct, если к этому же deviceId есть активное WD-соединение. Раньше при participants.nodeKey = HTTP (192.168.1.123:8081), а WD был подключён — первые попытки летели по HTTP, падали (ConnectException), и только после 2 retry (44 сек) шли через WD.
   */
  private static void enqueueForAllParticipants(Context appCtx, MealseLogger log,
      ConversationRepository convRepo, ParticipantRepository partRepo, OutboxRepository outboxRepo,
      String conversationId, String messageId, String selfFp, String selfDeviceId,
      @Nullable String callId) {
    List<Participant> participants = partRepo.getByConversation(conversationId);
    int enqueued = 0;

    // ★ НОВОЕ: получаем WD-соединение один раз на всех участников.
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

      // ★ НОВОЕ: если WD подключён к тому же deviceId, что и участник —
      // приоритет WD-nodeKey. Это резко ускоряет доставку, пока HTTP-попытки
      // будут падать (например, узел в другой подсети / Wi-Fi отключён).
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
    log.log("enqueue: msg=" + shortId(messageId) + ", targets=" + enqueued + ", callId="
        + shortId(callId));
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