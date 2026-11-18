package com.my.mealse.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Управляет настройками ICE (STUN + TURN) для WebRTC.
 *
 * Источники TURN-серверов:
 *   1. Публичный openrelay.metered.ca (по умолчанию)
 *   2. Пользовательский TURN (задаётся в SettingsActivity)
 *   3. Центральный сервер (опционально, задаётся в SettingsActivity)
 *
 * Режимы:
 *   AUTO     — STUN + TURN (полный набор)
 *   DIRECT   — только STUN (P2P, TURN не используется)
 *   GATEWAY  — без STUN/TURN (только host-кандидаты внутри VPN-подсети)
 */
public class IceConfigManager {

  private static final String TAG = "IceConfigManager";
  private static final String PREF_NAME = "mealse_ice_config";

  private static final String KEY_USE_TURN = "use_turn";
  private static final String KEY_TURN_URL = "turn_url";
  private static final String KEY_TURN_USERNAME = "turn_username";
  private static final String KEY_TURN_CREDENTIAL = "turn_credential";

  // ★ НОВОЕ: центральный сервер (VPS TURN)
  private static final String KEY_CENTRAL_URL = "central_url";
  private static final String KEY_CENTRAL_USERNAME = "central_username";
  private static final String KEY_CENTRAL_CREDENTIAL = "central_credential";

  // Значения по умолчанию — публичный openrelay
  private static final boolean DEFAULT_USE_TURN = true;
  private static final String DEFAULT_TURN_URL = "turn:openrelay.metered.ca:80";
  private static final String DEFAULT_TURN_USERNAME = "openrelayproject";
  private static final String DEFAULT_TURN_CREDENTIAL = "openrelayproject";

  private final SharedPreferences prefs;
  private final MealseLogger log;

  public IceConfigManager(Context context) {
    this.prefs = context.getApplicationContext()
        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    this.log = MealseLogger.get(context, TAG);
    log.log("══ IceConfigManager создан ══");
  }

  // ============================================================
  // STUN + TURN (основные)
  // ============================================================

  public boolean isTurnEnabled() {
    return prefs.getBoolean(KEY_USE_TURN, DEFAULT_USE_TURN);
  }

  public void setTurnEnabled(boolean enabled) {
    prefs.edit().putBoolean(KEY_USE_TURN, enabled).apply();
    log.log("setTurnEnabled: " + enabled);
  }

  public String getTurnUrl() {
    return prefs.getString(KEY_TURN_URL, DEFAULT_TURN_URL);
  }

  public String getTurnUsername() {
    return prefs.getString(KEY_TURN_USERNAME, DEFAULT_TURN_USERNAME);
  }

  public String getTurnCredential() {
    return prefs.getString(KEY_TURN_CREDENTIAL, DEFAULT_TURN_CREDENTIAL);
  }

  public void setTurnConfig(String url, String username, String credential) {
    SharedPreferences.Editor e = prefs.edit();
    if (url != null && !url.isEmpty()) {
      e.putString(KEY_TURN_URL, url);
    }
    if (username != null && !username.isEmpty()) {
      e.putString(KEY_TURN_USERNAME, username);
    }
    if (credential != null && !credential.isEmpty()) {
      e.putString(KEY_TURN_CREDENTIAL, credential);
    }
    e.apply();
    log.log("setTurnConfig: url=" + url + ", username=" + username);
  }

  // ============================================================
  // ★ НОВОЕ: центральный сервер (VPS TURN)
  // ============================================================

  public boolean isCentralServerEnabled() {
    String url = prefs.getString(KEY_CENTRAL_URL, null);
    return url != null && !url.isEmpty();
  }

  public String getCentralServerUrl() {
    return prefs.getString(KEY_CENTRAL_URL, null);
  }

  public String getCentralServerUsername() {
    return prefs.getString(KEY_CENTRAL_USERNAME, "");
  }

  public String getCentralServerCredential() {
    return prefs.getString(KEY_CENTRAL_CREDENTIAL, "");
  }

  public void setCentralServerConfig(String url, String username, String credential) {
    SharedPreferences.Editor e = prefs.edit();
    if (url == null || url.isEmpty()) {
      e.remove(KEY_CENTRAL_URL);
      log.log("setCentralServerConfig: URL пустой — центральный сервер отключён");
    } else {
      e.putString(KEY_CENTRAL_URL, url);
      e.putString(KEY_CENTRAL_USERNAME, username != null ? username : "");
      e.putString(KEY_CENTRAL_CREDENTIAL, credential != null ? credential : "");
      log.log("setCentralServerConfig: url=" + url + ", username=" + username);
    }
    e.apply();
  }

  public void clearCentralServer() {
    prefs.edit()
        .remove(KEY_CENTRAL_URL)
        .remove(KEY_CENTRAL_USERNAME)
        .remove(KEY_CENTRAL_CREDENTIAL)
        .apply();
    log.log("clearCentralServer: центральный сервер сброшен");
  }

  // ============================================================
  // Сброс
  // ============================================================

