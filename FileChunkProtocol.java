package com.my.mealse.messenger.transport;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Arrays;

/**
 * Протокол передачи файлов чанками через Wi-Fi Direct.
 *
 * ★ Улучшение №4: файлы > 5 МБ через WD.
 *
 * Проблема: WifiDirectFrame.MAX_FRAME_SIZE = 8 МБ. Base64 от 5 МБ ≈ 6.7 МБ (помещается), от 10 МБ ≈ 13.3 МБ (не помещается → receiveLoop на B видит frameLength > MAX_MSG_SIZE → break → соединение рвётся).
 *
 * Решение: файл режется на чанки по CHUNK_SIZE = 512 КБ raw. Каждый чанк — отдельный JSON-кадр через WifiDirectManager.sendSignal (TYPE_JSON_SIGNAL). Base64 от 512 КБ ≈ 683 КБ — комфортно влезает в 8 МБ.
 *
 * Порядок НЕ важен: B собирает через RandomAccessFile.seek(chunkIndex * CHUNK_SIZE). Даже если чанки пришли 1,3,2,0,4 — файл соберётся корректно.
 *
 * Формат JSON чанка: { "type": "file_chunk", "message_id": "uuid", "conversation_id": "uuid", "chunk_index": 0, "total_chunks": 20, "file_name": "photo.jpg", "file_mime": "image/jpeg", "file_size": 10485760, "from_device_id": "...", "from_fingerprint": "...", "to_fingerprint": "...", "sent_at": 1700000000, "data_base64": "...." }
 *
 * Сборка на B: - tmp = getCacheDir()/incoming/<msgId>_<name>.part - RandomAccessFile raf = new RandomAccessFile(tmp, "rw"); - raf.seek(chunkIndex * CHUNK_SIZE); raf.write(data); - при chunkIndex == totalChunks - 1 → close, moveFromFile в AttachmentStorage.
 *
 * Таймаут: если после первого чанка за CHUNK_COLLECT_TIMEOUT_MS (60с) не пришли все — удалить .part, ничего не создавать. Отправитель получит таймаут по ack → markAttempt → retry whole file (outbox).
 */
public final class FileChunkProtocol {

  private static final String TAG = "MessengerFileChunk";

  /** ★ Значение поля "type" в JSON чанка. */
  public static final String TYPE_FILE_CHUNK = "file_chunk";

  /**
   * ★ ИЗМЕНЕНО (правка D): 512 КБ → 256 КБ.
   *
   * Причина: JSON-чанк = 512 КБ raw → ~683 КБ base64. Кадр WD ~683 КБ близок к порогу 1 МБ, при котором sendRawFrame ждёт опустошения очереди 10 с. При перегрузке TCP (sendThread блокируется на write) это приводило к обрыву WD.
   *
   * 256 КБ raw → ~341 КБ base64 — кадр <1 МБ, sendRawFrame не ждёт, TCP-буфер не переполняется так быстро.
   *
   * Скорость: 6 МБ = 24 чанка × 150 мс = 3,6 с (было 12 × 100 = 1,2 с) — приемлемо, зато стабильно.
   */
  public static final int CHUNK_SIZE = 256 * 1024;

  /** ★ Пауза между кадрами (снижает риск переполнения буфера сокета). */
  /**
   * ★ ИЗМЕНЕНО (правка E): пауза между чанками 100 мс → 150 мс. Даём sendThread больше времени на запись в TCP-буфер.
   */
  public static final int CHUNK_SEND_DELAY_MS = 150;

  /** ★ Таймаут сборки на B (после первого чанка). */
  public static final long CHUNK_COLLECT_TIMEOUT_MS = 120_000L;

  /** ★ Максимальный размер файла для чанков (синхронизирован с FileMessageSender.MAX_FILE_SIZE). */
  /**
   * ★ 8 ГБ, синхронно с HTTP-путём. ⚠ BREAKING CHANGE ПРОТОКОЛА: приёмник со старым лимитом отклонит чанки. Требуется обновление ОБЕИХ сторон.
   */
  public static final long MAX_FILE_SIZE = 8L * 1024 * 1024 * 1024;

