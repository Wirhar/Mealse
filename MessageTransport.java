package com.my.mealse.messenger.transport;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.my.mealse.model.TrustedNode;
import com.my.mealse.util.MealseLogger;
import com.my.mealse.util.NodeManager;
import com.my.mealse.util.WifiDirectManager;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * Фасад транспорта: выбирает HttpMessageSender / WifiDirectMessageSender по targetNode.mode.
 *
 * ★ send (JSON): TEXT / ack / candidate и т.п. - mode == WIFI_DIRECT → WifiDirectMessageSender (JSON-кадр TYPE_JSON_SIGNAL) - иначе → HttpMessageSender (POST /signal/<type>)
 *
 * ★ sendFile (бинарный): FILE через /signal/file (HTTP) или чанки (WD). - mode == WIFI_DIRECT → FileChunkProtocol: файл режется на чанки по 512 КБ raw, каждый — отдельный JSON {type:"file_chunk", ...} через WifiDirectManager.sendSignal. - иначе → FileMessageSender (HTTP POST /signal/file, streaming 64 КБ буфер, с ProgressCallback).
 *
 * ★ Улучшение №4: WD-ветка через чанки. Было: base64 всего файла в один JSON-кадр. Для файла 6+ МБ base64 > 8 МБ (WifiDirectFrame.MAX_FRAME_SIZE) → receiveLoop на B видит frameLength > MAX_MSG_SIZE → break → соединение рвётся.
 *
 * Стало: FileChunkProtocol (CHUNK_SIZE = 512 КБ). Каждый чанк — отдельный JSON {type:"file_chunk", message_id, chunk_index, total_chunks, ..., data_base64: ~683 КБ}. Кадр ≤ 8 МБ гарантированно.
 *
 * Порядок НЕ важен: B собирает через RandomAccessFile.seek( chunk_index * CHUNK_SIZE). Даже 1,3,2,0,4 — файл соберётся.
 *
 * Callback: только на последнем чанке (для DeliveryScheduler.onSendResult). Промежуточные — noop (fire-and-forget, ошибки не отслеживаем). Прогресс для WD не показываем (20 чанков × 100мс = 2с, нет смысла).
 *
 * Ack DELIVERED придёт от MessageReceiver после сборки. Если не придёт — DeliveryScheduler.markAttempt → retry whole file (outbox).
 */
public class MessageTransport {

  private static final String TAG = "MessengerTransport";

  private final Context appContext;
  private final MealseLogger log;
  private final NodeManager nodeManager;
  private final WifiDirectManager wdm;
  private final HttpMessageSender httpSender;
  private final WifiDirectMessageSender wdSender;
  private final FileMessageSender fileSender;

  /** Noop-callback для промежуточных чанков (fire-and-forget). */
  private static final MessageSender.Callback NOOP_CALLBACK = result -> {
  };

  public MessageTransport(Context context) {
    this.appContext = context.getApplicationContext();
    this.log = MealseLogger.get(appContext, TAG);
    this.nodeManager = new NodeManager(appContext);

    WifiDirectManager w = null;
    try {
      if (appContext instanceof com.my.mealse.MealseApp) {
        w = ((com.my.mealse.MealseApp) appContext).getWifiDirectManager();
      }
    } catch (Exception e) {
      log.warn("getWifiDirectManager: " + e.getMessage());
    }
    this.wdm = w;
    this.httpSender = new HttpMessageSender(appContext);
    this.wdSender = new WifiDirectMessageSender(appContext, wdm);
    this.fileSender = new FileMessageSender(appContext);
  }

  // ============================================================
  // send (JSON) — TEXT / ack / candidate
  // ============================================================

  public void send(@NonNull String targetNodeKey, @NonNull MessageEnvelope envelope,
      @NonNull MessageSender.Callback callback) {
    sendRaw(targetNodeKey, envelope.toJson(), callback);
  }

