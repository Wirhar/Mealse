package com.my.mealse.messenger.transport;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * Обёртка сообщения для транспорта.
 *
 * Видна серверу/получателю в открытом виде (нужна для маршрутизации): type = "message" from_device_id / from_fingerprint — отправитель to_fingerprint — получатель (для PRIVATE; для GROUP = null) conversation_id / message_id — маршрут и дедупликация timestamp — секунды encrypted — true если payload в ciphertext payload — { iv?, ciphertext? } или { kind, body, ... } (MVP plaintext)
 *
 * В MVP: encrypted = false, payload = JSON-объект MessagePayload. В будущем: encrypted = true, payload = { iv: base64, ciphertext: base64 }.
 *
 * Использует формат, совместимый с ServerService: JSON с полем "type".
 */
public final class MessageEnvelope {

  public static final String TYPE_MESSAGE = "message";

  private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls()
      .create();

  // === Поля (public для Gson) ===
  public String type = TYPE_MESSAGE;
  public String from_device_id;
  public String from_fingerprint;
  public String to_fingerprint;
  public String conversation_id;
  public String message_id;
  public long timestamp;
  public boolean encrypted;
  public JsonObject payload;
  public String call_id;

  public MessageEnvelope() {
  }

  /**
   * Создаёт envelope для отправки.
   *
   * @param fromDeviceId    ANDROID_ID отправителя
   * @param fromFingerprint fingerprint отправителя
   * @param toFingerprint   fingerprint получателя (null для GROUP-рассылки — но в MVP групповые отправляются per-participant с конкретным to_fingerprint, так что null редко)
   * @param conversationId  UUID диалога
   * @param messageId       UUID сообщения
   * @param encrypted       true если payload зашифрован
   * @param payload         JsonObject (MessagePayload или {iv,ciphertext})
   */
  public static MessageEnvelope build(String fromDeviceId, String fromFingerprint,
      @Nullable String toFingerprint, String conversationId, String messageId, boolean encrypted,
      JsonObject payload) {
    MessageEnvelope e = new MessageEnvelope();
    e.type = TYPE_MESSAGE;
    e.from_device_id = fromDeviceId;
    e.from_fingerprint = fromFingerprint;
    e.to_fingerprint = toFingerprint;
    e.conversation_id = conversationId;
    e.message_id = messageId;
    e.call_id = null;
    e.timestamp = System.currentTimeMillis() / 1000L;
    e.encrypted = encrypted;
    e.payload = payload;
    return e;
  }

  /**
   * ★ НОВОЕ: перегрузка build с callId (для сообщений чата звонка).
   */
  public static MessageEnvelope build(String fromDeviceId, String fromFingerprint,
      @Nullable String toFingerprint, String conversationId, String messageId,
      @Nullable String callId, boolean encrypted, JsonObject payload) {
    MessageEnvelope e = build(fromDeviceId, fromFingerprint, toFingerprint, conversationId,
        messageId, encrypted, payload);
    e.call_id = callId;
    return e;
  }

  public String toJson() {
    JsonObject o = GSON.toJsonTree(this).getAsJsonObject();
    if (!o.has("type") || o.get("type").isJsonNull()) {
      o.addProperty("type", TYPE_MESSAGE);
    }
    if (!o.has("timestamp")) {
      o.addProperty("timestamp", timestamp > 0 ? timestamp : System.currentTimeMillis() / 1000L);
    }
    if (!o.has("from_device_id") || o.get("from_device_id").isJsonNull()) {
      if (from_device_id != null)
        o.addProperty("from_device_id", from_device_id);
    }
    if (!o.has("from_fingerprint") || o.get("from_fingerprint").isJsonNull()) {
      if (from_fingerprint != null)
        o.addProperty("from_fingerprint", from_fingerprint);
    }
    // call_id: пишем только если не null (Gson с serializeNulls иначе добавит "call_id":null)
    if (call_id == null || call_id.isEmpty()) {
      o.remove("call_id");
    }
    return GSON.toJson(o);
  }

  @Nullable
  public static MessageEnvelope fromJson(String json) {
    if (json == null || json.isEmpty())
      return null;
    try {
      return GSON.fromJson(json, MessageEnvelope.class);
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * Базовый парсинг «похоже ли на сообщение» — до полного fromJson. Используется в MessageReceiver, чтобы быстро отсеять чужие сигналы.
   */
  public static boolean looksLikeMessage(String json) {
    if (json == null || json.isEmpty())
      return false;
    return json.contains("\"type\":\"message\"") || json.contains("\"type\": \"message\"");
  }

  @Override
  public String toString() {
    return "MessageEnvelope{from='" + shortFp(from_fingerprint) + '\'' + ", to='"
        + shortFp(to_fingerprint) + '\'' + ", conv='" + shortId(conversation_id) + '\'' + ", msg='"
        + shortId(message_id) + '\'' + ", encrypted=" + encrypted + ", payload="
        + (payload != null ? payload.size() + " полей" : "null") + '}';
  }

  private static String shortFp(String fp) {
    if (fp == null)
      return "null";
    return fp.length() > 8 ? fp.substring(0, 8) : fp;
  }

  private static String shortId(String id) {
    if (id == null)
      return "null";
    return id.length() > 8 ? id.substring(0, 8) : id;
  }
}