package com.my.mealse.filemanager.wifidirect;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Формат кадра для мультиплексированного канала Wi-Fi Direct.
 *
 * ┌─────────────────────────────────────────────────────────────┐
 * │ uint32 total_length  │  длина payload + 1 (байт msg_type)   │
 * ├─────────────────────────────────────────────────────────────┤
 * │ uint8  msg_type      │  0x01..0x06 (см. TYPE_*)             │
 * ├─────────────────────────────────────────────────────────────┤
 * │ bytes  payload       │  зависит от msg_type                 │
 * └─────────────────────────────────────────────────────────────┘
 *
 * Для FS_CHUNK payload имеет структуру:
 *   [uint32 req_id][uint64 offset][uint16 chunk_size][chunk_size байт данных]
 *
 * Для FS_CANCEL payload:
 *   [uint32 req_id]
 *
 * Для остальных — UTF-8 JSON.
 *
 * Все числа — big-endian (java.nio.ByteBuffer по умолчанию).
 */
public final class WifiDirectFrame {

    private WifiDirectFrame() {}

    // ===== Типы сообщений =====
    public static final int TYPE_JSON_SIGNAL    = 0x01;
    public static final int TYPE_FS_REQUEST     = 0x02;
    public static final int TYPE_FS_RESPONSE    = 0x03;
    public static final int TYPE_FS_CHUNK       = 0x04;
    public static final int TYPE_FS_ERROR       = 0x05;
    public static final int TYPE_FS_CANCEL      = 0x06;

    // ===== Ограничения =====
    public static final int MAX_FRAME_SIZE = 8 * 1024 * 1024; // 8 МБ — JSON ≤ это, чанки ≤ 64 КБ
    public static final int MAX_CHUNK_DATA = 64 * 1024;       // 64 КБ данных в FS_CHUNK

    // ===== Хедер FS_CHUNK =====
    private static final int CHUNK_HEADER = 4 + 8 + 2; // req_id + offset + size

    // ============================================================
    //  Упаковка
    // ============================================================

    /**
     * Собирает кадр из msg_type и payload.
     * Возвращает готовый к отправке массив байт.
     */
    public static byte[] pack(int msgType, byte[] payload) {
        if (payload == null) payload = new byte[0];
        int total = 1 + payload.length;
        ByteBuffer buf = ByteBuffer.allocate(4 + total);
        buf.putInt(total);
        buf.put((byte) (msgType & 0xFF));
        buf.put(payload);
        return buf.array();
    }

    /**
     * Упаковка JSON-кадра (JSON_SIGNAL / FS_REQUEST / FS_RESPONSE / FS_ERROR).
     */
    public static byte[] packJson(int msgType, String json) {
        if (json == null) json = "{}";
        return pack(msgType, json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Упаковка FS_CHUNK.
     */
    public static byte[] packChunk(int reqId, long offset, byte[] data, int dataLen) {
        if (data == null) data = new byte[0];
        if (dataLen <= 0 || dataLen > data.length) dataLen = data.length;

        ByteBuffer buf = ByteBuffer.allocate(4 + 1 + CHUNK_HEADER + dataLen);
        buf.putInt(1 + CHUNK_HEADER + dataLen);
        buf.put((byte) TYPE_FS_CHUNK);
        buf.putInt(reqId);
        buf.putLong(offset);
        buf.putShort((short) dataLen);
        buf.put(data, 0, dataLen);
        return buf.array();
    }

    /**
     * Упаковка FS_CANCEL.
     */
    public static byte[] packCancel(int reqId) {
        ByteBuffer buf = ByteBuffer.allocate(4 + 1 + 4);
        buf.putInt(1 + 4);
        buf.put((byte) TYPE_FS_CANCEL);
        buf.putInt(reqId);
        return buf.array();
    }

    // ============================================================
    //  Разбор — статические утилиты для читателя
    // ============================================================

    /**
     * Считывает 4-байтовый total_length из начала буфера.
     * Возвращает -1, если байт недостаточно.
     */
    public static int readTotalLength(byte[] header4) {
        if (header4 == null || header4.length < 4) return -1;
        return ByteBuffer.wrap(header4).getInt();
    }

    /**
     * Разбирает полностью прочитанный кадр (после того как readTotalLength уже
     * отработал и ты прочитал total_length байт).
     *
     * @param payload байты без 4-байтового префикса длины (первый байт — msg_type)
     */
    public static Frame parse(byte[] payload) {
        if (payload == null || payload.length < 1) return null;
        int msgType = payload[0] & 0xFF;
        byte[] body = new byte[payload.length - 1];
        System.arraycopy(payload, 1, body, 0, body.length);
        return new Frame(msgType, body);
    }

    /**
     * Извлекает JSON-строку из тела кадра JSON-типов.
     */
    public static String bodyToJson(byte[] body) {
        if (body == null || body.length == 0) return "{}";
        return new String(body, StandardCharsets.UTF_8);
    }

    /**
     * Разбирает FS_CHUNK body в структуру.
     */
    public static ChunkData parseChunk(byte[] body) {
        if (body == null || body.length < CHUNK_HEADER) return null;
        ByteBuffer buf = ByteBuffer.wrap(body);
        int reqId = buf.getInt();
        long offset = buf.getLong();
        int dataLen = buf.getShort() & 0xFFFF;
        if (dataLen < 0 || buf.remaining() < dataLen) return null;
        byte[] data = new byte[dataLen];
        buf.get(data);
        return new ChunkData(reqId, offset, data);
    }

    /**
     * Разбирает FS_CANCEL body.
     */
    public static int parseCancel(byte[] body) {
        if (body == null || body.length < 4) return -1;
        return ByteBuffer.wrap(body).getInt();
    }

    // ============================================================
    //  Структуры
    // ============================================================

    public static class Frame {
        public final int type;
        public final byte[] body;

        public Frame(int type, byte[] body) {
            this.type = type;
            this.body = body;
        }

        public boolean isJsonSignal() { return type == TYPE_JSON_SIGNAL; }
        public boolean isFsRequest()  { return type == TYPE_FS_REQUEST; }
        public boolean isFsResponse() { return type == TYPE_FS_RESPONSE; }
        public boolean isFsChunk()    { return type == TYPE_FS_CHUNK; }
        public boolean isFsError()    { return type == TYPE_FS_ERROR; }
        public boolean isFsCancel()   { return type == TYPE_FS_CANCEL; }
    }

    public static class ChunkData {
        public final int reqId;
        public final long offset;
        public final byte[] data;

        public ChunkData(int reqId, long offset, byte[] data) {
            this.reqId = reqId;
            this.offset = offset;
            this.data = data;
        }
    }
}