  public void sendRaw(@NonNull String targetNodeKey, @NonNull String envelopeJson,
      @NonNull MessageSender.Callback callback) {
    TrustedNode target = findNode(targetNodeKey);

    // ★ ФИКС: если запись узла HTTP-шная, но WD подключён к тому же deviceId —
    // используем WD. Иначе в WD-сети IP из обычной Wi-Fi недоступен.
    if (shouldUseWifiDirect(target)) {
      log.log("send: " + targetNodeKey + " → Wi-Fi Direct (target.mode="
          + (target != null ? target.getMode() : "?") + ", WD connected to same deviceId)");
      wdSender.send(targetNodeKey, envelopeJson, callback);
      return;
    }

    if (target != null && target.getMode() == TrustedNode.Mode.WIFI_DIRECT) {
      log.log("send: " + targetNodeKey + " → Wi-Fi Direct (mode=WIFI_DIRECT)");
      wdSender.send(targetNodeKey, envelopeJson, callback);
    } else {
      log.log("send: " + targetNodeKey + " → HTTP (mode="
          + (target != null ? target.getMode() : "unknown") + ")");
      httpSender.send(targetNodeKey, envelopeJson, callback);
    }
  }

  /**
   * ★ ФИКС: true, если: - WD-соединение установлено (wdm.isConnected()); - remotePeer.deviceId совпадает с target.deviceId (то же физическое устройство); - target.mode != WIFI_DIRECT (иначе сработает обычная WD-ветка). Это позволяет отправлять по WD, даже если в NodeManager узел записан как HTTP.
   */
  private boolean shouldUseWifiDirect(@Nullable TrustedNode target) {
    if (target == null)
      return false;
    if (target.getMode() == TrustedNode.Mode.WIFI_DIRECT)
      return false;
    if (wdm == null || !wdm.isConnected())
      return false;

    try {
      WifiDirectManager.Peer remote = wdm.getRemotePeer();
      if (remote == null)
        return false;
      String targetDeviceId = target.getDeviceId();
      if (targetDeviceId == null || targetDeviceId.isEmpty())
        return false;
      return targetDeviceId.equals(remote.deviceId);
    } catch (Exception e) {
      return false;
    }
  }

  // ============================================================
  // sendFile — перегрузка без прогресса
  // ============================================================

  public void sendFile(@NonNull String targetNodeKey, @NonNull MessageEnvelope envelope,
      @NonNull File file, @NonNull MessageSender.Callback callback) {
    sendFile(targetNodeKey, envelope, file, null, callback);
  }

  // ============================================================
  // sendFile — с прогрессом (HTTP) / без прогресса (WD)
  // ============================================================

  /**
   * Отправить файл.
   *
   * HTTP (mode != WIFI_DIRECT): FileMessageSender.sendFile(..., progress, callback) — streaming, прогресс каждые 100 мс.
   *
   * WD (mode == WIFI_DIRECT): FileChunkProtocol — цикл чанков 512 КБ через WifiDirectManager.sendSignal. progress игнорируется. callback срабатывает только на последнем чанке.
   */
  public void sendFile(@NonNull String targetNodeKey, @NonNull MessageEnvelope envelope,
      @NonNull File file, @Nullable FileMessageSender.ProgressCallback progress,
      @NonNull MessageSender.Callback callback) {
    TrustedNode target = findNode(targetNodeKey);

    if (shouldUseWifiDirect(target)) {
      log.log(
          "sendFile: " + targetNodeKey + " → Wi-Fi Direct (чанки, WD connected to same deviceId)");
      sendFileWifiDirect(targetNodeKey, envelope, file, callback);
      return;
    }

    if (target != null && target.getMode() == TrustedNode.Mode.WIFI_DIRECT) {
      log.log("sendFile: " + targetNodeKey + " → Wi-Fi Direct (чанки)");
      sendFileWifiDirect(targetNodeKey, envelope, file, callback);
    } else {
      log.log("sendFile: " + targetNodeKey + " → HTTP /signal/file");
      fileSender.sendFile(targetNodeKey, envelope.toJson(), file, progress, callback);
    }
  }

  // ============================================================
  // ★ Улучшение №4: Wi-Fi Direct — чанки
  // ============================================================