  private static final Gson GSON = new Gson();

  private FileChunkProtocol() {
  }

  // ============================================================
  // Сборка JSON чанка (на отправителе)
  // ============================================================

  /**
   * Собирает JSON одного чанка.
   *
   * @param messageId       UUID сообщения
   * @param conversationId  UUID диалога
   * @param chunkIndex      индекс чанка (0..totalChunks-1)
   * @param totalChunks     всего чанков
   * @param fileName        имя файла
   * @param fileMime        MIME
   * @param fileSize        полный размер файла
   * @param fromDeviceId    ANDROID_ID отправителя
   * @param fromFingerprint fingerprint отправителя
   * @param toFingerprint   fingerprint получателя
   * @param sentAt          timestamp (секунды)
   * @param chunkBytes      сырые байты чанка (≤ CHUNK_SIZE)
   * @return JSON или null при ошибке
   */
  @Nullable
  public static String buildChunkJson(@NonNull String messageId, @NonNull String conversationId,
      int chunkIndex, int totalChunks, @NonNull String fileName, @Nullable String fileMime,
      long fileSize, @Nullable String fromDeviceId, @Nullable String fromFingerprint,
      @Nullable String toFingerprint, long sentAt, @NonNull byte[] chunkBytes) {
    if (messageId == null || conversationId == null)
      return null;
    if (chunkBytes == null || chunkBytes.length == 0)
      return null;
    if (chunkIndex < 0 || totalChunks <= 0 || chunkIndex >= totalChunks)
      return null;
    if (chunkBytes.length > CHUNK_SIZE)
      return null;

    try {
      JsonObject o = new JsonObject();
      o.addProperty("type", TYPE_FILE_CHUNK);
      o.addProperty("message_id", messageId);
      o.addProperty("conversation_id", conversationId);
      o.addProperty("chunk_index", chunkIndex);
      o.addProperty("total_chunks", totalChunks);
      o.addProperty("file_name", fileName != null ? fileName : "file.bin");
      o.addProperty("file_mime", fileMime != null ? fileMime : "application/octet-stream");
      o.addProperty("file_size", fileSize);
      if (fromDeviceId != null)
        o.addProperty("from_device_id", fromDeviceId);
      if (fromFingerprint != null)
        o.addProperty("from_fingerprint", fromFingerprint);
      if (toFingerprint != null)
        o.addProperty("to_fingerprint", toFingerprint);
      o.addProperty("sent_at", sentAt);
      o.addProperty("data_base64",
          android.util.Base64.encodeToString(chunkBytes, android.util.Base64.NO_WRAP));
      return GSON.toJson(o);
    } catch (Exception e) {
      return null;
    }
  }

  // ============================================================
  // Разбор (на получателе)
  // ============================================================

  /**
   * Быстрый фильтр «похоже ли на file_chunk» без полного парсинга. Используется MealseApp.handleWifiDirectSignal / MessageReceiver.onReceive, чтобы отсеять чужие JSON до parseChunk.
   */
  public static boolean isFileChunk(@Nullable String json) {
    if (json == null || json.isEmpty())
      return false;
    return json.contains("\"type\":\"" + TYPE_FILE_CHUNK + "\"")
        || json.contains("\"type\": \"" + TYPE_FILE_CHUNK + "\"");
  }

