package com.my.mealse.messenger.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.google.gson.JsonObject;
import com.my.mealse.messenger.MessengerEvents;
import com.my.mealse.messenger.crypto.MessageCipher;
import com.my.mealse.messenger.crypto.MessagePayload;
import com.my.mealse.messenger.crypto.PayloadCodec;
import com.my.mealse.messenger.data.entity.ConversationEntity;
import com.my.mealse.messenger.mapper.ConversationMapper;
import com.my.mealse.messenger.model.Conversation;
import com.my.mealse.messenger.model.Message;
import com.my.mealse.messenger.model.MessageStatus;
import com.my.mealse.messenger.model.MessageType;
import com.my.mealse.messenger.notify.IncomingMessageNotifier;
import com.my.mealse.messenger.repo.ConversationRepository;
import com.my.mealse.messenger.repo.MessageRepository;
import com.my.mealse.messenger.repo.ParticipantRepository;
import com.my.mealse.messenger.storage.AttachmentStorage;
import com.my.mealse.messenger.transport.FileChunkProtocol;
import com.my.mealse.messenger.transport.MessageEnvelope;
import com.my.mealse.messenger.transport.MessageSender;
import com.my.mealse.messenger.transport.MessageTransport;
import com.my.mealse.messenger.transport.SendResult;
import com.my.mealse.model.TrustedNode;
import com.my.mealse.util.DeviceInfoHelper;
import com.my.mealse.util.MealseLogger;
import com.my.mealse.util.NodeManager;
import com.my.mealse.util.SecureAuthManager;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.my.mealse.messenger.model.ConversationType;
import com.my.mealse.messenger.model.ParticipantRole;

import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Приём входящих сообщений из broadcast com.my.mealse.SIGNAL.
 *
 * Логика onReceive: 1. Проверяем, что JSON похож на message ИЛИ file_chunk. 2. ★ Улучшение №4: file_chunk → handleFileChunk (сборка через RandomAccessFile). 3. Иначе — MessageEnvelope, дедупликация, self-loop, сохранение, broadcast, notify, ack.
 *
 * ★ Шаг 16.8.6: FILE через /signal/file (localPath приоритетнее base64). ★ Улучшение №4: FILE через WD-чанки (type="file_chunk"): - каждый чанк = отдельный JSON 512 КБ raw (base64 ~683 КБ); - tmp = getCacheDir()/incoming/<msgId>_<name>.part; - RandomAccessFile.seek(chunkIndex * CHUNK_SIZE) → write(data); - Set<Integer> received — трекаем пришедшие индексы; - когда received.size() == totalChunks → moveFromFile + MessageEntity(FILE) + ack.
 *
 * Таймаут 60с: если после первого чанка за 60с не все — удалить .part, удалить tracker, ничего не создавать. A: SENT (не DELIVERED) → retry → FAILED.
 */
public class MessageReceiver extends BroadcastReceiver {

  private static final String TAG = "MessengerReceiver";