  public void resetToDefaults() {
    prefs.edit()
        .putBoolean(KEY_USE_TURN, DEFAULT_USE_TURN)
        .putString(KEY_TURN_URL, DEFAULT_TURN_URL)
        .putString(KEY_TURN_USERNAME, DEFAULT_TURN_USERNAME)
        .putString(KEY_TURN_CREDENTIAL, DEFAULT_TURN_CREDENTIAL)
        .remove(KEY_CENTRAL_URL)
        .remove(KEY_CENTRAL_USERNAME)
        .remove(KEY_CENTRAL_CREDENTIAL)
        .apply();
    log.log("resetToDefaults: STUN/TURN сброшены на openrelay, центральный сервер очищен");
  }

  // ============================================================
  // ★ НОВОЕ: сборка JSON для call.html
  // ============================================================

  /**
   * Формирует полный ICE-конфиг (STUN + все TURN) для call.html.
   * Не учитывает режим — используется по умолчанию.
   *
   * Формат:
   * {
   *   "stun": ["stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"],
   *   "turn":  { "url": "...", "username": "...", "credential": "..." },
   *   "turns": [ { "url": "...", "username": "...", "credential": "..." } ]
   * }
   *
   * Если задан только один TURN — отправляем ключ "turn" (обратная совместимость).
   * Если больше одного — отправляем "turns" (массив).
   */
  public String buildIceConfigJson() {
    return buildIceConfigJsonForMode("AUTO", null, 0);
  }

  /**
   * ★ НОВЫЙ МЕТОД: собирает ICE-конфиг с учётом режима.
   *
   * @param mode         "AUTO" | "DIRECT" | "GATEWAY"
   * @param gatewayIp    IP шлюза (только для логирования)
   * @param gatewayPort  порт шлюза (только для логирования)
   */
  public String buildIceConfigJsonForMode(String mode, String gatewayIp, int gatewayPort) {
    log.log("buildIceConfigJsonForMode START: mode=" + mode
        + ", gatewayIp=" + gatewayIp
        + ", gatewayPort=" + gatewayPort);

    String m = (mode != null) ? mode.toUpperCase() : "AUTO";

    // GATEWAY: полностью без ICE-серверов, только host
    if ("GATEWAY".equals(m)) {
      log.log("buildIceConfigJsonForMode: GATEWAY → пустой конфиг (только host)");
      return "{\"stun\":[]}";
    }

    StringBuilder sb = new StringBuilder(512);
    sb.append("{\"stun\":[")
        .append("\"stun:stun.l.google.com:19302\",")
        .append("\"stun:stun1.l.google.com:19302\"")
        .append("]");

    // DIRECT: только STUN, без TURN
    if ("DIRECT".equals(m)) {
      sb.append("}");
      String result = sb.toString();
      log.log("buildIceConfigJsonForMode: DIRECT → " + result);
      return result;
    }

    // AUTO: STUN + TURN (если включён)
    boolean anyTurnAdded = false;

    // Основной TURN (openrelay по умолчанию)
    if (isTurnEnabled()) {
      String url = getTurnUrl();
      String username = getTurnUsername();
      String credential = getTurnCredential();
      if (url != null && !url.isEmpty()) {
        sb.append(",\"turn\":{")
            .append("\"url\":\"").append(escapeJson(url)).append("\",")
            .append("\"username\":\"").append(escapeJson(username != null ? username : ""))
            .append("\",")
            .append("\"credential\":\"").append(escapeJson(credential != null ? credential : ""))
            .append("\"}");
        anyTurnAdded = true;
        log.log("buildIceConfigJsonForMode: добавлен основной TURN " + url);
      }
    }

    // ★ НОВОЕ: центральный сервер (второй TURN)
    if (isCentralServerEnabled()) {
      String url = getCentralServerUrl();
      String username = getCentralServerUsername();
      String credential = getCentralServerCredential();
      if (url != null && !url.isEmpty()) {
        if (!anyTurnAdded) {
          // Если основного TURN нет — используем "turn"
          sb.append(",\"turn\":{")
              .append("\"url\":\"").append(escapeJson(url)).append("\",")
              .append("\"username\":\"").append(escapeJson(username != null ? username : ""))
              .append("\",")
              .append("\"credential\":\"").append(escapeJson(credential != null ? credential : ""))
              .append("\"}");
        } else {
          // Иначе — добавляем в массив "turns"
          sb.append(",\"turns\":[{")
              .append("\"url\":\"").append(escapeJson(url)).append("\",")
              .append("\"username\":\"").append(escapeJson(username != null ? username : ""))
              .append("\",")
              .append("\"credential\":\"").append(escapeJson(credential != null ? credential : ""))
              .append("\"}]");
        }
        log.log("buildIceConfigJsonForMode: добавлен центральный TURN " + url);
      }
    }

    sb.append("}");
    String result = sb.toString();
    log.log("buildIceConfigJsonForMode: результат = " + result);
    return result;
  }

  private String escapeJson(String s) {
    if (s == null) return "";
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}