  /**
   * Полный разбор JSON чанка. Возвращает null при битом JSON / отсутствии обязательных полей / неверном base64.
   */
  @Nullable
  public static ChunkData parseChunk(@Nullable String json) {
    if (json == null || json.isEmpty())
      return null;

    try {
      JsonObject o = JsonParser.parseString(json).getAsJsonObject();
      if (o == null)
        return null;
      if (!o.has("type") || !TYPE_FILE_CHUNK.equals(o.get("type").getAsString())) {
        return null;
      }
      if (!o.has("message_id") || o.get("message_id").isJsonNull())
        return null;
      if (!o.has("chunk_index") || o.get("chunk_index").isJsonNull())
        return null;
      if (!o.has("total_chunks") || o.get("total_chunks").isJsonNull())
        return null;
      if (!o.has("data_base64") || o.get("data_base64").isJsonNull())
        return null;

      ChunkData d = new ChunkData();
      d.messageId = o.get("message_id").getAsString();
      d.conversationId = optString(o, "conversation_id");
      d.chunkIndex = o.get("chunk_index").getAsInt();
      d.totalChunks = o.get("total_chunks").getAsInt();
      d.fileName = optString(o, "file_name");
      if (d.fileName == null || d.fileName.isEmpty())
        d.fileName = "file.bin";
      d.fileMime = optString(o, "file_mime");
      if (d.fileMime == null || d.fileMime.isEmpty())
        d.fileMime = "application/octet-stream";
      d.fileSize = optLong(o, "file_size", 0L);
      d.fromDeviceId = optString(o, "from_device_id");
      d.fromFingerprint = optString(o, "from_fingerprint");
      d.toFingerprint = optString(o, "to_fingerprint");
      d.sentAt = optLong(o, "sent_at", 0L);

      String b64 = o.get("data_base64").getAsString();
      if (b64 == null || b64.isEmpty())
        return null;

      try {
        d.data = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP);
      } catch (Exception e) {
        return null;
      }
      if (d.data == null || d.data.length == 0)
        return null;

      // Валидация индексов
      if (d.chunkIndex < 0 || d.totalChunks <= 0 || d.chunkIndex >= d.totalChunks) {
        return null;
      }
      if (d.data.length > CHUNK_SIZE) {
        return null;
      }

      return d;

    } catch (Exception e) {
      return null;
    }
  }

  // ============================================================
  // Утилиты
  // ============================================================

  /**
   * Сколько чанков нужно для файла размером fileSize. fileSize = 0 → 0 (пустой файл не отправляем). fileSize = 1 → 1. fileSize = CHUNK_SIZE → 1. fileSize = CHUNK_SIZE + 1 → 2.
   */
  public static int chunkCount(long fileSize) {
    if (fileSize <= 0)
      return 0;
    return (int) ((fileSize + CHUNK_SIZE - 1) / CHUNK_SIZE);
  }

  /**
   * Ожидаемое смещение чанка в файле: chunkIndex * CHUNK_SIZE. Используется на B для RandomAccessFile.seek().
   */
  public static long offsetFor(int chunkIndex) {
    return (long) chunkIndex * CHUNK_SIZE;
  }

  // ============================================================
  // POJO
  // ============================================================

  /**
   * Результат разбора JSON чанка.
   */
  public static class ChunkData {
    public String messageId;
    public String conversationId;
    public int chunkIndex;
    public int totalChunks;
    public String fileName;
    public String fileMime;
    public long fileSize;
    public String fromDeviceId;
    public String fromFingerprint;
    public String toFingerprint;
    public long sentAt;
    public byte[] data;

    public boolean isLastChunk() {
      return chunkIndex == totalChunks - 1;
    }

    public long offset() {
      return offsetFor(chunkIndex);
    }

    @Override
    public String toString() {
      return "ChunkData{" + "msgId='" + shortId(messageId) + '\'' + ", chunk=" + (chunkIndex + 1)
          + "/" + totalChunks + ", name='" + fileName + '\'' + ", size=" + fileSize + "B"
          + ", data=" + (data != null ? data.length + "B" : "null") + '}';
    }

    private static String shortId(String id) {
      if (id == null)
        return "?";
      return id.length() > 8 ? id.substring(0, 8) : id;
    }
  }

  // ============================================================
  // Приватные хелперы для Gson
  // ============================================================

  @Nullable
  private static String optString(JsonObject o, String key) {
    if (o == null || !o.has(key) || o.get(key).isJsonNull())
      return null;
    try {
      return o.get(key).getAsString();
    } catch (Exception e) {
      return null;
    }
  }

  private static long optLong(JsonObject o, String key, long def) {
    if (o == null || !o.has(key) || o.get(key).isJsonNull())
      return def;
    try {
      return o.get(key).getAsLong();
    } catch (Exception e) {
      return def;
    }
  }
}