package com.my.mealse.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Управляет настройками ICE (STUN + TURN) для WebRTC.
 *
 * Источники TURN-серверов: 1. Пользовательский TURN (задаётся в SettingsActivity) 2. Центральный сервер (опционально, задаётся в SettingsActivity)
 *
 * ★ ИЗМЕНЕНО (приватность + STUN 701): DEFAULT_USE_TURN = false. Раньше по умолчанию был включён публичный openrelay.metered.ca — медиа потенциально уходило на чужой сервер, плюс в LAN-звонке ICE собирал лишние relay-кандидаты (2-5 с задержки на старте). Теперь TURN — только если пользователь явно включил в SettingsActivity (или задан центральный сервер).
 *
 * buildIceConfigJsonForMode("WIFI_DIRECT") → {"stun":[]} (как GATEWAY). Раньше WIFI_DIRECT попадал в AUTO → STUN+TURN → STUN 701 в логах (21:24:47) и задержка ICE. Теперь WD = host-only.
 *
 * Режимы: AUTO — STUN + TURN (если пользователь включил) DIRECT — только STUN (P2P, TURN не используется) GATEWAY — без STUN/TURN (только host внутри VPN-подсети) WIFI_DIRECT — без STUN/TURN (только host в WD-подсети)
 */
public class IceConfigManager {

  private static final String TAG = "IceConfigManager";
  private static final String PREF_NAME = "mealse_ice_config";

  private static final String KEY_USE_TURN = "use_turn";
  private static final String KEY_TURN_URL = "turn_url";
  private static final String KEY_TURN_USERNAME = "turn_username";
  private static final String KEY_TURN_CREDENTIAL = "turn_credential";

  // ★ центральный сервер (VPS TURN)
  private static final String KEY_CENTRAL_URL = "central_url";
  private static final String KEY_CENTRAL_USERNAME = "central_username";
  private static final String KEY_CENTRAL_CREDENTIAL = "central_credential";

  /**
   * ★ ИЗМЕНЕНО: DEFAULT_USE_TURN = false. Раньше true + openrelay.metered.ca по умолчанию — утечка медиа на сторонний сервер и лишние relay-кандидаты в LAN. Теперь TURN выключен по умолчанию; пользователь включает явно.
   */
  private static final boolean DEFAULT_USE_TURN = false;

  /**
   * ★ ИЗМЕНЕНО: DEFAULT_TURN_URL = "" (пусто), а не openrelay. Если пользователь включит TURN, но не задаст URL — TURN не будет добавлен (buildIceConfigJsonForMode проверит url.isEmpty()). Раньше здесь был openrelay.metered.ca — даже при выключенном USE_TURN поле оставалось, и логи/настройки показывали чужой сервер.
   */
  private static final String DEFAULT_TURN_URL = "";
  private static final String DEFAULT_TURN_USERNAME = "";
  private static final String DEFAULT_TURN_CREDENTIAL = "";

  private final SharedPreferences prefs;
  private final MealseLogger log;

