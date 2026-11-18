package com.my.mealse.browser.proxy;

import android.webkit.CookieManager;
import android.webkit.WebResourceResponse;

import com.my.mealse.util.DeviceInfoHelper;
import com.my.mealse.util.MealseLogger;
import com.my.mealse.util.SecureAuthManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * HTTP-клиент к прокси-эндпоинту /proxy/http на ServerService шлюза (порт 8081).
 *
 * Вход: WebResourceRequest.getUrl() + getMethod() + getRequestHeaders(). Выход: WebResourceResponse для WebView (status, headers, body).
 *
 * Реализовано синхронно (WebView.shouldInterceptRequest требует синхронного ответа). Таймаут — 15 сек на подключение, 30 сек на чтение.
 *
 * ★ A: Set-Cookie от шлюза применяются через CookieManager; cookies клиента отправляются в шлюз. ★ B: follow_redirects=false — WebView сам разбирается с редиректами. ★ C: Accept-Language по умолчанию (если WebView не передал). ★ E: серия ошибок → disableForSession() на 60 секунд.
 *
 * ★ G (FIX): лимит чтения ответа шлюза поднят с 2 МБ до 20 МБ — синхронизирован с ServerService.MAX_PROXY_RESPONSE_SIZE. Раньше крупные ответы (JS-бандлы, HTML с инлайн-скриптами) обрезались на 2 МБ, что приводило к JSONException: Unterminated string at character 2097152. ★ H (FIX): добавлено логирование фактических размеров сырого ответа шлюза, длины body_base64 и результата декодирования; при ошибке парсинга JSON — логируется префикс ответа (без body_base64) для диагностики.
 */
public class HttpProxyClient {

  private static final String TAG = "HttpProxyClient";

  private static final int CONNECT_TIMEOUT_MS = 15_000;
  private static final int READ_TIMEOUT_MS = 30_000;

  /**
   * ★ G: Лимит сырого ответа шлюза (JSON-обёртка, включая body_base64). Синхронизирован с ServerService.MAX_PROXY_RESPONSE_SIZE (20 МБ). Сам body_base64 занимает ~4/3 от исходного тела; 20 МБ сырого JSON покрывает тело до ~15 МБ — с запасом для JS-бандлов, HTML и изображений.
   */
  private static final int MAX_PROXY_RESPONSE_SIZE = 20 * 1024 * 1024;

  // ★ E: fallback при серии ошибок
  private static final int MAX_CONSECUTIVE_ERRORS = 2;
  private static final long DISABLE_DURATION_MS = 60_000L;

  private volatile boolean proxyDisabled = false;
  private volatile int consecutiveErrors = 0;
  private volatile long disabledUntil = 0L;

  // Заголовки, которые не прокидываем (WebView сам справится или они опасны)
  private static final String[] BLOCKED_HEADERS = { "host", "connection", "keep-alive",
      "proxy-connection", "transfer-encoding", "upgrade", "te", "trailer", "content-length",
      "content-encoding" };

  private final android.content.Context appContext;
  private final String gatewayIp;
  private final int gatewayPort;
  private final MealseLogger log;
  private final String selfFingerprint;
  private final String selfDeviceId;
  private ProxyCache cache;

  public HttpProxyClient(android.content.Context context, String gatewayIp, int gatewayPort) {
    this.appContext = context.getApplicationContext();
    this.gatewayIp = gatewayIp;
    this.gatewayPort = gatewayPort;
    this.log = MealseLogger.get(this.appContext, TAG);

    String fp = null, did = null;
    try {
      fp = new SecureAuthManager(context).getDeviceFingerprint();
    } catch (Throwable ignored) {
    }
    try {
      did = DeviceInfoHelper.getDeviceId(context);
    } catch (Throwable ignored) {
    }
    this.selfFingerprint = fp != null ? fp : "";
    this.selfDeviceId = did != null ? did : "";
    this.cache = new ProxyCache(context);
  }

  /**
   * ★ E: проверка — не отключён ли прокси (временно или на сессию).
   */
  private boolean isDisabledNow() {
    if (!proxyDisabled)
      return false;
    if (disabledUntil > 0 && System.currentTimeMillis() >= disabledUntil) {
      // Время вышло — пробуем снова
      proxyDisabled = false;
      disabledUntil = 0;
      consecutiveErrors = 0;
      log.log("proxy: re-enabled after cooldown");
      return false;
    }
    return true;
  }