  private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "MessengerReceiver");
    t.setDaemon(true);
    return t;
  });

  /**
   * ★ Улучшение №4: трекеры сборки чанков (msgId → tracker). Доступ — только из EXECUTOR (single-thread), но ConcurrentHashMap защищает от возможных гонок при будущих изменениях.
   */
  private static final ConcurrentHashMap<String, ChunkTracker> CHUNK_TRACKERS = new ConcurrentHashMap<>();

  @Override
  public void onReceive(Context context, Intent intent) {
    if (context == null || intent == null)
      return;

    final String action = intent.getAction();
    final Context appCtx = context.getApplicationContext();
    final MealseLogger log = MealseLogger.get(appCtx, TAG);

    log.log("📥 onReceive: action=" + action);

    if (!"com.my.mealse.SIGNAL".equals(action)) {
      log.log("onReceive: чужой action — пропуск");
      return;
    }

    String json = intent.getStringExtra("signal_json");
    if (json == null)
      json = intent.getStringExtra("raw");
    if (json == null) {
      log.warn("onReceive: нет signal_json/raw в intent");
      return;
    }

    boolean looksMessage = MessageEnvelope.looksLikeMessage(json);
    boolean looksChunk = FileChunkProtocol.isFileChunk(json);

    log.log("onReceive: json.length=" + json.length() + ", looksMessage=" + looksMessage
        + ", looksChunk=" + looksChunk + ", head="
        + (json.length() > 120 ? json.substring(0, 120) : json));

    // Быстрый фильтр: message ИЛИ file_chunk
    if (!looksMessage && !looksChunk) {
      log.warn("onReceive: не message и не file_chunk — пропуск");
      return;
    }

    final String finalJson = json;

    EXECUTOR.execute(() -> {
      try {
        handleMessage(appCtx, finalJson);
      } catch (Exception e) {
        MealseLogger.get(appCtx, TAG).logError("handleMessage", e);
      }
    });
  }

  // ============================================================
  // Основная обработка
  // ============================================================

  private void handleMessage(Context appCtx, String json) {
    MealseLogger log = MealseLogger.get(appCtx, TAG);

    // ★ Улучшение №4: file_chunk → отдельная ветка
    if (FileChunkProtocol.isFileChunk(json)) {
      handleFileChunk(appCtx, log, json);
      return;
    }

    MessageEnvelope envelope = MessageEnvelope.fromJson(json);
    if (envelope == null) {
      log.warn("handleMessage: envelope == null");
      return;
    }
    if (!MessageEnvelope.TYPE_MESSAGE.equals(envelope.type)) {
      log.log("handleMessage: type=" + envelope.type + " != message — пропуск");
      return;
    }

    String messageId = envelope.message_id;
    if (messageId == null || messageId.isEmpty()) {
      log.warn("handleMessage: нет message_id");
      return;
    }

    String selfFp = "";
    String selfDeviceId = "";
    try {
      selfFp = new SecureAuthManager(appCtx).getDeviceFingerprint();
      if (selfFp == null)
        selfFp = "";
    } catch (Exception ignored) {
    }
    try {
      selfDeviceId = DeviceInfoHelper.getDeviceId(appCtx);
      if (selfDeviceId == null)
        selfDeviceId = "";
    } catch (Exception ignored) {
    }

    if (!selfFp.isEmpty() && selfFp.equals(envelope.from_fingerprint)) {
      log.log("handleMessage: self-loop по fingerprint — пропуск");
      return;
    }
    if (!selfDeviceId.isEmpty() && selfDeviceId.equals(envelope.from_device_id)) {
      log.log("handleMessage: self-loop по deviceId — пропуск");
      return;
    }

    MessageRepository msgRepo = new MessageRepository(appCtx);
    ConversationRepository convRepo = new ConversationRepository(appCtx);
    ParticipantRepository partRepo = new ParticipantRepository(appCtx);

    // ★ ПАТЧ: сначала извлекаем payload, чтобы понять, ack это или нет.
    // Ack DELIVERED/READ переиспользует message_id исходного сообщения,
    // которое на отправителе уже есть в БД → старая проверка exists()
    // отбрасывала ack как «дубликат», и статус никогда не обновлялся.
    MessagePayload payload = extractPayload(appCtx, envelope, log);
    if (payload == null) {
      log.warn("handleMessage: payload == null для " + shortId(messageId));
      return;
    }

    // Ack обрабатываем ДО дедупликации — у него свой контракт:
    // payload.messageId указывает на ОРИГИНАЛЬНОЕ сообщение, статус которого
    // нужно обновить (DELIVERED/READ). Дедуп по envelope.message_id здесь
    // неприменим.
    if (payload.isDeliveredAck()) {
      log.log("handleMessage: DELIVERED ack для " + shortId(payload.messageId) + " (envelope.msg="
          + shortId(messageId) + ")");
      handleDeliveredAck(appCtx, log, msgRepo, envelope, payload);
      return;
    }
    if (payload.isReadAck()) {
      log.log("handleMessage: READ ack для " + shortId(payload.messageId) + " (envelope.msg="
          + shortId(messageId) + ")");
      handleReadAck(appCtx, log, msgRepo, envelope, payload);
      return;
    }

    // Обычное сообщение — дедупликация по envelope.message_id
    if (msgRepo.exists(messageId)) {
      log.log("handleMessage: дубликат " + shortId(messageId) + " — пропуск");
      return;
    }

    handleRegularMessage(appCtx, log, envelope, payload, msgRepo, convRepo, partRepo, selfFp,
        selfDeviceId);
  }

  // ============================================================
  // ★ Улучшение №4: сборка чанков
  // ============================================================

  /**
   * Принимает один file_chunk, пишет в .part по смещению, трекает индексы. Когда все чанки пришли — собирает MessageEntity(FILE), moveFromFile, ack.
   */
  /**
   * Принимает один file_chunk, пишет в .part по смещению, трекает индексы. Когда все чанки пришли — собирает MessageEntity(FILE), moveFromFile, ack.
   *
   * ★ ИСПРАВЛЕНО (шаг 17.1): try/catch(Throwable) на весь метод — OOM или любое неожиданное исключение в parseChunk/writeChunkToPart/ assembleFile больше не роняет процесс. При ошибке .part и tracker удаляются, отправитель сделает retry по таймауту ack.
   */
  private void handleFileChunk(Context appCtx, MealseLogger log, String json) {
    try {
      handleFileChunkInner(appCtx, log, json);
    } catch (OutOfMemoryError oom) {
      log.error("handleFileChunk: OOM — " + oom.getMessage() + ", json.length="
          + (json != null ? json.length() : -1));
      // .part и tracker удалятся в cleanupStaleTrackers на следующем чанке
      // или в MealseApp.cleanupIncomingParts при следующем старте.
    } catch (Throwable t) {
      log.logError(
          "handleFileChunk: критическая ошибка, json.length=" + (json != null ? json.length() : -1),
          t);
    }
  }

  private void handleFileChunkInner(Context appCtx, MealseLogger log, String json) {
    // ★ НОВОЕ: чистим зависшие трекеры при каждом чанке, не только
    // при создании нового. Иначе один потерянный чанк держит tracker
    // и .part-файл 60-120с, пока не придёт следующий файл.
    maybeCleanupStaleTrackers(log);

    FileChunkProtocol.ChunkData d = FileChunkProtocol.parseChunk(json);

    if (d == null) {
      log.warn("handleFileChunk: parseChunk вернул null");
      return;
    }

    log.log("handleFileChunk: " + shortId(d.messageId) + ", chunk " + (d.chunkIndex + 1) + "/"
        + d.totalChunks + ", name=" + d.fileName + ", data="
        + (d.data != null ? d.data.length + "B" : "null"));

    // Проверка размера
    if (d.fileSize <= 0 || d.fileSize > FileChunkProtocol.MAX_FILE_SIZE) {
      log.warn("handleFileChunk: fileSize " + d.fileSize + " вне [1, "
          + FileChunkProtocol.MAX_FILE_SIZE + "]");
      return;
    }

    // Self-loop
    String selfFp = "";
    String selfDeviceId = "";
    try {
      selfFp = new SecureAuthManager(appCtx).getDeviceFingerprint();
      if (selfFp == null)
        selfFp = "";
    } catch (Exception ignored) {
    }
    try {
      selfDeviceId = DeviceInfoHelper.getDeviceId(appCtx);
      if (selfDeviceId == null)
        selfDeviceId = "";
    } catch (Exception ignored) {
    }

    if (!selfFp.isEmpty() && selfFp.equals(d.fromFingerprint)) {
      log.log("handleFileChunk: self-loop по fingerprint — пропуск");
      return;
    }
    if (!selfDeviceId.isEmpty() && selfDeviceId.equals(d.fromDeviceId)) {
      log.log("handleFileChunk: self-loop по deviceId — пропуск");
      return;
    }

    // Если сообщение уже есть — дубликат последнего чанка
    MessageRepository msgRepo = new MessageRepository(appCtx);
    if (msgRepo.exists(d.messageId)) {
      log.log("handleFileChunk: сообщение уже есть " + shortId(d.messageId) + " — пропуск");
      cleanupPartFile(appCtx, d.messageId, d.fileName, log);
      CHUNK_TRACKERS.remove(d.messageId);
      return;
    }

    long now = System.currentTimeMillis();

    // Получаем/создаём tracker
    ChunkTracker tracker = CHUNK_TRACKERS.get(d.messageId);
    if (tracker == null) {
      // Проверка таймаута соседних — если висит что-то старое
      cleanupStaleTrackers(log);

      tracker = new ChunkTracker();
      tracker.messageId = d.messageId;
      tracker.conversationId = d.conversationId;
      tracker.fileName = d.fileName;
      tracker.fileMime = d.fileMime;
      tracker.fileSize = d.fileSize;
      tracker.totalChunks = d.totalChunks;
      tracker.fromDeviceId = d.fromDeviceId;
      tracker.fromFingerprint = d.fromFingerprint;
      tracker.toFingerprint = d.toFingerprint;
      tracker.sentAt = d.sentAt;
      tracker.firstAt = now;
      tracker.lastAt = now;
      tracker.partFile = getPartFile(appCtx, d.messageId, d.fileName);
      CHUNK_TRACKERS.put(d.messageId, tracker);

      log.log("handleFileChunk: новый tracker " + shortId(d.messageId) + " (" + d.totalChunks
          + " чанков, " + d.fileSize + "B)");
    } else {
      //long now = System.currentTimeMillis();
      // ★ таймаут «по простою» вместо «на весь файл»
      if (now - tracker.lastAt > FileChunkProtocol.CHUNK_COLLECT_TIMEOUT_MS) {
        deletePartFile(tracker, log);
        CHUNK_TRACKERS.remove(d.messageId);
        return;
      }
      tracker.lastAt = now; // ★ обновляем на каждом чанке (до дедупликации)

      // ★ НОВОЕ: если первый чанк пришёл без conversation_id, а этот — с ним,
      // обновляем tracker. Иначе assembleFile создаст PRIVATE вместо CALL.
      if ((tracker.conversationId == null || tracker.conversationId.isEmpty())
          && d.conversationId != null && !d.conversationId.isEmpty()) {
        tracker.conversationId = d.conversationId;
        log.log("handleFileChunk: обновлён conversationId tracker → " + shortId(d.conversationId));
      }
      // ★ То же для fileMime / fileName (могли прийти в другом чанке)
      if ((tracker.fileMime == null || tracker.fileMime.isEmpty()) && d.fileMime != null
          && !d.fileMime.isEmpty()) {
        tracker.fileMime = d.fileMime;
      }
    }

    // Дедупликация чанка
    if (!tracker.received.add(d.chunkIndex)) {
      log.log("handleFileChunk: дубликат чанка " + d.chunkIndex + " — пропуск");
      return;
    }

    // Запись в .part по смещению
    boolean writeOk = writeChunkToPart(tracker.partFile, d.chunkIndex, d.data, log);
    if (!writeOk) {
      log.warn("handleFileChunk: writeChunkToPart failed — откат");
      tracker.received.remove(d.chunkIndex);
      return;
    }

    log.log("handleFileChunk: chunk " + (d.chunkIndex + 1) + "/" + d.totalChunks
        + " записан, received=" + tracker.received.size() + "/" + d.totalChunks);

    // Проверка: все ли чанки
    if (tracker.received.size() < d.totalChunks) {
      return; // ждём остальные
    }

    // Все получены — собираем
    log.log("handleFileChunk: все " + d.totalChunks + " чанков получены для " + shortId(d.messageId)
        + " — собираю");
    CHUNK_TRACKERS.remove(d.messageId);

    try {
      assembleFile(appCtx, log, tracker);
    } catch (Throwable e) {
      log.logError("handleFileChunk: assembleFile", e);
      deletePartFile(tracker, log);
    }
  }

  /**
   * ★ ИЗМЕНЕНО: lastCleanupAt — static volatile, а не instance.
   *
   * MessageReceiver создаётся заново на каждый вызов (new MessageReceiver() в MealseApp/ServerService). Instance-поле lastCleanupAt всегда 0 → maybeCleanupStaleTrackers запускал cleanupStaleTrackers НА КАЖДЫЙ ЧАНК (80 проходов по CHUNK_TRACKERS для файла 20 МБ). Теперь — раз в 5 с на процесс.
   */
  private static volatile long lastCleanupAt = 0L;

  /** ★ ИЗМЕНЕНО: интервал чистки — static, чтобы не дублировать в instance. */
  private static final long CLEANUP_INTERVAL_MS = 5_000L;

  /**
   * ★ ИЗМЕНЕНО: static + static volatile lastCleanupAt. MessageReceiver создаётся на каждый onReceive — instance-поле сбрасывалось в 0 → чистка на каждый чанк. Теперь — раз в 5 с на процесс.
   */
  private static void maybeCleanupStaleTrackers(MealseLogger log) {
    long now = System.currentTimeMillis();
    if (now - lastCleanupAt < CLEANUP_INTERVAL_MS)
      return;
    lastCleanupAt = now;
    cleanupStaleTrackers(log);
  }

  /**
   * Все чанки получены — переносим .part в AttachmentStorage, создаём MessageEntity(FILE), broadcast, notify, ack.
   */
  private void assembleFile(Context appCtx, MealseLogger log, ChunkTracker tracker) {
    File partFile = tracker.partFile;
    if (partFile == null || !partFile.exists() || !partFile.isFile()) {
      log.warn("assembleFile: .part не существует " + partFile);
      return;
    }

    // Проверка длины: должна быть >= fileSize (разреженный файл м.б. больше, но не меньше)
    long actualLen = partFile.length();
    if (actualLen < tracker.fileSize) {
      log.warn("assembleFile: length=" + actualLen + " < fileSize=" + tracker.fileSize
          + " — неполный файл, удаляю");
      deletePartFile(tracker, log);
      return;
    }

    // Определяем conversationId
    String conversationId = tracker.conversationId;
    ConversationRepository convRepo = new ConversationRepository(appCtx);
    if (conversationId == null || conversationId.isEmpty()) {
      if (tracker.fromFingerprint == null || tracker.fromFingerprint.isEmpty()) {
        log.warn("assembleFile: нет conversation_id и from_fingerprint");
        deletePartFile(tracker, log);
        return;
      }
      Conversation conv = convRepo.getOrCreatePrivate(tracker.fromFingerprint,
          "Устройство " + shortId(tracker.fromFingerprint));
      if (conv == null) {
        log.warn("assembleFile: не удалось создать PRIVATE");
        deletePartFile(tracker, log);
        return;
      }
      conversationId = conv.getId();
    } else {
      if (convRepo.getById(conversationId) == null) {
        if (tracker.fromFingerprint != null && !tracker.fromFingerprint.isEmpty()) {
          Conversation newConv = convRepo.getOrCreatePrivate(tracker.fromFingerprint,
              "Устройство " + shortId(tracker.fromFingerprint));
          if (newConv != null) {
            conversationId = newConv.getId();
          }
        }
        if (convRepo.getById(conversationId) == null) {
          log.warn(
              "assembleFile: диалог " + shortId(conversationId) + " не существует и не создан");
          deletePartFile(tracker, log);
          return;
        }
      }
    }

    // moveFromFile: .part → getFilesDir()/messenger/attachments/<convId>/<msgId>_<name>
    File saved = AttachmentStorage.moveFromFile(appCtx, conversationId, tracker.messageId,
        partFile);
    if (saved == null) {
      log.warn("assembleFile: moveFromFile вернул null");
      deletePartFile(tracker, log);
      return;
    }

    // Создаём MessageEntity
    long createdAt = tracker.sentAt > 0 ? tracker.sentAt * 1000L : System.currentTimeMillis();

    Message m = new Message();
    m.setId(tracker.messageId);
    m.setConversationId(conversationId);
    m.setSenderFingerprint(tracker.fromFingerprint);
    m.setSenderDeviceId(tracker.fromDeviceId);
    m.setSenderNodeKey(null);
    m.setType(MessageType.FILE);
    m.setBody(tracker.fileName);
    m.setEncryptedBody(null);
    m.setAttachmentUri(saved.getAbsolutePath());
    m.setAttachmentName(tracker.fileName);
    m.setAttachmentMime(tracker.fileMime);
    m.setAttachmentSize(saved.length());
    m.setCreatedAt(createdAt);
    m.setStatus(MessageStatus.DELIVERED);
    m.setDeliveredAt(System.currentTimeMillis());

    MessageRepository msgRepo = new MessageRepository(appCtx);
    boolean inserted = msgRepo.add(m);
    if (!inserted) {
      log.log("assembleFile: уже есть " + shortId(tracker.messageId)
          + " — moveFromFile перезаписал, ок");
      // Message уже был — но файл мог быть другим. Не критично для MVP.
      return;
    }

    convRepo.updateLastMessage(conversationId, tracker.messageId, m.getPreview(), createdAt);
    convRepo.touchUpdatedAt(conversationId);
    convRepo.incrementUnread(conversationId);

    MessengerEvents.sendMessageNew(appCtx, conversationId, tracker.messageId);
    MessengerEvents.sendConversationChanged(appCtx, conversationId);

    try {
      Conversation conv = convRepo.getById(conversationId);
      String title = conv != null ? conv.getDisplayTitle() : "Сообщение";
      int unread = conv != null ? conv.getUnreadCount() : 1;
      new IncomingMessageNotifier(appCtx).show(conversationId, title, m, unread);
    } catch (Exception e) {
      log.logError("assembleFile: notify", e);
    }

    // Ack DELIVERED — собираем MessageEnvelope из tracker
    try {
      MessageEnvelope env = new MessageEnvelope();
      env.type = MessageEnvelope.TYPE_MESSAGE;
      env.from_device_id = tracker.fromDeviceId;
      env.from_fingerprint = tracker.fromFingerprint;
      env.to_fingerprint = tracker.toFingerprint;
      env.conversation_id = conversationId;
      env.message_id = tracker.messageId;
      env.timestamp = tracker.sentAt;
      sendDeliveredAck(appCtx, log, env);
    } catch (Exception e) {
      log.logError("assembleFile: sendDeliveredAck", e);
    }

    log.log("assembleFile: ✓ " + tracker.fileName + " (" + saved.length() + "B) → "
        + saved.getAbsolutePath() + ", conv=" + shortId(conversationId) + ", msg="
        + shortId(tracker.messageId));
  }

  // ============================================================
  // .part: запись / удаление
  // ============================================================

  private File getPartFile(Context appCtx, String messageId, String fileName) {
    File incomingDir = new File(appCtx.getCacheDir(), "incoming");
    if (!incomingDir.exists())
      incomingDir.mkdirs();
    String safeName = sanitizeFileName(fileName);
    return new File(incomingDir, sanitizeFileName(messageId) + "_" + safeName + ".part");
  }

  /**
   * Пишет чанк в .part по смещению chunkIndex * CHUNK_SIZE. RandomAccessFile — файл может быть разреженным, seek заполняет нулями.
   */
  private boolean writeChunkToPart(File partFile, int chunkIndex, byte[] data, MealseLogger log) {
    if (partFile == null || data == null || data.length == 0)
      return false;
    RandomAccessFile raf = null;
    try {
      raf = new RandomAccessFile(partFile, "rw");
      long offset = FileChunkProtocol.offsetFor(chunkIndex);
      raf.seek(offset);
      raf.write(data);
      return true;
    } catch (Exception e) {
      log.logError("writeChunkToPart: " + partFile, e);
      return false;
    } finally {
      if (raf != null) {
        try {
          raf.close();
        } catch (Exception ignored) {
        }
      }
    }
  }

  /**
   * ★ ИЗМЕНЕНО: static — вызывается из static cleanupStaleTrackers.
   */
  private static void deletePartFile(ChunkTracker tracker, MealseLogger log) {
    if (tracker == null || tracker.partFile == null)
      return;
    try {
      if (tracker.partFile.exists() && tracker.partFile.delete()) {
        log.log("deletePartFile: " + tracker.partFile.getAbsolutePath());
      }
    } catch (Exception ignored) {
    }
  }

  private void cleanupPartFile(Context appCtx, String messageId, String fileName,
      MealseLogger log) {
    try {
      File partFile = getPartFile(appCtx, messageId, fileName);
      if (partFile.exists()) {
        // noinspection ResultOfMethodCallIgnored
        partFile.delete();
        log.log("cleanupPartFile: " + partFile.getAbsolutePath());
      }
    } catch (Exception ignored) {
    }
  }

  /**
   * Удаляет .part-файлы и trackers старше CHUNK_COLLECT_TIMEOUT_MS. Вызывается при создании нового tracker (редко, не на каждый чанк).
   */
  /**
   * ★ ИЗМЕНЕНО: static — не использует instance-поля, только static CHUNK_TRACKERS и deletePartFile.
   */
  private static void cleanupStaleTrackers(MealseLogger log) {
    long now = System.currentTimeMillis();
    synchronized (CHUNK_TRACKERS) {
      java.util.Iterator<java.util.Map.Entry<String, ChunkTracker>> it = CHUNK_TRACKERS.entrySet()
          .iterator();
      while (it.hasNext()) {
        ChunkTracker t = it.next().getValue();
        if (t != null && now - t.lastAt > FileChunkProtocol.CHUNK_COLLECT_TIMEOUT_MS) {
          deletePartFile(t, log);
          it.remove();
        }
      }
    }
  }

  private static String sanitizeFileName(String s) {
    if (s == null || s.isEmpty())
      return "file";
    return s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
  }

  // ============================================================
  // ChunkTracker
  // ============================================================

  private static class ChunkTracker {
    String messageId;
    String conversationId;
    String fileName;
    String fileMime;
    long fileSize;
    int totalChunks;
    String fromDeviceId;
    String fromFingerprint;
    String toFingerprint;
    long sentAt;
    long firstAt;
    long lastAt;
    File partFile;
    final Set<Integer> received = new HashSet<>();
  }

  // ============================================================
  // Ack-обработка
  // ============================================================

  private void handleDeliveredAck(Context appCtx, MealseLogger log, MessageRepository msgRepo,
      MessageEnvelope envelope, MessagePayload payload) {
    String originalMessageId = payload.messageId;
    if (originalMessageId == null || originalMessageId.isEmpty()) {
      log.warn("handleDeliveredAck: нет messageId");
      return;
    }
    Message m = msgRepo.getById(originalMessageId);
    if (m == null) {
      log.warn("handleDeliveredAck: сообщение " + shortId(originalMessageId) + " не найдено");
      return;
    }
    if (m.getStatus() == MessageStatus.DELIVERED || m.getStatus() == MessageStatus.READ) {
      return;
    }
    msgRepo.markDelivered(originalMessageId, System.currentTimeMillis());
    MessengerEvents.sendMessageStatusChanged(appCtx, m.getConversationId(), originalMessageId,
        MessageStatus.DELIVERED.toStorageString());
    log.log("handleDeliveredAck: " + shortId(originalMessageId) + " → DELIVERED");
  }

  private void handleReadAck(Context appCtx, MealseLogger log, MessageRepository msgRepo,
      MessageEnvelope envelope, MessagePayload payload) {
    String originalMessageId = payload.messageId;
    if (originalMessageId == null || originalMessageId.isEmpty())
      return;
    Message m = msgRepo.getById(originalMessageId);
    if (m == null)
      return;
    if (m.getStatus() == MessageStatus.READ)
      return;
    msgRepo.markRead(originalMessageId, System.currentTimeMillis());
    MessengerEvents.sendMessageStatusChanged(appCtx, m.getConversationId(), originalMessageId,
        MessageStatus.READ.toStorageString());
    log.log("handleReadAck: " + shortId(originalMessageId) + " → READ");
  }

  // ============================================================
  // Обычное сообщение (TEXT, FILE через /signal/file с localPath)
  // ============================================================

  private void handleRegularMessage(Context appCtx, MealseLogger log, MessageEnvelope envelope,
      MessagePayload payload, MessageRepository msgRepo, ConversationRepository convRepo,
      ParticipantRepository partRepo, String selfFp, String selfDeviceId) {
    String conversationId = envelope.conversation_id;

    // ============================================================
    // 0. ★ ИСПРАВЛЕНО (шаг 17.2): ранняя проверка дубликата ДО обработки вложения.
    // Иначе attachFileIfPresent → moveFromFile пытается переместить tmp,
    // который уже перемещён предыдущим вызовом → warning «localPath не существует».
    // ============================================================
    if (msgRepo.exists(envelope.message_id)) {
      log.log("handleRegularMessage: дубликат " + shortId(envelope.message_id)
          + " — пропуск (до attachFile)");
      // Если это FILE с localPath — tmp уже перемещён, но на всякий случай
      // удалим, если он ещё существует.
      if (payload != null && payload.localPath != null && !payload.localPath.isEmpty()) {
        try {
          File tmp = new File(payload.localPath);
          if (tmp.exists()) {
            // noinspection ResultOfMethodCallIgnored
            tmp.delete();
            log.log("handleRegularMessage: удалён tmp-дубликат " + tmp.getAbsolutePath());
          }
        } catch (Exception ignored) {
        }
      }
      return;
    }

    // ============================================================
    // 1. Определяем conversationId
    // ============================================================
    if (conversationId == null || conversationId.isEmpty()) {
      // Fallback: envelope без conversation_id (старая версия / WD base64)
      if (envelope.from_fingerprint == null || envelope.from_fingerprint.isEmpty()) {
        log.warn("handleRegularMessage: нет conversation_id и from_fingerprint");
        return;
      }
      Conversation conv = convRepo.getOrCreatePrivate(envelope.from_fingerprint,
          "Устройство " + shortId(envelope.from_fingerprint));
      if (conv == null) {
        log.warn("handleRegularMessage: не удалось создать PRIVATE (без convId)");
        return;
      }
      conversationId = conv.getId();
      log.log(
          "handleRegularMessage: создан/найден PRIVATE (без convId) " + shortId(conversationId));
      // внутри handleRegularMessage, блок "1. Определяем conversationId"
    } else {
      Conversation conv = convRepo.getById(conversationId);
      if (conv == null) {
        if (envelope.from_fingerprint == null || envelope.from_fingerprint.isEmpty()) {
          log.warn("handleRegularMessage: convId " + shortId(conversationId)
              + " не найден, но from_fingerprint пуст — пропускаем");
          return;
        }

        // ★ Ищем CALL-чат: сначала по envelope.call_id, потом по conversationId
        ConversationEntity callConv = null;
        if (envelope.call_id != null && !envelope.call_id.isEmpty()) {
          callConv = convRepo.getEntityByCallId(envelope.call_id);
          log.log("handleRegularMessage: getEntityByCallId(envelope.call_id="
              + shortId(envelope.call_id) + ") → " + (callConv != null ? callConv.id : "null"));
        }
        if (callConv == null) {
          callConv = convRepo.getEntityByCallId(conversationId);
          log.log("handleRegularMessage: getEntityByCallId(conversationId="
              + shortId(conversationId) + ") → " + (callConv != null ? callConv.id : "null"));
        }

        Conversation newConv;
        if (callConv != null) {
          // ★ Нашли CALL-чат — используем его как есть (type=CALL)
          newConv = ConversationMapper.toModel(callConv);
          log.log("handleRegularMessage: используем CALL-чат id=" + shortId(newConv.getId()));
        } else {
          // ★ НОВОЕ: перед созданием PRIVATE с явным id проверяем,
          // не занят ли этот id CALL-чатом (гонка: CallActivity.initCallConversation
          // мог создать CALL параллельно). Если занят — используем его.
          ConversationEntity existingById = convRepo.getEntityByCallId(conversationId);
          if (existingById != null) {
            newConv = ConversationMapper.toModel(existingById);
            log.log("handleRegularMessage: conversationId=" + shortId(conversationId)
                + " уже занят CALL-чатом — используем его");
          } else {
            // Fallback: создаём PRIVATE с явным id
            newConv = convRepo.getOrCreatePrivateWithId(conversationId, envelope.from_fingerprint,
                "Устройство " + shortId(envelope.from_fingerprint));
          }
        }

        if (newConv == null) {
          log.warn(
              "handleRegularMessage: не удалось создать диалог с id=" + shortId(conversationId));
          return;
        }
        conversationId = newConv.getId(); // ★ на случай, если CALL-чат нашёлся по call_id
        log.log("handleRegularMessage: создан/найден диалог с id=" + shortId(conversationId)
            + " (peer=" + shortId(envelope.from_fingerprint) + ", type=" + newConv.getType() + ")");
      }
    }

    // ============================================================
    // 2. ★ ФИКС: убедиться, что participants существуют
    // ============================================================
    ensureParticipants(appCtx, log, convRepo, partRepo, conversationId, envelope, selfFp,
        selfDeviceId);

    // ============================================================
    // 3. Тип сообщения и timestamp
    // ============================================================
    MessageType type = mapKindToType(payload.kind);

    long createdAt = payload.sentAt > 0 ? payload.sentAt * 1000L : System.currentTimeMillis();

    // ============================================================
    // 4. Собираем Message
    // ============================================================
    Message m = new Message();
    m.setId(envelope.message_id);
    m.setConversationId(conversationId);
    m.setSenderFingerprint(envelope.from_fingerprint);
    m.setSenderDeviceId(envelope.from_device_id);
    m.setSenderNodeKey(null);
    m.setType(type);
    m.setBody(payload.body);
    m.setEncryptedBody(null);
    m.setAttachmentName(payload.attachmentName);
    m.setAttachmentMime(payload.attachmentMime);
    m.setAttachmentSize(payload.attachmentSize);
    m.setCreatedAt(createdAt);
    m.setStatus(MessageStatus.DELIVERED);
    m.setDeliveredAt(System.currentTimeMillis());
    m.setReplyToMessageId(payload.replyTo);

    // ============================================================
    // 5. FILE-вложение
    // ============================================================
    if (type == MessageType.FILE) {
      attachFileIfPresent(appCtx, log, envelope, payload, conversationId, m);
    }

    // ============================================================
    // 6. Сохраняем в БД
    // ============================================================
    boolean inserted = msgRepo.add(m);
    if (!inserted) {
      log.log("handleRegularMessage: уже есть " + shortId(envelope.message_id));
      if (type == MessageType.FILE && payload.localPath != null && !payload.localPath.isEmpty()) {
        try {
          File tmp = new File(payload.localPath);
          if (tmp.exists()) {
            // noinspection ResultOfMethodCallIgnored
            tmp.delete();
            log.log("handleRegularMessage: удалён tmp-дубликат " + tmp.getAbsolutePath());
          }
        } catch (Exception ignored) {
        }
      }
      return;
    }

    // ============================================================
    // 7. Обновляем Conversation
    // ============================================================
    convRepo.updateLastMessage(conversationId, envelope.message_id, m.getPreview(), createdAt);
    convRepo.touchUpdatedAt(conversationId);
    convRepo.incrementUnread(conversationId);

    // ============================================================
    // 8. Broadcast для UI
    // ============================================================
    MessengerEvents.sendMessageNew(appCtx, conversationId, envelope.message_id);
    MessengerEvents.sendConversationChanged(appCtx, conversationId);

    // ============================================================
    // 9. Уведомление
    // ============================================================
    try {
      Conversation conv = convRepo.getById(conversationId);
      String title = conv != null ? conv.getDisplayTitle() : "Сообщение";
      int unread = conv != null ? conv.getUnreadCount() : 1;
      new IncomingMessageNotifier(appCtx).show(conversationId, title, m, unread);
    } catch (Exception e) {
      log.logError("handleRegularMessage: notify", e);
    }

    // ============================================================
    // 10. Ack DELIVERED
    // ============================================================
    sendDeliveredAck(appCtx, log, envelope);

    log.log("handleRegularMessage: сохранено msg=" + shortId(envelope.message_id) + ", conv="
        + shortId(conversationId) + ", type=" + type + ", body="
        + (payload.body != null ? payload.body.length() + " chars" : "null") + ", attachment="
        + (m.getAttachmentUri() != null ? "yes" : "no"));
  }

  /**
   * ★ ФИКС: гарантирует, что в PRIVATE-чате есть participants (self + peer).
   *
   * Проблема: если чат создан только через MessageReceiver (getOrCreatePrivateWithId / getOrCreatePrivate), participants не добавляются (это делает только ChatsListActivity при ручном создании). В итоге MessageCommands.sendText → targets=0 → сообщение остаётся PENDING.
   *
   * Решение: при получении сообщения проверяем participants и, если их нет, добавляем self (OWNER) и peer (MEMBER).
   *
   * Для GROUP-чатов participants уже добавлены при создании группы. Для CALL-чатов participants добавляются в CallActivity.initCallConversation (пока не реализовано — добавим по аналогии).
   */
  private void ensureParticipants(Context appCtx, MealseLogger log, ConversationRepository convRepo,
      ParticipantRepository partRepo, String conversationId, MessageEnvelope envelope,
      String selfFp, String selfDeviceId) {
    try {
      int count = partRepo.countByConversation(conversationId);
      if (count > 0) {
        log.log(
            "ensureParticipants: уже есть " + count + " участников для " + shortId(conversationId));
        return;
      }

      Conversation conv = convRepo.getById(conversationId);
      if (conv == null) {
        log.warn("ensureParticipants: conversation не найден " + shortId(conversationId));
        return;
      }

      ConversationType type = conv.getType();
      if (type == ConversationType.GROUP) {
        // Для GROUP participants должны были быть добавлены при создании.
        // Если нет — не восстанавливаем (не знаем полный список).
        log.warn("ensureParticipants: GROUP без participants — пропускаем");
        return;
      }

      // PRIVATE или CALL — добавляем self + peer
      String peerFp = envelope.from_fingerprint;
      String peerDeviceId = envelope.from_device_id;
      String peerName = (peerFp != null && peerFp.length() >= 8) ? "Устройство " + shortId(peerFp)
          : "Собеседник";

      // Self — OWNER
      String selfNodeKey = null; // для self nodeKey не нужен
      partRepo.add(conversationId, selfNodeKey, selfFp, selfDeviceId, "Я", ParticipantRole.OWNER);

      // Peer — MEMBER
      String peerNodeKey = null;
      if (peerFp != null && !peerFp.isEmpty()) {
        // Пытаемся найти TrustedNode по fingerprint, чтобы взять nodeKey
        try {
          com.my.mealse.model.TrustedNode peerNode = new com.my.mealse.util.NodeManager(appCtx)
              .findByFingerprint(peerFp);
          if (peerNode != null) {
            peerNodeKey = peerNode.getKey();
          }
        } catch (Exception ignored) {
        }
      }
      if (peerFp != null && !peerFp.isEmpty()) {
        partRepo.add(conversationId, peerNodeKey, peerFp, peerDeviceId, peerName,
            ParticipantRole.MEMBER);
      } else if (peerDeviceId != null && !peerDeviceId.isEmpty()) {
        // Fallback: если нет fingerprint, но есть deviceId — добавляем
        // с deviceId, чтобы sendText мог найти target по deviceId.
        partRepo.add(conversationId, peerNodeKey, null, peerDeviceId, peerName,
            ParticipantRole.MEMBER);
      } else {
        log.warn(
            "ensureParticipants: нет ни fingerprint, ни deviceId peer — " + "не добавляем peer");
      }

      int newCount = partRepo.countByConversation(conversationId);
      log.log("ensureParticipants: добавлено, теперь " + newCount + " участников для "
          + shortId(conversationId));
    } catch (Exception e) {
      log.logError("ensureParticipants: ошибка", e);
    }
  }

  // ============================================================
  // FILE через /signal/file (localPath) / WD base64
  // ============================================================

  private void attachFileIfPresent(Context appCtx, MealseLogger log, MessageEnvelope envelope,
      MessagePayload payload, String conversationId, Message m) {
    try {
      String fileName = payload.attachmentName;
      if (fileName == null || fileName.isEmpty()) {
        fileName = (payload.body != null && !payload.body.isEmpty()) ? payload.body : "file.bin";
      }

      // === 1. Приоритет: payload.localPath (HTTP /signal/file) ===
      if (payload.localPath != null && !payload.localPath.isEmpty()) {
        File tmp = new File(payload.localPath);
        if (tmp.exists() && tmp.isFile()) {
          File saved = AttachmentStorage.moveFromFile(appCtx, conversationId, envelope.message_id,
              tmp);
          if (saved != null) {
            m.setAttachmentUri(saved.getAbsolutePath());
            m.setAttachmentSize(saved.length());
            if (m.getAttachmentName() == null || m.getAttachmentName().isEmpty()) {
              m.setAttachmentName(fileName);
            }
            log.log("attachFile: localPath → AttachmentStorage: " + saved.getAbsolutePath() + " ("
                + saved.length() + "B)");
            return;
          } else {
            log.warn(
                "attachFile: moveFromFile вернул null, tmp остаётся: " + tmp.getAbsolutePath());
          }
        } else {
          log.warn("attachFile: localPath не существует: " + tmp.getAbsolutePath());
        }
      }

      // === 2. Fallback: attachmentDataBase64 (старая версия) ===
      if (envelope.payload == null) {
        log.warn("attachFile: envelope.payload == null");
        return;
      }
      if (!envelope.payload.has("attachmentDataBase64")
          || envelope.payload.get("attachmentDataBase64").isJsonNull()) {
        log.warn("attachFile: нет ни localPath, ни attachmentDataBase64");
        return;
      }

      String base64 = envelope.payload.get("attachmentDataBase64").getAsString();
      if (base64 == null || base64.isEmpty()) {
        log.warn("attachFile: пустой attachmentDataBase64");
        return;
      }

      byte[] bytes;
      try {
        bytes = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP);
      } catch (Exception e) {
        log.logError("attachFile: base64.decode", e);
        return;
      }

      if (bytes == null || bytes.length == 0) {
        log.warn("attachFile: base64.decode вернул пусто");
        return;
      }

      File saved = AttachmentStorage.saveFromBytes(appCtx, conversationId, envelope.message_id,
          fileName, bytes);
      if (saved == null) {
        log.warn("attachFile: AttachmentStorage.saveFromBytes вернул null");
        return;
      }

      m.setAttachmentUri(saved.getAbsolutePath());
      m.setAttachmentSize(bytes.length);
      if (m.getAttachmentName() == null || m.getAttachmentName().isEmpty()) {
        m.setAttachmentName(fileName);
      }

      log.log(
          "attachFile: base64 fallback → " + saved.getAbsolutePath() + " (" + bytes.length + "B)");

    } catch (Exception e) {
      log.logError("attachFile: критическая ошибка", e);
    }
  }

  // ============================================================
  // Ack DELIVERED (отправка)
  // ============================================================

  private void sendDeliveredAck(Context appCtx, MealseLogger log, MessageEnvelope original) {
    try {
      String toFingerprint = original.from_fingerprint;
      if (toFingerprint == null || toFingerprint.isEmpty()) {
        log.log("sendDeliveredAck: нет from_fingerprint — не отправляем");
        return;
      }

      NodeManager nm = new NodeManager(appCtx);
      TrustedNode target = nm.findByFingerprint(toFingerprint);
      if (target == null && original.from_device_id != null && !original.from_device_id.isEmpty()) {
        List<TrustedNode> byDevice = nm.findAllByDeviceId(original.from_device_id);
        if (!byDevice.isEmpty())
          target = byDevice.get(0);
      }

      if (target == null) {
        log.warn("sendDeliveredAck: узел не найден (fp=" + shortId(toFingerprint)
            + ") — ack не отправлен");
        return;
      }
      if (target.getIpAddress() == null || target.getIpAddress().isEmpty()) {
        log.warn("sendDeliveredAck: у узла нет IP — ack не отправлен");
        return;
      }

      String nodeKey = target.getKey();

      MessagePayload payload = PayloadCodec.deliveredAck(original.message_id,
          System.currentTimeMillis() / 1000L);
      JsonObject payloadObj = com.google.gson.JsonParser.parseString(PayloadCodec.encode(payload))
          .getAsJsonObject();

      String selfFp = "";
      String selfDeviceId = "";
      try {
        selfFp = new SecureAuthManager(appCtx).getDeviceFingerprint();
        if (selfFp == null)
          selfFp = "";
      } catch (Exception ignored) {
      }
      try {
        selfDeviceId = DeviceInfoHelper.getDeviceId(appCtx);
        if (selfDeviceId == null)
          selfDeviceId = "";
      } catch (Exception ignored) {
      }

      MessageEnvelope ack = MessageEnvelope.build(selfDeviceId, selfFp, toFingerprint,
          original.conversation_id, original.message_id, false, payloadObj);

      MessageTransport transport = new MessageTransport(appCtx);
      transport.send(nodeKey, ack, new MessageSender.Callback() {
        @Override
        public void onResult(SendResult result) {
          if (result.isSuccess()) {
            log.log("sendDeliveredAck OK: " + shortId(original.message_id) + " → " + nodeKey + " ("
                + result.getTransport() + ")");
          } else {
            log.warn("sendDeliveredAck FAIL: " + shortId(original.message_id) + " → " + nodeKey
                + " — " + result.getErrorMessage());
          }
        }
      });

    } catch (Exception e) {
      log.logError("sendDeliveredAck", e);
    }
  }

  // ============================================================
  // Извлечение payload
  // ============================================================

  private MessagePayload extractPayload(Context appCtx, MessageEnvelope envelope,
      MealseLogger log) {
    if (envelope.payload == null) {
      log.warn("extractPayload: payload == null");
      return null;
    }

    if (!envelope.encrypted) {
      try {
        return new com.google.gson.Gson().fromJson(envelope.payload, MessagePayload.class);
      } catch (Exception e) {
        log.logError("extractPayload: parse plaintext payload", e);
        return null;
      }
    }

    try {
      String b64 = envelope.payload.has("ciphertext")
          ? envelope.payload.get("ciphertext").getAsString()
          : null;
      if (b64 == null || b64.isEmpty()) {
        log.warn("extractPayload: нет ciphertext");
        return null;
      }
      byte[] blob = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP);
      MessagePayload p = MessageCipher.decodeAndDecrypt(appCtx, null, blob);
      if (p == null) {
        log.warn("extractPayload: decrypt вернул null");
      }
      return p;
    } catch (Exception e) {
      log.logError("extractPayload: decrypt", e);
      return null;
    }
  }

  private MessageType mapKindToType(String kind) {
    if (kind == null)
      return MessageType.TEXT;
    switch (kind) {
      case MessagePayload.KIND_IMAGE:
        return MessageType.IMAGE;
      case MessagePayload.KIND_FILE:
        return MessageType.FILE;
      case MessagePayload.KIND_SYSTEM:
        return MessageType.SYSTEM;
      case MessagePayload.KIND_TEXT:
      default:
        return MessageType.TEXT;
    }
  }

  private static String shortId(String id) {
    if (id == null)
      return "?";
    return id.length() > 8 ? id.substring(0, 8) : id;
  }
}