  public IceConfigManager(Context context) {
    this.prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME,
        Context.MODE_PRIVATE);
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
  // Центральный сервер (VPS TURN)
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
    prefs.edit().remove(KEY_CENTRAL_URL).remove(KEY_CENTRAL_USERNAME).remove(KEY_CENTRAL_CREDENTIAL)
        .apply();
    log.log("clearCentralServer: центральный сервер сброшен");
  }

  // ============================================================
  // Сброс
  // ============================================================

  /**
   * ★ ИЗМЕНЕНО: resetToDefaults больше не включает openrelay. DEFAULT_USE_TURN = false, DEFAULT_TURN_URL = "" — после сброса TURN выключен, URL пуст. Пользователь задаёт TURN явно.
   */
  public void resetToDefaults() {
    prefs.edit().putBoolean(KEY_USE_TURN, DEFAULT_USE_TURN)
        .putString(KEY_TURN_URL, DEFAULT_TURN_URL)
        .putString(KEY_TURN_USERNAME, DEFAULT_TURN_USERNAME)
        .putString(KEY_TURN_CREDENTIAL, DEFAULT_TURN_CREDENTIAL).remove(KEY_CENTRAL_URL)
        .remove(KEY_CENTRAL_USERNAME).remove(KEY_CENTRAL_CREDENTIAL).apply();
    log.log("resetToDefaults: TURN выключен (use_turn=false, url=\"\"), центральный сервер очищен");
  }

  // ============================================================
  // Сборка JSON для call.html
  // ============================================================

  /**
   * Формирует полный ICE-конфиг (STUN + все TURN) для call.html. Не учитывает режим — используется по умолчанию (AUTO).
   *
   * Формат: { "stun": ["stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"], "turn": { "url": "...", "username": "...", "credential": "..." }, "turns": [ { "url": "...", "username": "...", "credential": "..." } ] }
   *
   * Если задан только один TURN — отправляем ключ "turn" (обратная совместимость). Если больше одного — отправляем "turns" (массив).
   */
  public String buildIceConfigJson() {
    return buildIceConfigJsonForMode("AUTO", null, 0);
  }

  /**
   * ★ ИЗМЕНЕНО: WIFI_DIRECT → {"stun":[]} (host-only), как GATEWAY.
   *
   * Раньше WIFI_DIRECT не обрабатывался → попадал в AUTO → STUN+TURN → STUN 701 в логах (21:24:47) и задержка ICE 2-5 с (сбор srflx-кандидатов, которые в WD-подсети не нужны).
   *
   * Теперь: WIFI_DIRECT = host-only, без STUN/TURN. Соответствует call.html.configureIceMode('WIFI_DIRECT') → iceServers = [] (правка 4.2).
   *
   * @param mode        "AUTO" | "DIRECT" | "GATEWAY" | "WIFI_DIRECT"
   * @param gatewayIp   IP шлюза (только для логирования)
   * @param gatewayPort порт шлюза (только для логирования)
   */
  public String buildIceConfigJsonForMode(String mode, String gatewayIp, int gatewayPort) {
    log.log("buildIceConfigJsonForMode START: mode=" + mode + ", gatewayIp=" + gatewayIp
        + ", gatewayPort=" + gatewayPort);

    String m = (mode != null) ? mode.toUpperCase() : "AUTO";

    // ★ ИЗМЕНЕНО: WIFI_DIRECT обрабатывается как GATEWAY — host-only
    if ("GATEWAY".equals(m) || "WIFI_DIRECT".equals(m)) {
      log.log("buildIceConfigJsonForMode: " + m + " → пустой конфиг (только host)");
      return "{\"stun\":[]}";
    }

    StringBuilder sb = new StringBuilder(512);
    sb.append("{\"stun\":[").append("\"stun:stun.l.google.com:19302\",")
        .append("\"stun:stun1.l.google.com:19302\"").append("]");

    // DIRECT: только STUN, без TURN
    if ("DIRECT".equals(m)) {
      sb.append("}");
      String result = sb.toString();
      log.log("buildIceConfigJsonForMode: DIRECT → " + result);
      return result;
    }

    // AUTO: STUN + TURN (если пользователь включил)
    boolean anyTurnAdded = false;

    // Основной TURN (пользовательский)
    if (isTurnEnabled()) {
      String url = getTurnUrl();
      String username = getTurnUsername();
      String credential = getTurnCredential();
      if (url != null && !url.isEmpty()) {
        sb.append(",\"turn\":{").append("\"url\":\"").append(escapeJson(url)).append("\",")
            .append("\"username\":\"").append(escapeJson(username != null ? username : ""))
            .append("\",").append("\"credential\":\"")
            .append(escapeJson(credential != null ? credential : "")).append("\"}");
        anyTurnAdded = true;
        log.log("buildIceConfigJsonForMode: добавлен основной TURN " + url);
      }
    }

    // Центральный сервер (второй TURN)
    if (isCentralServerEnabled()) {
      String url = getCentralServerUrl();
      String username = getCentralServerUsername();
      String credential = getCentralServerCredential();
      if (url != null && !url.isEmpty()) {
        if (!anyTurnAdded) {
          // Если основного TURN нет — используем "turn"
          sb.append(",\"turn\":{").append("\"url\":\"").append(escapeJson(url)).append("\",")
              .append("\"username\":\"").append(escapeJson(username != null ? username : ""))
              .append("\",").append("\"credential\":\"")
              .append(escapeJson(credential != null ? credential : "")).append("\"}");
        } else {
          // Иначе — добавляем в массив "turns"
          sb.append(",\"turns\":[{").append("\"url\":\"").append(escapeJson(url)).append("\",")
              .append("\"username\":\"").append(escapeJson(username != null ? username : ""))
              .append("\",").append("\"credential\":\"")
              .append(escapeJson(credential != null ? credential : "")).append("\"}]");
        }
        log.log("buildIceConfigJsonForMode: добавлен центральный TURN " + url);
      }
    }

    sb.append("}");
    String result = sb.toString();
    log.log("buildIceConfigJsonForMode: результат = " + result);
    return result;
  }

  /**
   * ★ ИЗМЕНЕНО: экранирует не только \\ и ", но и control chars
   * (\n, \r, \t, и \u0000-\u001F). Раньше username/credential с \n
   * давали невалидный JSON. Для TURN-креденшелов (base64/hex) —
   * обычно не нужно, но защита от кривого ввода.
   */
  private String escapeJson(String s) {
    if (s == null) return "";
    StringBuilder sb = new StringBuilder(s.length() + 8);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '\\': sb.append("\\\\"); break;
        case '"':  sb.append("\\\""); break;
        case '\n': sb.append("\\n"); break;
        case '\r': sb.append("\\r"); break;
        case '\t': sb.append("\\t"); break;
        default:
          if (c < 0x20) {
          // control char — формат U+XXXX
          sb.append(String.format("\\u%04x", (int) c));
        } else {
            sb.append(c);
          }
      }
    }
    return sb.toString();
  }
}