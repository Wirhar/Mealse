package com.my.mealse.messenger.transport;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.google.gson.JsonObject;
import com.my.mealse.messenger.MessengerEvents;
import com.my.mealse.messenger.crypto.MessageCipher;
import com.my.mealse.messenger.crypto.MessagePayload;
import com.my.mealse.messenger.data.entity.OutboxEntity;
import com.my.mealse.messenger.model.Conversation;
import com.my.mealse.messenger.model.ConversationType;
import com.my.mealse.messenger.model.Message;
import com.my.mealse.messenger.model.MessageStatus;
import com.my.mealse.messenger.model.MessageType;
import com.my.mealse.messenger.model.Participant;
import com.my.mealse.messenger.repo.ConversationRepository;
import com.my.mealse.messenger.repo.MessageRepository;
import com.my.mealse.messenger.repo.OutboxRepository;
import com.my.mealse.messenger.repo.ParticipantRepository;
import com.my.mealse.model.TrustedNode;
import com.my.mealse.util.MealseLogger;
import com.my.mealse.util.NodeManager;

import java.io.File;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Планировщик офлайн-доставки.
 *
 * Логика: 1. enqueue(messageId) — сразу пробует отправить (nextAttemptAt=now). 2. Периодический тикер (каждые 30с) — getDue(now) → tryDeliver. 3. retryFor(targetNodeKey) — при появлении узла в сети. 4. retryAll() — BOOT_COMPLETED / WIFI_STATE_CHANGED.
 *
 * ★ Шаг 13.1: HttpMessageSender синхронный (реальный HTTP-ответ). HTTP 200 → SENT, outbox удаляется. HTTP 4xx/5xx / timeout / ConnectException → markAttempt → backoff.
 *
 * ★ Шаг 16.8.4: FILE через transport.sendFile (бинарный /signal/file). Для FILE payloadObj с base64 НЕ собирается — envelope идёт без payload, а MessageTransport решает: - mode == WIFI_DIRECT → WifiDirectMessageSender (base64, старый путь); - иначе → FileMessageSender (HTTP streaming).
 *
 * ★ Улучшение №1: fatal HTTP-коды → markFailed сразу, без retry. 413 (Payload Too Large) — размер не уменьшится. 400 (Bad Request) — payload битый, retry не поможет. 403 (Forbidden) — доступ не разрешён. Остальные (404, 5xx, code==0 — сеть) → markAttempt → backoff.
 *
 * ★ Улучшение №3: прогресс отправки для FILE. FileMessageSender.sendFile получает ProgressCallback, который пишет progress_<messageId> в SharedPreferences (mealse_messenger_progress) и шлёт broadcast MessengerEvents.ACTION_MESSAGE_PROGRESS. MessageAdapter читает progress_<messageId> и показывает ProgressBar при status == PENDING и 0 < progress < 100. onSendResult очищает progress_<messageId> (успех или фейл).
 */
public class DeliveryScheduler {

  private static final String TAG = "MessengerDelivery";

  private static final long TICK_INTERVAL_MS = 30_000L;
  private static final int BATCH_LIMIT = 20;

  /** ★ Улучшение №3: SharedPreferences для прогресса (синхронизирован с MessageAdapter). */
  public static final String PREF_PROGRESS = "mealse_messenger_progress";
  public static final String KEY_PROGRESS_PREFIX = "progress_";

  private static volatile DeliveryScheduler instance;

  public static DeliveryScheduler get(Context context) {
    if (instance == null) {
      synchronized (DeliveryScheduler.class) {
        if (instance == null) {
          instance = new DeliveryScheduler(context.getApplicationContext());
        }
      }
    }
    return instance;
  }

  private final Context appContext;
  private final MealseLogger log;
  private final OutboxRepository outboxRepo;
  private final MessageRepository messageRepo;
  private final MessageTransport transport;