  /**
   * Отправка файла чанками через WD.
   *
   * Шаги: 1. Проверки: file существует, size > 0, size ≤ MAX_FILE_SIZE. 2. totalChunks = FileChunkProtocol.chunkCount(size). 3. FileInputStream + буфер CHUNK_SIZE (512 КБ). 4. Цикл: read → buildChunkJson → wdSender.send (noop на промежуточных, callback на последнем) → Thread.sleep(100) между кадрами.
   *
   * Ошибки: - file.length() > MAX_FILE_SIZE → error. - totalChunks == 0 → error. - IOException → error. - wdSender.send на последнем чанке вернёт error (WD отключился) → callback.onResult(error) → DeliveryScheduler.markAttempt → retry.
   */
  /**
   * ★ ИЗМЕНЕНО (правка E, финал — под реальную сигнатуру buildChunkJson): отправка файла чанками через Wi-Fi Direct.
   *
   * Сохранено из исходного рабочего кода: - вызов buildChunkJson(message_id, conversation_id, chunkIndex, totalChunks, fileName, fileMime, size, from_device_id, from_fingerprint, to_fingerprint, sentAt, chunk) — 13 параметров; - проверка wdm.isConnected() на КАЖДОМ чанке (WD мог отвалиться посреди передачи → сразу error, не «долбим» мёртвый сокет); - NOOP_CALLBACK для промежуточных чанков, реальный callback — для последнего (чтобы DeliveryScheduler узнал результат); - лог «чанк i/N (rB, json=...chars) [LAST]»; - общий try/catch с elapsed в SendResult.error.
   *
   * Добавлено (правка E): 1. Проверка queued от wdSender.send для КАЖДОГО чанка, а не только для последнего. Раньше промежуточные шли через NOOP_CALLBACK — ошибка «не поставлен в очередь» терялась, и мы продолжали слать оставшиеся чанки в переполненный/мёртвый сокет → обрыв WD. Теперь при queued=false — прерываем цикл и возвращаем error → DeliveryScheduler.markAttempt → retry whole file. 2. Дочитываем чанк до want (в цикле), а не одним in.read(buf). Причина: FileChunkProtocol.buildChunkJson отвергает chunkBytes.length > CHUNK_SIZE (ок), но на последнем чанке read < CHUNK_SIZE — это нормально; а вот если InputStream вернёт меньше want на ПРОМЕЖУТОЧНОМ чанке, то offsetFor() на B разъедется и файл соберётся битым. Дочитывание в цикле это исключает.
   *
   * CHUNK_SIZE и CHUNK_SEND_DELAY_MS уже уменьшены/увеличены в FileChunkProtocol (правки D/E), здесь их не дублируем.
   */
  /**
   * ★ ИЗМЕНЕНО (правка E, финал): отправка файла чанками через Wi-Fi Direct.
   *
   * Проверено под приложенные файлы: - FileChunkProtocol.buildChunkJson — 13 параметров, сигнатура совпадает; - FileChunkProtocol.CHUNK_SIZE = 256 КБ, CHUNK_SEND_DELAY_MS = 150, MAX_FILE_SIZE = 20 МБ, chunkCount(long), offsetFor(int) — консистентны; - MessageSender.Callback — SAM (единственный onResult) → лямбда ок; - SendResult.error(String, String, long) и SendResult.isSuccess() — есть; - MessageTransport: поля wdm, wdSender, log; импорт java.util.Arrays.
   *
   * Сохранено из исходного рабочего кода: - 13-параметрический buildChunkJson; - проверка wdm.isConnected() на КАЖДОМ чанке; - NOOP-семантика для промежуточных чанков (реальный callback — только на последнем, чтобы DeliveryScheduler узнал результат); - лог «чанк i/N (rB, json=...chars) [LAST]»; - общий try/catch с elapsed в SendResult.error.
   *
   * Добавлено (правка E): 1. Проверка queued от wdSender.send для КАЖДОГО чанка. Раньше промежуточные шли через NOOP_CALLBACK — ошибка «не поставлен в очередь» терялась, и мы продолжали слать оставшиеся чанки в переполненный/мёртвый сокет → обрыв WD. Теперь queued=false → error → DeliveryScheduler.markAttempt → retry whole file. 2. Дочитываем чанк до want в цикле (а не одним in.read(buf)), иначе на медленном InputStream промежуточный чанк короче CHUNK_SIZE → offsetFor() на B разъедется → файл битый. 3. lock.wait(2000) обёрнут в try/catch (InterruptedException) — иначе метод не компилируется (wait бросает checked-исключение).
   *
   * CHUNK_SIZE = 256 КБ и CHUNK_SEND_DELAY_MS = 150 уже в FileChunkProtocol (правки D/E), здесь не дублируются.
   */
  private void sendFileWifiDirect(@NonNull String targetNodeKey, @NonNull MessageEnvelope envelope,
      @NonNull File file, @NonNull MessageSender.Callback callback) {
    long start = System.currentTimeMillis();

    if (!file.exists() || !file.isFile()) {
      callback
          .onResult(SendResult.error("wifidirect", "file not found: " + file.getAbsolutePath(), 0));
      return;
    }
    long size = file.length();
    if (size <= 0) {
      callback.onResult(SendResult.error("wifidirect", "file empty", 0));
      return;
    }
    if (size > FileChunkProtocol.MAX_FILE_SIZE) {
      callback.onResult(SendResult.error("wifidirect",
          "file too large: " + size + "B > " + FileChunkProtocol.MAX_FILE_SIZE + "B", 0));
      return;
    }

    // ★ Используем this.wdm (поле класса), как в исходном рабочем коде.
    if (wdm == null || !wdm.isConnected()) {
      callback.onResult(SendResult.error("wifidirect", "Wi-Fi Direct не подключён",
          System.currentTimeMillis() - start));
      return;
    }

    int totalChunks = FileChunkProtocol.chunkCount(size);
    if (totalChunks <= 0) {
      callback.onResult(SendResult.error("wifidirect", "totalChunks == 0 (size=" + size + ")", 0));
      return;
    }

    String fileName = file.getName();
    String fileMime = com.my.mealse.messenger.model.AttachmentInfo.guessMimeByName(fileName);
    long sentAt = envelope.timestamp;

    log.log("sendFileWifiDirect: " + fileName + ", " + size + "B, " + totalChunks + " чанков × "
        + FileChunkProtocol.CHUNK_SIZE + "B, target=" + targetNodeKey);

    try (InputStream in = new BufferedInputStream(new FileInputStream(file), 64 * 1024)) {
      byte[] buf = new byte[FileChunkProtocol.CHUNK_SIZE];
      for (int i = 0; i < totalChunks; i++) {
        // ★ Проверяем соединение на каждом чанке — если WD упал
        // посреди передачи, нет смысла продолжать.
        if (wdm == null || !wdm.isConnected()) {
          log.warn("sendFileWifiDirect: WD отвалился на чанке " + i + "/" + totalChunks);
          callback.onResult(SendResult.error("wifidirect",
              "Wi-Fi Direct disconnected mid-transfer at chunk " + i, 0));
          return;
        }

        // Читаем ровно want байт (последний чанк — короче).
        // Без цикла дочитывания на медленном InputStream read() мог
        // вернуть меньше CHUNK_SIZE на промежуточном чанке → offsetFor()
        // на B разъедется → файл соберётся битым.
        int want = (int) Math.min(buf.length, size - (long) i * FileChunkProtocol.CHUNK_SIZE);
        int read = 0;
        while (read < want) {
          int r = in.read(buf, read, want - read);
          if (r < 0)
            break;
          read += r;
        }
        if (read != want) {
          log.warn("sendFileWifiDirect: read " + read + " != want " + want + " на чанке " + i + "/"
              + totalChunks + " (size=" + size + ")");
          callback.onResult(SendResult.error("wifidirect",
              "short read at chunk " + i + ": " + read + "/" + want, 0));
          return;
        }
        // buildChunkJson принимает byte[] и валидирует length <= CHUNK_SIZE;
        // на последнем чанке read < CHUNK_SIZE — это допустимо.
        byte[] chunk = (read == buf.length) ? buf : Arrays.copyOf(buf, read);

        // ★ 13-параметрическая buildChunkJson — ровно как в рабочем коде.
        String json = FileChunkProtocol.buildChunkJson(envelope.message_id,
            envelope.conversation_id, i, totalChunks, fileName, fileMime, size,
            envelope.from_device_id, envelope.from_fingerprint, envelope.to_fingerprint, sentAt,
            chunk);
        if (json == null) {
          log.warn("sendFileWifiDirect: buildChunkJson вернул null на чанке " + i);
          callback
              .onResult(SendResult.error("wifidirect", "buildChunkJson failed at chunk " + i, 0));
          return;
        }

        final boolean isLast = (i == totalChunks - 1);
        final int chunkIndex = i;

        // ★ ИЗМЕНЕНО: проверяем queued для КАЖДОГО чанка, а не только
        // для последнего. Раньше промежуточные шли через NOOP_CALLBACK —
        // ошибка «не поставлен в очередь» терялась, и мы продолжали
        // слать оставшиеся чанки в переполненный/мёртвый сокет → обрыв WD.
        // Теперь queued=false → error → DeliveryScheduler.markAttempt →
        // retry whole file (лучше retry, чем обрыв WD).
        final boolean[] queued = new boolean[] { false };
        final Object lock = new Object();
        // MessageSender.Callback — SAM (единственный onResult) → лямбда ок.
        MessageSender.Callback chunkCb = result -> {
          synchronized (lock) {
            queued[0] = result.isSuccess();
            lock.notify();
          }
          if (isLast) {
            callback.onResult(result);
          }
        };
        wdSender.send(targetNodeKey, json, chunkCb);

        // wdSender.send синхронный (wdm.sendSignal возвращает boolean,
        // callback вызывается сразу внутри send). wait(2000) — safety-net
        // на случай, если в вашей версии send асинхронный.
        // ★ wait бросает checked InterruptedException — обёрнут в try/catch.
        synchronized (lock) {
          if (!queued[0]) {
            try {
              lock.wait(2000);
            } catch (InterruptedException ie) {
              Thread.currentThread().interrupt();
              callback.onResult(SendResult.error("wifidirect",
                  "interrupted while waiting queue at chunk " + chunkIndex, 0));
              return;
            }
          }
        }
        if (!queued[0]) {
          log.warn("sendFileWifiDirect: чанк " + (chunkIndex + 1) + "/" + totalChunks
              + " НЕ поставлен в очередь — прерываем, error для retry");
          callback.onResult(
              SendResult.error("wifidirect", "chunk " + chunkIndex + " not queued (WD перегружен?)",
                  System.currentTimeMillis() - start));
          return;
        }

        log.log("sendFileWifiDirect: чанк " + (chunkIndex + 1) + "/" + totalChunks + " (" + read
            + "B, json=" + json.length() + " chars)" + (isLast ? " [LAST]" : ""));

        if (!isLast) {
          try {
            Thread.sleep(FileChunkProtocol.CHUNK_SEND_DELAY_MS);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("sendFileWifiDirect: прервано на чанке " + i);
            callback.onResult(SendResult.error("wifidirect", "interrupted at chunk " + i, 0));
            return;
          }
        }
      }

      long elapsed = System.currentTimeMillis() - start;
      log.log("sendFileWifiDirect: отправлено " + totalChunks + " чанков за " + elapsed
          + "мс (жду ack DELIVERED от получателя)");

    } catch (IOException e) {
      long elapsed = System.currentTimeMillis() - start;
      log.logError("sendFileWifiDirect: IOException", e);
      callback.onResult(SendResult.error("wifidirect", "read file: " + e.getMessage(), elapsed));
    } catch (Exception e) {
      long elapsed = System.currentTimeMillis() - start;
      log.logError("sendFileWifiDirect: Exception", e);
      callback.onResult(SendResult.error("wifidirect",
          e.getMessage() != null ? e.getMessage() : "unknown error", elapsed));
    }
  }

  // ============================================================
  // Хелперы
  // ============================================================

  @Nullable
  private TrustedNode findNode(String nodeKey) {
    if (nodeKey == null)
      return null;
    int idx = nodeKey.lastIndexOf(':');
    if (idx <= 0)
      return null;
    String ip = nodeKey.substring(0, idx);
    int port;
    try {
      port = Integer.parseInt(nodeKey.substring(idx + 1));
    } catch (NumberFormatException e) {
      return null;
    }
    return nodeManager.findByIpPort(ip, port);
  }
}