  /**
   * ★ E: учёт ошибки. После N подряд — disable на 60 сек.
   */
  private void recordError() {
    consecutiveErrors++;
    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
      proxyDisabled = true;
      disabledUntil = System.currentTimeMillis() + DISABLE_DURATION_MS;
      consecutiveErrors = 0;
      log.warn("proxy: DISABLED на " + (DISABLE_DURATION_MS / 1000) + " сек после серии ошибок");
    }
  }

  /**
   * ★ E: успех — сбрасываем счётчик ошибок.
   */
  private void recordSuccess() {
    consecutiveErrors = 0;
  }

  /**
   * Выполняет прокси-запрос.
   *
   * @return WebResourceResponse или null, если прокси недоступен (тогда WebView должен загрузить напрямую).
   */
  public WebResourceResponse fetch(String method, String urlStr, Map<String, String> reqHeaders) {
    HttpURLConnection conn = null;
    if (isDisabledNow())
      return null;

    try {
      // ★ 1. Кэш HIT?
      if (cache != null) {
        WebResourceResponse cached = cache.get(method, urlStr);
        if (cached != null) {
          log.log("proxy: cache HIT " + method + " " + truncateUrl(urlStr));
          return cached;
        }
      }

      // ★ 2. Готовим запрос
      log.log("proxy: " + method + " " + truncateUrl(urlStr));

      JSONObject req = new JSONObject();
      req.put("method", method);
      req.put("url", urlStr);
      // ★ B: шлюз НЕ следует редиректам — WebView сам разберётся
      req.put("follow_redirects", false);

      // ★ A: подмешиваем cookies клиента (WebView)
      Map<String, String> allHeaders = new HashMap<>();
      if (reqHeaders != null && !reqHeaders.isEmpty()) {
        for (Map.Entry<String, String> e : reqHeaders.entrySet()) {
          String k = e.getKey();
          if (k == null)
            continue;
          String lk = k.toLowerCase(Locale.ROOT);
          if (isBlocked(lk))
            continue;
          allHeaders.put(k, e.getValue());
        }
      }
      String clientCookies = getCookies(urlStr);
      if (clientCookies != null && !clientCookies.isEmpty() && !allHeaders.containsKey("Cookie")
          && !allHeaders.containsKey("cookie")) {
        allHeaders.put("Cookie", clientCookies);
      }
      // ★ C: Accept-Language по умолчанию
      if (!allHeaders.containsKey("Accept-Language")
          && !allHeaders.containsKey("accept-language")) {
        allHeaders.put("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8");
      }

      if (!allHeaders.isEmpty()) {
        JSONObject h = new JSONObject();
        for (Map.Entry<String, String> e : allHeaders.entrySet()) {
          h.put(e.getKey(), e.getValue());
        }
        req.put("headers", h);
      }

      String bodyStr = req.toString();
      byte[] bodyBytes = bodyStr.getBytes(StandardCharsets.UTF_8);

      String proxyUrlStr = buildProxyUrl();
      URL proxyUrl = new URL(proxyUrlStr);
      conn = (HttpURLConnection) proxyUrl.openConnection();
      conn.setRequestMethod("POST");
      conn.setDoOutput(true);
      conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
      conn.setReadTimeout(READ_TIMEOUT_MS);
      conn.setFixedLengthStreamingMode(bodyBytes.length);
      conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
      conn.setRequestProperty("X-Mealse-Fingerprint", selfFingerprint);
      conn.setRequestProperty("X-Mealse-DeviceId", selfDeviceId);

      try (java.io.OutputStream os = conn.getOutputStream()) {
        os.write(bodyBytes);
        os.flush();
      }

      // ★ 3. Одна проверка status кода — здесь, и только здесь
      int code = conn.getResponseCode();

      if (code == 404) {
        log.warn("proxy: HTTP 404 — шлюз не поддерживает /proxy/http → DISABLED");
        disableForSession();
        return null;
      }
      if (code != 200) {
        log.warn("proxy: HTTP " + code + " — fallback to direct");
        recordError();
        return null;
      }

      // ★ G: читаем до 20 МБ (синхронизировано со шлюзом)
      String respBody = readAll(conn.getInputStream(), MAX_PROXY_RESPONSE_SIZE);
      if (respBody == null || respBody.isEmpty()) {
        log.warn("proxy: пустой ответ");
        recordError();
        return null;
      }

      // ★ H: диагностика фактического размера сырого ответа
      final int rawLen = respBody.length();
      final int contentLengthHeader = conn.getContentLength();
      log.log("proxy: raw response " + rawLen + " chars (Content-Length=" + contentLengthHeader
          + ", max=" + MAX_PROXY_RESPONSE_SIZE + ")");

      // ★ H: обёртка парсинга с диагностикой префикса при ошибке
      JSONObject resp;
      try {
        resp = new JSONObject(respBody);
      } catch (org.json.JSONException je) {
        // Собираем безопасный префикс без body_base64 — только структура
        String safePrefix = extractJsonStructurePrefix(respBody);
        log.warn("proxy: JSON parse FAILED (" + je.getMessage() + "), rawLen=" + rawLen
            + ", prefix=" + safePrefix);
        recordError();
        return null;
      }

      int status = resp.optInt("status", 200);
      String finalUrl = resp.optString("final_url", urlStr);
      String bodyBase64 = resp.optString("body_base64", "");
      boolean truncated = resp.optBoolean("truncated", false);

      // ★ A: применяем Set-Cookie из ответа шлюза
      applySetCookies(urlStr, resp);

      byte[] respBytes;
      if (bodyBase64 == null || bodyBase64.isEmpty()) {
        respBytes = new byte[0];
      } else {
        try {
          respBytes = android.util.Base64.decode(bodyBase64, android.util.Base64.NO_WRAP);
        } catch (IllegalArgumentException b64e) {
          log.warn("proxy: base64 decode FAILED (" + b64e.getMessage() + "), b64Len="
              + bodyBase64.length() + " — fallback to direct");
          recordError();
          return null;
        }
      }

      // ★ H: логируем итоговый размер декодированного тела
      log.log("proxy: decoded body " + respBytes.length + " B из b64=" + bodyBase64.length()
          + " chars, status=" + status + ", truncated=" + truncated);

      Map<String, String> respHeaders = new HashMap<>();
      if (resp.has("headers") && resp.get("headers") instanceof JSONObject) {
        JSONObject h = resp.getJSONObject("headers");
        Iterator<String> keys = h.keys();
        while (keys.hasNext()) {
          String k = keys.next();
          if (h.get(k) != null)
            respHeaders.put(k, h.get(k).toString());
        }
      }

      String mime = respHeaders.get("Content-Type");
      if (mime == null)
        mime = respHeaders.get("content-type");
      String mimeType = "text/plain";
      String encoding = "utf-8";
      if (mime != null) {
        int semi = mime.indexOf(';');
        if (semi > 0) {
          mimeType = mime.substring(0, semi).trim();
          String rest = mime.substring(semi + 1).trim();
          if (rest.toLowerCase(Locale.ROOT).startsWith("charset=")) {
            encoding = rest.substring(8).trim();
          }
        } else {
          mimeType = mime.trim();
        }
      }

      if (status >= 300 && status < 400) {
        log.log("proxy: 3xx (" + status
            + ") → fallback на direct (WebResourceResponse не принимает 3xx)");
        recordSuccess(); // это не ошибка прокси, это ограничение API
        return null; // WebView сам обработает Location
      }

      // ★ 4. Сохраняем в кэш (только если не truncated — иначе неполный ответ)
      if (cache != null && respBytes.length > 0 && !truncated) {
        cache.put(method, urlStr, status, respHeaders, respBytes);
      }

      recordSuccess();

      // ★ 5. Возвращаем ответ WebView
      InputStream data = new ByteArrayInputStream(respBytes);
      return new WebResourceResponse(mimeType, encoding, status, statusPhrase(status), respHeaders,
          data);

    } catch (Throwable t) {
      log.warn("proxy: ошибка — " + t.getClass().getSimpleName() + ": " + t.getMessage());
      recordError();
      return null;
    } finally {
      if (conn != null) {
        try {
          conn.disconnect();
        } catch (Throwable ignored) {
        }
      }
    }
  }

  // ============================================================
  // ★ H: диагностика префикса JSON без body_base64
  // ============================================================

  /**
   * Возвращает безопасный префикс JSON-ответа для лога: вырезает содержимое поля "body_base64": "..." (может быть до 20 МБ). Оставляет структуру: {"status":200,"headers":{...},"body_base64":"<...>",...}
   */
  private static String extractJsonStructurePrefix(String raw) {
    if (raw == null)
      return "null";
    final int limit = 800;
    StringBuilder sb = new StringBuilder(Math.min(raw.length(), limit));
    int i = 0;
    boolean inBodyBase64 = false;
    while (i < raw.length() && sb.length() < limit) {
      if (!inBodyBase64 && raw.startsWith("\"body_base64\"", i)) {
        sb.append("\"body_base64\"");
        i += "\"body_base64\"".length();
        // пропускаем пробелы и ':'
        while (i < raw.length() && (raw.charAt(i) == ' ' || raw.charAt(i) == '\t'))
          i++;
        if (i < raw.length() && raw.charAt(i) == ':')
          i++;
        while (i < raw.length() && (raw.charAt(i) == ' ' || raw.charAt(i) == '\t'))
          i++;
        // ожидаем открывающую кавычку
        if (i < raw.length() && raw.charAt(i) == '"') {
          sb.append(":\"<...");
          inBodyBase64 = true;
          i++;
        }
        continue;
      }
      if (inBodyBase64) {
        if (raw.charAt(i) == '"') {
          sb.append("...>\"");
          inBodyBase64 = false;
        }
        i++;
        continue;
      }
      sb.append(raw.charAt(i));
      i++;
    }
    if (sb.length() >= limit)
      sb.append("…");
    return sb.toString();
  }

  // ============================================================
  // ★ A: cookies
  // ============================================================

  private String getCookies(String url) {
    try {
      return CookieManager.getInstance().getCookie(url);
    } catch (Throwable t) {
      return null;
    }
  }

  private void applySetCookies(String url, JSONObject resp) {
    try {
      if (resp == null || !resp.has("set_cookie"))
        return;
      Object raw = resp.get("set_cookie");
      if (!(raw instanceof JSONArray))
        return;
      JSONArray arr = (JSONArray) raw;
      if (arr.length() == 0)
        return;
      CookieManager cm = CookieManager.getInstance();
      cm.setAcceptCookie(true);
      for (int i = 0; i < arr.length(); i++) {
        String cookie = arr.optString(i, null);
        if (cookie == null || cookie.isEmpty())
          continue;
        cm.setCookie(url, cookie);
      }
      cm.flush();
      log.log("proxy: cookies applied (" + arr.length() + ") для " + truncateUrl(url));
    } catch (Throwable t) {
      log.warn("applySetCookies: " + t.getMessage());
    }
  }

  // ============ Утилиты ============

  private String buildProxyUrl() {
    // Шлюз может быть IPv4 (192.168.1.5) или IPv6 ([2a00:...])
    String host = gatewayIp;
    if (gatewayIp != null && gatewayIp.contains(":") && !gatewayIp.startsWith("[")) {
      host = "[" + gatewayIp + "]";
    }
    return "http://" + host + ":" + gatewayPort + "/proxy/http";
  }

  private boolean isBlocked(String lk) {
    for (String b : BLOCKED_HEADERS) {
      if (b.equals(lk))
        return true;
    }
    return false;
  }

  private String readAll(InputStream in, int max) throws Exception {
    if (in == null)
      return "";
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(8192);
    byte[] buf = new byte[8192];
    int total = 0;
    int r;
    while ((r = in.read(buf)) != -1) {
      int toWrite = Math.min(r, max - total);
      if (toWrite <= 0)
        break;
      out.write(buf, 0, toWrite);
      total += toWrite;
      if (total >= max)
        break;
    }
    return new String(out.toByteArray(), StandardCharsets.UTF_8);
  }

  private static String statusPhrase(int code) {
    switch (code) {
      case 200:
        return "OK";
      case 201:
        return "Created";
      case 204:
        return "No Content";
      case 301:
        return "Moved Permanently";
      case 302:
        return "Found";
      case 303:
        return "See Other";
      case 304:
        return "Not Modified";
      case 307:
        return "Temporary Redirect";
      case 308:
        return "Permanent Redirect";
      case 400:
        return "Bad Request";
      case 401:
        return "Unauthorized";
      case 403:
        return "Forbidden";
      case 404:
        return "Not Found";
      case 429:
        return "Too Many Requests";
      case 500:
        return "Internal Server Error";
      case 502:
        return "Bad Gateway";
      case 503:
        return "Service Unavailable";
      case 504:
        return "Gateway Timeout";
      default:
        return "Unknown";
    }
  }

  public boolean isProxyDisabled() {
    return isDisabledNow();
  }

  public void disableForSession() {
    this.proxyDisabled = true;
    this.disabledUntil = 0; // бессрочно
    log.warn("proxy: DISABLED for session — шлюз не поддерживает /proxy/http");
  }

  private static String truncateUrl(String url) {
    if (url == null)
      return "null";
    if (url.length() > 100)
      return url.substring(0, 100) + "...";
    return url;
  }

  public static class ProxyResult {
    public int status;
    public String bodyBase64;
    public String contentType;
    public String finalUrl;
  }

  public ProxyResult fetchWithBody(String method, String urlStr, Map<String, String> reqHeaders,
      byte[] body) {
    if (isDisabledNow())
      return null;
    HttpURLConnection conn = null;
    try {
      JSONObject req = new JSONObject();
      req.put("method", method);
      req.put("url", urlStr);
      // ★ B
      req.put("follow_redirects", false);

      // ★ A + C
      Map<String, String> allHeaders = new HashMap<>();
      if (reqHeaders != null && !reqHeaders.isEmpty()) {
        for (Map.Entry<String, String> e : reqHeaders.entrySet()) {
          String k = e.getKey();
          if (k == null)
            continue;
          if (isBlocked(k.toLowerCase(Locale.ROOT)))
            continue;
          allHeaders.put(k, e.getValue());
        }
      }
      String clientCookies = getCookies(urlStr);
      if (clientCookies != null && !clientCookies.isEmpty() && !allHeaders.containsKey("Cookie")
          && !allHeaders.containsKey("cookie")) {
        allHeaders.put("Cookie", clientCookies);
      }
      if (!allHeaders.containsKey("Accept-Language")
          && !allHeaders.containsKey("accept-language")) {
        allHeaders.put("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8");
      }
      if (!allHeaders.isEmpty()) {
        JSONObject h = new JSONObject();
        for (Map.Entry<String, String> e : allHeaders.entrySet()) {
          h.put(e.getKey(), e.getValue());
        }
        req.put("headers", h);
      }

      if (body != null && body.length > 0) {
        req.put("body_base64",
            android.util.Base64.encodeToString(body, android.util.Base64.NO_WRAP));
      }

      byte[] reqBytes = req.toString().getBytes(StandardCharsets.UTF_8);
      URL proxyUrl = new URL(buildProxyUrl());
      conn = (HttpURLConnection) proxyUrl.openConnection();
      conn.setRequestMethod("POST");
      conn.setDoOutput(true);
      conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
      conn.setReadTimeout(READ_TIMEOUT_MS);
      conn.setFixedLengthStreamingMode(reqBytes.length);
      conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
      conn.setRequestProperty("X-Mealse-Fingerprint", selfFingerprint);
      conn.setRequestProperty("X-Mealse-DeviceId", selfDeviceId);

      try (java.io.OutputStream os = conn.getOutputStream()) {
        os.write(reqBytes);
        os.flush();
      }

      int code = conn.getResponseCode();
      if (code == 404) {
        log.warn("proxy: HTTP 404 → DISABLED");
        disableForSession();
        return null;
      }
      if (code != 200) {
        recordError();
        return null;
      }

      // ★ G: тот же увеличенный лимит для POST-ответов
      String respBody = readAll(conn.getInputStream(), MAX_PROXY_RESPONSE_SIZE);
      if (respBody == null) {
        recordError();
        return null;
      }

      final int rawLen = respBody.length();
      log.log("proxy[POST]: raw response " + rawLen + " chars");

      JSONObject resp;
      try {
        resp = new JSONObject(respBody);
      } catch (org.json.JSONException je) {
        String safePrefix = extractJsonStructurePrefix(respBody);
        log.warn("proxy[POST]: JSON parse FAILED (" + je.getMessage() + "), rawLen=" + rawLen
            + ", prefix=" + safePrefix);
        recordError();
        return null;
      }

      // ★ A: cookies
      applySetCookies(urlStr, resp);

      ProxyResult r = new ProxyResult();
      r.status = resp.optInt("status", 200);
      r.bodyBase64 = resp.optString("body_base64", "");
      r.finalUrl = resp.optString("final_url", urlStr);

      if (resp.has("headers") && resp.get("headers") instanceof JSONObject) {
        JSONObject h = resp.getJSONObject("headers");
        String ct = h.optString("content-type", h.optString("Content-Type", null));
        r.contentType = ct;
      }

      log.log("proxy[POST]: OK ← " + r.status + " (b64="
          + (r.bodyBase64 != null ? r.bodyBase64.length() : 0) + " chars)");

      recordSuccess();
      return r;
    } catch (Throwable t) {
      log.warn("fetchWithBody: " + t.getMessage());
      recordError();
      return null;
    } finally {
      if (conn != null)
        try {
          conn.disconnect();
        } catch (Throwable ignored) {
        }
    }
  }
}