  /**
   * ★ ИЗМЕНЕНО: newCachedThreadPool вместо newSingleThreadExecutor.
   *
   * Причина: MessageTransport.sendFileWifiDirect для файла 20 МБ / 256 КБ = 80 чанков × 150 мс = 12 с блокировки. При single-thread все остальные сообщения (TEXT/ack/FILE для других получателей) ждут 12 с.
   *
   * inFlight (ConcurrentHashMap) уже защищает от параллельной отправки одной и той же outbox-записи — cached pool безопасен.
   *
   * Именованные потоки — для отладки (в трейсах видно "MessengerDelivery-N").
   */
  private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
    Thread t = new Thread(r, "MessengerDelivery-" + System.nanoTime());
    t.setDaemon(true);
    return t;
  });

  private final Handler tickHandler = new Handler(Looper.getMainLooper());
  private final AtomicBoolean ticking = new AtomicBoolean(false);
  private volatile boolean started = false;

  private final ConcurrentHashMap<String, Boolean> inFlight = new ConcurrentHashMap<>();

  private DeliveryScheduler(Context context) {
    this.appContext = context;
    this.log = MealseLogger.get(appContext, TAG);
    this.outboxRepo = new OutboxRepository(appContext);
    this.messageRepo = new MessageRepository(appContext);
    this.transport = new MessageTransport(appContext);
  }

  // ============================================================
  // Публичный API
  // ============================================================

  public void start() {
    if (started)
      return;
    started = true;
    scheduleTick();
    log.log("DeliveryScheduler: started, tick=" + TICK_INTERVAL_MS + "ms");
  }

  public void stop() {
    started = false;
    tickHandler.removeCallbacksAndMessages(null);
    log.log("DeliveryScheduler: stopped");
  }

  public void enqueue(String outboxId) {
    if (outboxId == null)
      return;
    executor.execute(() -> tryDeliver(outboxId));
  }

  public void retryFor(@Nullable String targetNodeKey) {
    if (targetNodeKey == null)
      return;
    executor.execute(() -> {
      List<OutboxEntity> list = outboxRepo.getByTarget(targetNodeKey);
      log.log("retryFor: " + targetNodeKey + " → " + list.size() + " записей");
      for (OutboxEntity e : list) {
        tryDeliver(e.id);
      }
    });
  }

  public void retryAll() {
    executor.execute(() -> {
      List<OutboxEntity> list = outboxRepo.getAll();
      log.log("retryAll: " + list.size() + " записей");
      long now = System.currentTimeMillis();
      for (OutboxEntity e : list) {
        if (e.nextAttemptAt <= now) {
          tryDeliver(e.id);
        }
      }
    });
  }

  // ============================================================
  // Тикер
  // ============================================================

  private void scheduleTick() {
    if (!started)
      return;
    tickHandler.postDelayed(this::tick, TICK_INTERVAL_MS);
  }

  /**
   * ★ ВАЖНО (cached pool): ticking.compareAndSet(false, true) обязателен — без него несколько tick'ов могли бы одновременно запустить getDueLimited и создать дубли inFlight. С single-thread это было избыточно, с cached pool — критично.
   */
  private void tick() {
    if (!started)
      return;

    if (ticking.compareAndSet(false, true)) {
      executor.execute(() -> {
        try {
          long now = System.currentTimeMillis();
          List<OutboxEntity> due = outboxRepo.getDueLimited(now, BATCH_LIMIT);
          if (!due.isEmpty()) {
            log.log("tick: " + due.size() + " due-записей");
            for (OutboxEntity e : due) {
              tryDeliver(e.id);
            }
          }
        } catch (Exception e) {
          log.logError("tick error", e);
        } finally {
          ticking.set(false);
        }
      });
    }
    scheduleTick();
  }

  // ============================================================
  // Отправка одной записи
  // ============================================================

  private void tryDeliver(String outboxId) {
    if (outboxId == null)
      return;
    if (inFlight.putIfAbsent(outboxId, Boolean.TRUE) != null) {
      return;
    }
    try {
      doTryDeliver(outboxId);
    } finally {
      inFlight.remove(outboxId);
    }
  }

  private void doTryDeliver(String outboxId) {
    OutboxEntity outbox = outboxRepo.getById(outboxId);
    if (outbox == null)
      return;

    Message msg = messageRepo.getById(outbox.messageId);
    if (msg == null) {
      log.warn("doTryDeliver: message not found, removing outbox " + outboxId);
      outboxRepo.remove(outboxId);
      return;
    }
    if (msg.getStatus() == MessageStatus.DELIVERED || msg.getStatus() == MessageStatus.READ
        || msg.getStatus() == MessageStatus.FAILED) {
      log.log("doTryDeliver: message status=" + msg.getStatus() + " — удаляем outbox");
      outboxRepo.remove(outboxId);
      clearProgress(outbox.messageId);
      return;
    }

    String selfFp = getSelfFingerprint();
    String selfDeviceId = getSelfDeviceId();

    // ★ НОВОЕ: достаём callId из ConversationEntity (для CALL-чатов).
    // Outbox не хранит callId — берём из диалога. Если диалог не CALL, callId=null.
    String callId = null;
    try {
      ConversationRepository convRepo = new ConversationRepository(appContext);
      Conversation conv = convRepo.getById(outbox.conversationId);
      if (conv != null && conv.getType() == ConversationType.CALL) {
        callId = conv.getCallId();
      }
    } catch (Exception e) {
      log.warn("doTryDeliver: getCallId: " + e.getMessage());
    }

    // ============================================================
    // FILE — через transport.sendFile (с прогрессом)
    // ============================================================
    if (msg.getType() == MessageType.FILE) {
      String path = msg.getAttachmentUri();
      if (path == null || path.isEmpty()) {
        log.warn("doTryDeliver: FILE, attachmentUri пуст — markFailed");
        markFailed(outbox.messageId, outboxId);
        return;
      }
      File file = new File(path);
      if (!file.exists() || !file.isFile()) {
        log.warn("doTryDeliver: FILE, файл исчез " + path + " — markFailed");
        markFailed(outbox.messageId, outboxId);
        return;
      }
      long size = file.length();
      if (size <= 0 || size > FileMessageSender.MAX_FILE_SIZE) {
        log.warn("doTryDeliver: FILE размер " + size + " вне [1, " + FileMessageSender.MAX_FILE_SIZE
            + "] — markFailed");
        markFailed(outbox.messageId, outboxId);
        return;
      }

      MessageEnvelope envelope = MessageEnvelope.build(selfDeviceId, selfFp,
          outbox.targetFingerprint, outbox.conversationId, outbox.messageId, callId, // ★ передаём callId (может быть null)
          false, null);

      log.log("doTryDeliver: FILE attempt=" + outbox.attemptCount + ", target="
          + outbox.targetNodeKey + ", file=" + file.getName() + ", size=" + size + "B" + ", msg="
          + shortId(outbox.messageId) + ", callId=" + shortId(callId));

      final String fMessageId = outbox.messageId;
      final String fConversationId = outbox.conversationId;

      transport.sendFile(outbox.targetNodeKey, envelope, file, (written, total) -> {
        if (total <= 0)
          return;
        int pct = (int) Math.min(100, (written * 100L) / total);
        saveProgress(fMessageId, pct);
        MessengerEvents.sendMessageProgress(appContext, fConversationId, fMessageId, pct);
      }, result -> {
        clearProgress(fMessageId);
        executor.execute(() -> onSendResult(outboxId, outbox, result));
      });
      return;
    }

    // ============================================================
    // TEXT (и будущие IMAGE/SYSTEM без вложений) — обычный JSON
    // ============================================================
    MessagePayload payload = new MessagePayload();
    payload.kind = msg.getType() != null ? msg.getType().toStorageString()
        : MessagePayload.KIND_TEXT;
    payload.messageId = msg.getId();
    payload.body = msg.getBody();
    payload.replyTo = msg.getReplyToMessageId();
    payload.sentAt = msg.getCreatedAt() / 1000L;

    MessageCipher.EncodedPayload encoded = MessageCipher.encodeAndEncrypt(appContext, payload);
    if (encoded == null) {
      log.warn("doTryDeliver: encode failed, markAttempt");
      boolean exhausted = outboxRepo.markAttempt(outboxId, "encode failed");
      if (exhausted)
        markFailed(outbox.messageId, outboxId);
      return;
    }

    JsonObject payloadObj;
    try {
      if (encoded.isEncrypted()) {
        payloadObj = new JsonObject();
        payloadObj.addProperty("iv", "");
        payloadObj.addProperty("ciphertext",
            android.util.Base64.encodeToString(encoded.encryptedBlob, android.util.Base64.NO_WRAP));
      } else {
        payloadObj = com.google.gson.JsonParser.parseString(encoded.plaintextBody)
            .getAsJsonObject();
      }
    } catch (Exception e) {
      log.logError("doTryDeliver: build payloadObj", e);
      boolean exhausted = outboxRepo.markAttempt(outboxId, "payload build failed");
      if (exhausted)
        markFailed(outbox.messageId, outboxId);
      return;
    }

    MessageEnvelope envelope = MessageEnvelope.build(selfDeviceId, selfFp, outbox.targetFingerprint,
        outbox.conversationId, outbox.messageId, callId, // ★ передаём callId (может быть null)
        encoded.isEncrypted(), payloadObj);

    log.log("doTryDeliver: TEXT attempt=" + outbox.attemptCount + ", target=" + outbox.targetNodeKey
        + ", msg=" + shortId(outbox.messageId) + ", callId=" + shortId(callId));

    transport.send(outbox.targetNodeKey, envelope, result -> {
      executor.execute(() -> onSendResult(outboxId, outbox, result));
    });
  }

  // ============================================================
  // Результат отправки
  // ============================================================

  private void onSendResult(String outboxId, OutboxEntity outbox, SendResult result) {
    if (outbox != null)
      clearProgress(outbox.messageId);

    if (result.isSuccess()) {
      messageRepo.markIfCurrent(outbox.messageId, MessageStatus.SENT, MessageStatus.PENDING);
      outboxRepo.remove(outboxId);
      log.log("doTryDeliver OK: " + outboxId + " → SENT, transport=" + result.getTransport()
          + ", elapsed=" + result.getElapsedMs() + "ms");
    } else {
      int code = result.getHttpCode();

      // ★ НОВОЕ: если WD-транспорт упал, пробуем HTTP-nodeKey из participants
      if ("wifidirect".equals(result.getTransport()) && result.getErrorMessage() != null
          && result.getErrorMessage().contains("не подключён")) {
        String httpNodeKey = findHttpNodeKeyForConversation(outbox.conversationId,
            outbox.targetFingerprint);
        if (httpNodeKey != null && !httpNodeKey.equals(outbox.targetNodeKey)) {
          log.warn("doTryDeliver: WD упал, переключаюсь на HTTP " + httpNodeKey + " (было "
              + outbox.targetNodeKey + ")");
          // Пересоздаём outbox с HTTP-nodeKey
          outboxRepo.remove(outboxId);
          String newOutboxId = outboxRepo.enqueue(outbox.messageId, outbox.conversationId,
              httpNodeKey, outbox.targetFingerprint);
          if (newOutboxId != null) {
            DeliveryScheduler.get(appContext).enqueue(newOutboxId);
          }
          return;
        }
      }

      boolean fatal = (code == 413 || code == 400 || code == 403);
      log.warn("doTryDeliver FAIL: " + outboxId + " — " + result.getErrorMessage());
      if (fatal) {
        markFailed(outbox.messageId, outboxId);
        return;
      }
      boolean exhausted = outboxRepo.markAttempt(outboxId, result.getErrorMessage());
      if (exhausted)
        markFailed(outbox.messageId, outboxId);
    }
  }

  /** ★ НОВОЕ: ищет HTTP-nodeKey участника диалога (кроме self). */
  /**
   * ★ ИЗМЕНЕНО: безопасная проверка fingerprint.
   *
   * Было: if (targetFp != null && !targetFp.equals(p.getFingerprint())) continue; - если targetFp == null — берётся ЛЮБОЙ первый участник (даже не тот, кому адресовано сообщение); - p.getFingerprint() == null — targetFp.equals(null) == false, но условие !false == true → continue — ок, но неявно.
   *
   * Стало: - targetFp != null → строгое совпадение (Objects.equals); - targetFp == null → берём первого не-self участника с HTTP-узлом (разумный fallback, если outbox не сохранил targetFingerprint).
   */
  @Nullable
  private String findHttpNodeKeyForConversation(String conversationId, @Nullable String targetFp) {
    try {
      ConversationRepository convRepo = new ConversationRepository(appContext);
      Conversation conv = convRepo.getById(conversationId);
      if (conv == null)
        return null;
      ParticipantRepository partRepo = new ParticipantRepository(appContext);
      List<Participant> participants = partRepo.getByConversation(conversationId);
      if (participants == null || participants.isEmpty())
        return null;
      String selfFp = getSelfFingerprint();
      NodeManager nm = new NodeManager(appContext);

      for (Participant p : participants) {
        // Пропускаем self
        if (p.getFingerprint() != null && !p.getFingerprint().isEmpty()
            && p.getFingerprint().equals(selfFp)) {
          continue;
        }
        if (p.getDeviceId() != null && !p.getDeviceId().isEmpty()
            && p.getDeviceId().equals(getSelfDeviceId())) {
          continue;
        }
        // Если targetFp задан — только точное совпадение
        if (targetFp != null && !java.util.Objects.equals(targetFp, p.getFingerprint())) {
          continue;
        }
        // Ищем HTTP-узел по fingerprint или deviceId
        TrustedNode node = null;
        if (p.getFingerprint() != null && !p.getFingerprint().isEmpty()) {
          node = nm.findByFingerprint(p.getFingerprint());
        }
        if (node == null && p.getDeviceId() != null && !p.getDeviceId().isEmpty()) {
          List<TrustedNode> byDevice = nm.findAllByDeviceId(p.getDeviceId());
          if (byDevice != null) {
            for (TrustedNode n : byDevice) {
              if (n.getMode() != TrustedNode.Mode.WIFI_DIRECT && n.getIpAddress() != null
                  && !n.getIpAddress().isEmpty()) {
                node = n;
                break;
              }
            }
          }
        }
        if (node != null && node.getMode() != TrustedNode.Mode.WIFI_DIRECT
            && node.getIpAddress() != null && !node.getIpAddress().isEmpty()) {
          return node.getKey();
        }
      }
    } catch (Exception e) {
      log.warn("findHttpNodeKeyForConversation: " + e.getMessage());
    }
    return null;
  }

  private void markFailed(String messageId, String outboxId) {
    messageRepo.markFailed(messageId);
    outboxRepo.remove(outboxId);
    clearProgress(messageId);
    log.warn("doTryDeliver EXHAUSTED: msg=" + messageId + " → FAILED");
  }

  // ============================================================
  // Прогресс (SharedPreferences)
  // ============================================================

  private void saveProgress(String messageId, int percent) {
    if (messageId == null)
      return;
    try {
      SharedPreferences prefs = appContext.getSharedPreferences(PREF_PROGRESS,
          Context.MODE_PRIVATE);
      prefs.edit().putInt(KEY_PROGRESS_PREFIX + messageId, percent).apply();
    } catch (Exception ignored) {
    }
  }

  private void clearProgress(String messageId) {
    if (messageId == null)
      return;
    try {
      SharedPreferences prefs = appContext.getSharedPreferences(PREF_PROGRESS,
          Context.MODE_PRIVATE);
      prefs.edit().remove(KEY_PROGRESS_PREFIX + messageId).apply();
    } catch (Exception ignored) {
    }
  }

  // ============================================================
  // Хелперы
  // ============================================================

  private String getSelfFingerprint() {
    try {
      String fp = new com.my.mealse.util.SecureAuthManager(appContext).getDeviceFingerprint();
      return fp != null ? fp : "";
    } catch (Exception e) {
      return "";
    }
  }

  private String getSelfDeviceId() {
    try {
      String id = com.my.mealse.util.DeviceInfoHelper.getDeviceId(appContext);
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

  /**
   * ★ НОВОЕ: retry всех pending-сообщений для устройства по deviceId.
   *
   * В отличие от retryFor(nodeKey) — ищет по deviceId ВСЕ TrustedNode (HTTP и WD), и для каждого nodeKey дергает retryFor. Это закрывает сценарий: pending висят в HTTP-outbox, WD подключился позже — сообщения переотправляются через WD (MessageTransport.shouldUseWifiDirect сам выберет WD, если WD подключён к тому же deviceId).
   *
   * Вызывать из WifiDirectManager.notifyConnected / MealseApp listener.onConnected.
   */
  public void retryForDeviceId(@Nullable String deviceId) {
    if (deviceId == null || deviceId.isEmpty())
      return;
    executor.execute(() -> {
      try {
        NodeManager nm = new NodeManager(appContext);
        List<TrustedNode> nodes = nm.findAllByDeviceId(deviceId);
        if (nodes == null || nodes.isEmpty()) {
          log.log("retryForDeviceId: " + deviceId + " — узлов не найдено");
          return;
        }
        int total = 0;
        for (TrustedNode n : nodes) {
          String nodeKey = n.getKey();
          if (nodeKey == null || nodeKey.isEmpty())
            continue;
          List<OutboxEntity> list = outboxRepo.getByTarget(nodeKey);
          if (list == null || list.isEmpty())
            continue;
          log.log("retryForDeviceId: " + deviceId + " → " + nodeKey + ", записей=" + list.size());
          for (OutboxEntity e : list) {
            tryDeliver(e.id);
            total++;
          }
        }
        log.log("retryForDeviceId: " + deviceId + " — всего записей=" + total);
      } catch (Exception e) {
        log.logError("retryForDeviceId", e);
      }
    });
  }
}