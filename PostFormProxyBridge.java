package com.my.mealse.browser.proxy;

import android.content.Context;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.my.mealse.util.MealseLogger;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * JS-мост для POST/PUT запросов через прокси.
 *
 * ★ Использует ProxyClientProvider — возвращает АКТУАЛЬНЫЙ HttpProxyClient.
 *
 * ★ A: cookies клиента подмешиваются в fetchWithBody автоматически.
 * ★ B: follow_redirects=false — WebView сам разберётся.
 */
public class PostFormProxyBridge {

  private static final String TAG = "PostFormProxyBridge";

  public interface ProxyClientProvider {
    HttpProxyClient get();
  }

  @JavascriptInterface
  public boolean isProxyAvailable() {
    HttpProxyClient client = provider != null ? provider.get() : null;
    return client != null && !client.isProxyDisabled();
  }

  private final Context appContext;
  private final MealseLogger log;
  private final WebView webView;
  private final ProxyClientProvider provider;
  private final ExecutorService executor = Executors.newCachedThreadPool();

  public PostFormProxyBridge(Context context, WebView webView, ProxyClientProvider provider) {
    this.appContext = context.getApplicationContext();
    this.log = MealseLogger.get(appContext, TAG);
    this.webView = webView;
    this.provider = provider;
  }

  @JavascriptInterface
  public void proxyRequest(String reqId, String method, String url, String bodyBase64,
      String headersJson) {
    if (reqId == null || url == null)
      return;

    HttpProxyClient client = provider != null ? provider.get() : null;
    if (client == null || client.isProxyDisabled()) {
      log.log("POST-form: прокси неактивен → fallback на direct");
      callbackJs(reqId, false, 0, null, null);
      return;
    }

    log.log("POST-form через прокси: " + method + " " + url);

    final HttpProxyClient finalClient = client;
    executor.execute(() -> {
      try {
        byte[] body = null;
        if (bodyBase64 != null && !bodyBase64.isEmpty()) {
          body = android.util.Base64.decode(bodyBase64, android.util.Base64.NO_WRAP);
        }

        Map<String, String> headers = new HashMap<>();
        if (headersJson != null && !headersJson.isEmpty()) {
          JSONObject h = new JSONObject(headersJson);
          Iterator<String> it = h.keys();
          while (it.hasNext()) {
            String k = it.next();
            headers.put(k, h.getString(k));
          }
        }

        boolean hasCt = false;
        for (String k : headers.keySet()) {
          if ("content-type".equalsIgnoreCase(k)) {
            hasCt = true;
            break;
          }
        }
        if (!hasCt && body != null && body.length > 0) {
          headers.put("Content-Type", guessContentType(url));
        }

        HttpProxyClient.ProxyResult r = finalClient.fetchWithBody(method, url, headers, body);

        if (r == null) {
          callbackJs(reqId, false, 0, null, null);
          return;
        }
        callbackJs(reqId, true, r.status, r.bodyBase64, r.contentType);
      } catch (Throwable t) {
        log.logError("proxyRequest", t);
        callbackJs(reqId, false, 0, null, null);
      }
    });
  }

  private String guessContentType(String url) {
    String lower = url != null ? url.toLowerCase() : "";
    if (lower.contains("/api/") || lower.contains("/graphql") || lower.contains("/search")) {
      return "application/json; charset=utf-8";
    }
    return "application/x-www-form-urlencoded";
  }

  private void callbackJs(String reqId, boolean ok, int status, String bodyBase64,
      String contentType) {
    try {
      String js = "(function(){" + "if(window.__mealseProxyCallback){"
          + "  window.__mealseProxyCallback(" + "    '" + escapeJs(reqId) + "'," + "    "
          + (ok ? "true" : "false") + "," + "    " + status + "," + "    "
          + (bodyBase64 != null ? "'" + escapeJs(bodyBase64) + "'" : "null") + "," + "    "
          + (contentType != null ? "'" + escapeJs(contentType) + "'" : "null") + "  );" + "}"
          + "})();";
      webView.post(() -> webView.evaluateJavascript(js, null));
    } catch (Throwable t) {
      log.warn("callbackJs: " + t.getMessage());
    }
  }

  private static String escapeJs(String s) {
    if (s == null)
      return "";
    return s.replace("\\", "\\\\").replace("'", "\\'");
  }
}Js(reqId) + "'," + "    "
          + (ok ? "true" : "false") + "," + "    " + status + "," + "    "
          + (bodyBase64 != null ? "'" + escapeJs(bodyBase64) + "'" : "null") + "," + "    "
          + (contentType != null ? "'" + escapeJs(contentType) + "'" : "null") + "  );" + "}"
          + "})();";
      webView.post(() -> webView.evaluateJavascript(js, null));
    } catch (Throwable t) {
      log.warn("callbackJs: " + t.getMessage());
    }
  }

  private static String escapeJs(String s) {
    if (s == null)
      return "";
    return s.replace("\\", "\\\\").replace("'", "\\'");
  }
}