package com.my.mealse.browser;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Настройки браузера.
 *
 * Хранится в SharedPreferences "mealse_browser_prefs".
 *
 * ★ Ключи:
 *   - home_url — домашняя страница (по умолчанию "https://www.yandex.ru/")
 *   - search_engine — "yandex" | "google" | "duckduckgo" (по умолчанию "yandex")
 *   - user_agent_mode — "mobile" | "desktop" (по умолчанию "mobile")
 *   - adblock_enabled — блокировка рекламы (по умолчанию ВЫКЛ)
 *
 * ★ Шлюз не хранится в этой модели. Он читается из mealse_tunnel_prefs:
 *   mode == "client" + gateway_ip != "" → шлюз включён.
 *
 * ★ Adblock-движок и статистика вынесены в пакет browser.proxy
 *   (AdblockEngine, AdblockStats, AdblockRules).
 */
public final class BrowserSettings {

  public static final String PREF_NAME = "mealse_browser_prefs";

  public static final String KEY_HOME_URL = "home_url";
  public static final String KEY_SEARCH_ENGINE = "search_engine";
  public static final String KEY_USER_AGENT_MODE = "user_agent_mode";
  public static final String KEY_ADBLOCK_ENABLED = "adblock_enabled";

  public static final String DEFAULT_HOME_URL = "https://www.yandex.ru/";
  public static final String DEFAULT_SEARCH_ENGINE = "yandex";
  public static final String DEFAULT_USER_AGENT_MODE = "mobile";
  public static final boolean DEFAULT_ADBLOCK_ENABLED = false;

  private final SharedPreferences prefs;

  public BrowserSettings(Context context) {
    this.prefs = context.getApplicationContext()
        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
  }

  // ===== Home =====
  public String getHomeUrl() {
    String url = prefs.getString(KEY_HOME_URL, DEFAULT_HOME_URL);
    return (url == null || url.isEmpty()) ? DEFAULT_HOME_URL : url;
  }

  public void setHomeUrl(String url) {
    prefs.edit().putString(KEY_HOME_URL,
        (url == null || url.isEmpty()) ? DEFAULT_HOME_URL : url).apply();
  }

  // ===== Search =====
  public String getSearchEngine() {
    String s = prefs.getString(KEY_SEARCH_ENGINE, DEFAULT_SEARCH_ENGINE);
    return (s == null || s.isEmpty()) ? DEFAULT_SEARCH_ENGINE : s;
  }

  public void setSearchEngine(String engine) {
    prefs.edit().putString(KEY_SEARCH_ENGINE,
        (engine == null || engine.isEmpty()) ? DEFAULT_SEARCH_ENGINE : engine).apply();
  }

  // ===== UA =====
  public String getUserAgentMode() {
    String m = prefs.getString(KEY_USER_AGENT_MODE, DEFAULT_USER_AGENT_MODE);
    return (m == null || m.isEmpty()) ? DEFAULT_USER_AGENT_MODE : m;
  }

  public void setUserAgentMode(String mode) {
    prefs.edit().putString(KEY_USER_AGENT_MODE,
        (mode == null || mode.isEmpty()) ? DEFAULT_USER_AGENT_MODE : mode).apply();
  }

  // ===== Adblock =====
  public boolean isAdblockEnabled() {
    return prefs.getBoolean(KEY_ADBLOCK_ENABLED, DEFAULT_ADBLOCK_ENABLED);
  }

  public void setAdblockEnabled(boolean enabled) {
    prefs.edit().putBoolean(KEY_ADBLOCK_ENABLED, enabled).apply();
  }

  // ===== Search URL =====
  public String buildSearchUrl(String query) {
    if (query == null) query = "";
    String encoded;
    try {
      encoded = java.net.URLEncoder.encode(query, "UTF-8");
    } catch (Exception e) {
      encoded = query;
    }
    switch (getSearchEngine()) {
      case "google":
        return "https://www.google.com/search?q=" + encoded;
      case "duckduckgo":
        return "https://duckduckgo.com/?q=" + encoded;
      case "yandex":
      default:
        return "https://yandex.ru/search/?text=" + encoded;
    }
  }

  // ===== Normalize =====
  public String normalizeInput(String input) {
    if (input == null) return "";
    String s = input.trim();
    if (s.isEmpty()) return "";

    if (s.startsWith("http://") || s.startsWith("https://")
        || s.startsWith("file://") || s.startsWith("about:")) {
      return s;
    }
    if (s.equals("localhost") || s.startsWith("localhost:")) {
      return "http://" + s;
    }
    if (!s.contains(" ") && s.contains(".")) {
      return "https://" + s;
    }
    if (s.matches("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?$")) {
      return "http://" + s;
    }
    return buildSearchUrl(s);
  }
}