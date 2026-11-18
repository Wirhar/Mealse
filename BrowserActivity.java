package com.my.mealse.browser;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.my.mealse.BaseActivity;
import com.my.mealse.MainActivity;
import com.my.mealse.R;
import com.my.mealse.browser.download.BlobDownloadHelper;
import com.my.mealse.browser.download.PageSaver;
import com.my.mealse.browser.download.WifiDirectDownloadHelper;
import com.my.mealse.browser.history.HistoryHelper;
import com.my.mealse.browser.proxy.AdblockEngine;
import com.my.mealse.browser.proxy.GatewayDetector;
import com.my.mealse.browser.proxy.HttpProxyClient;
import com.my.mealse.browser.proxy.PostFormProxyBridge;
import com.my.mealse.filemanager.util.FileTypeHelper;
import com.my.mealse.filemanager.util.MAS_FileDownloadManager;
import com.my.mealse.model.TrustedNode;
import com.my.mealse.util.MealseLogger;
import com.my.mealse.util.NodeManager;

import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * BrowserActivity — браузер Mealse.
 *
 * ★ Шаг 2: базовый WebView + скрытие статус-бара + скачивание + история.
 * ★ Шаг 4: shouldInterceptRequest с прокси через шлюз.
 * ★ Шаг 5: Wi-Fi Direct, blob, кэш.
 * ★ Шаг 6: MHTML, User-Agent, Reader Mode, intent-filter, POST через прокси.
 * ★ Шаг 7: клиентская блокировка рекламы (AdblockEngine) до прокси.
 * ★ Шаг 8: возврат на главный экран приложения из меню браузера.
 */
public class BrowserActivity extends BaseActivity {

  private static final String TAG = "BrowserActivity";

  // ===== Настройки (prefs) =====
  private static final String PREF_TUNNEL = "mealse_tunnel_prefs";
  private static final String KEY_TUNNEL_MODE = "mode";
  private static final String KEY_TUNNEL_GATEWAY_IP = "gateway_ip";

  // ===== Views =====
  private EditText addressInput;
  private ImageButton btnBack, btnForward, btnRefresh, btnStop, btnHome, btnMenu;
  private ProgressBar progress;
  private WebView webView;
  private View emptyView;
  private TextView emptyStatus;

  // ===== Managers =====
  private MealseLogger log;
  private BrowserSettings settings;
  private MAS_FileDownloadManager downloadManager;
  private HistoryHelper historyHelper;
  private NodeManager nodeManager;

  // ===== State =====
  private boolean pageLoading = false;
  private String lastSavedUrl = null;
  private long lastSaveTime = 0L;
  private static final long SAVE_DEBOUNCE_MS = 1000L;
  private volatile boolean destroyed = false;

  // ★ Прокси через шлюз
  private GatewayDetector gatewayDetector;
  private volatile HttpProxyClient proxyClient;
  private volatile String gatewayLabel = null;

  // ★ Блокировка рекламы
  private volatile AdblockEngine adblockEngine;

  // ★ Помощники (создаются ПОСЛЕ initViews)
  private WifiDirectDownloadHelper wifiDirectDownloadHelper;
  private BlobDownloadHelper blobDownloadHelper;
  private PageSaver pageSaver;
  private ReaderModeExtractor readerExtractor;

  private OnBackInvokedCallback backCallback;

  // ============================================================
  // Lifecycle
  // ============================================================

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    log = MealseLogger.get(this, TAG);
    log.log("══ BrowserActivity onCreate START ══");

    // Скрытие статус-бара — до setContentView
    enableFullscreen();

    setContentView(R.layout.activity_browser);

    // ★ 1. Общие менеджеры (не зависят от webView)
    settings = new BrowserSettings(this);
    downloadManager = new MAS_FileDownloadManager(this);
    historyHelper = new HistoryHelper(this);
    nodeManager = new NodeManager(this);

    // ★ 2. initViews — создаёт webView и все View
    initViews();

    // ★ 3. Помощники, которые зависят от webView
    wifiDirectDownloadHelper = new WifiDirectDownloadHelper(this);
    blobDownloadHelper = new BlobDownloadHelper(this, webView);
    pageSaver = new PageSaver(this, webView);
    readerExtractor = new ReaderModeExtractor(webView);

    // ★ 4. Детектор шлюза
    gatewayDetector = new GatewayDetector(this);
    refreshProxyClient();

    if (emptyStatus != null) {
      emptyStatus.setText(gatewayLabel != null ? gatewayLabel + " · через шлюз\nВведите адрес"
          : "🌐 Напрямую\nВведите адрес в строке сверху");
    }

    // ★ 5. Настройка WebView (WebViewClient/WebChromeClient/JS-мосты)
    setupWebView();
    setupListeners();

    setupBackHandler();

    // ★ 6. Стартовый URL
    String startUrl = null;
    Intent intent = getIntent();
    if (intent != null) {
      startUrl = intent.getStringExtra("start_url");
    }
    if (startUrl == null || startUrl.isEmpty()) {
      startUrl = settings.getHomeUrl();
    }
    if (startUrl == null || startUrl.isEmpty()) {
      Uri data = intent != null ? intent.getData() : null;
      if (data != null) {
        startUrl = data.toString();
        log.log("URL из intent.getData(): " + startUrl);
      }
    }

    log.log("Start URL: " + startUrl);
    loadUrl(startUrl);

    log.log("══ BrowserActivity onCreate END ══");
  }

  /**
   * ★ F: health-check шлюза перед активацией прокси.
   * Делает быстрый TCP-probe + GET /ping. Если шлюз не отвечает — прокси не активируем.
   * Работает в фоновом потоке, результат применяется на UI-потоке.
   */
  private void refreshProxyClient() {
    try {
      GatewayDetector.GatewayInfo info = gatewayDetector.getActiveGateway();
      if (info == null) {
        proxyClient = null;
        gatewayLabel = null;
        log.log("Шлюз НЕ активен — работаем напрямую");
        return;
      }

      final String ip = info.gatewayIp;
      final int port = info.proxyPort;
      // Пока проверяем — ставим метку, но клиент ещё не активен
      gatewayLabel = "🛡 " + ip;
      log.log("Шлюз активен: " + info + " — проверяем доступность...");

      new Thread(() -> {
        boolean ok = pingGateway(ip, port);
        runOnUiThread(() -> {
          if (destroyed)
            return;
          if (ok) {
            proxyClient = new HttpProxyClient(BrowserActivity.this, ip, port);
            gatewayLabel = "🛡 " + ip;
            log.log("Gateway verified: OK → прокси активен");
          } else {
            proxyClient = null;
            gatewayLabel = null;
            log.warn("Gateway unreachable → работаем напрямую");
          }
          if (emptyStatus != null && emptyView != null
              && emptyView.getVisibility() == View.VISIBLE) {
            emptyStatus.setText(gatewayLabel != null ? gatewayLabel + " · через шлюз"
                : "🌐 Напрямую");
          }
        });
      }, "gw-ping").start();

    } catch (Throwable t) {
      proxyClient = null;
      gatewayLabel = null;
      log.warn("refreshProxyClient: " + t.getMessage());
    }
  }

  /**
   * ★ F: быстрый health-check шлюза. TCP-connect + GET /ping.
   */
  private boolean pingGateway(String ip, int port) {
    if (ip == null || ip.isEmpty())
      return false;
    try (java.net.Socket s = new java.net.Socket()) {
      s.connect(new java.net.InetSocketAddress(ip, port), 2000);
      s.setSoTimeout(2000);
      java.io.OutputStream out = s.getOutputStream();
      String host = ip.contains(":") ? "[" + ip + "]" : ip;
      out.write(("GET /ping HTTP/1.0\r\nHost: " + host + ":" + port + "\r\n\r\n")
          .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      out.flush();
      byte[] buf = new byte[256];
      int n = s.getInputStream().read(buf);
      if (n > 0) {
        String resp = new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8);
        return resp.contains("200") || resp.contains("\"status\"");
      }
    } catch (Throwable t) {
      log.warn("pingGateway " + ip + ":" + port + " — " + t.getMessage());
    }
    return false;
  }

  @Override
  protected void onResume() {
    super.onResume();
    destroyed = false;

    // Перечитываем состояние шлюза (пользователь мог запустить/остановить VPN)
    refreshProxyClient();

    if (webView != null) {
      webView.onResume();
      webView.resumeTimers();
    }
  }

  @Override
  protected void onPause() {
    super.onPause();
    if (webView != null) {
      webView.onPause();
      webView.pauseTimers();
    }
  }

  @Override
  protected void onDestroy() {
    destroyed = true;
    log.log("onDestroy");

    // ★ Снимаем backCallback
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backCallback != null) {
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        backCallback = null;
        log.log("onDestroy: backCallback снят");
      }
    } catch (Throwable t) {
      log.warn("onDestroy: unregister backCallback: " + t.getMessage());
    }

    // ★ Сбрасываем статистику adblock
    try {
      AdblockEngine ae = adblockEngine;
      if (ae != null && ae.getStats() != null) {
        ae.getStats().flush();
      }
      adblockEngine = null;
    } catch (Throwable t) {
      log.warn("onDestroy: adblock flush: " + t.getMessage());
    }

    try {
      if (downloadManager != null) {
        downloadManager.cleanup();
        downloadManager = null;
      }
    } catch (Throwable ignored) {
    }

    if (webView != null) {
      try {
        webView.stopLoading();
        webView.loadUrl("about:blank");
        webView.clearHistory();
        webView.removeAllViews();
        webView.destroy();
      } catch (Throwable ignored) {
      }
      webView = null;
    }

    super.onDestroy();
  }

  @Override
  @SuppressWarnings("deprecation")
  public void onBackPressed() {
    if (handleBackNavigation()) {
      return;
    }
    super.onBackPressed();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    if (intent != null && intent.getData() != null) {
      String url = intent.getData().toString();
      log.log("onNewIntent: URL из intent.getData()=" + url);
      loadUrl(url);
    }
  }

  // ============================================================
  // Скрытие статус-бара (fullscreen)
  // ============================================================

  private void enableFullscreen() {
    try {
      requestWindowFeature(Window.FEATURE_NO_TITLE);
      getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
          WindowManager.LayoutParams.FLAG_FULLSCREEN);
      getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

      // Android 15+: edge-to-edge, прячем system bars
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat ctl = WindowCompat.getInsetsController(getWindow(),
            getWindow().getDecorView());
        if (ctl != null) {
          ctl.hide(WindowInsetsCompat.Type.statusBars());
          ctl.setSystemBarsBehavior(
              WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
      }
      log.log("Fullscreen enabled");
    } catch (Throwable t) {
      log.warn("enableFullscreen: " + t.getMessage());
    }
  }

  // ============================================================
  // Init views + WebView
  // ============================================================

  private void initViews() {
    addressInput = findViewById(R.id.br_address_input);
    btnBack = findViewById(R.id.br_btn_back_page);
    btnForward = findViewById(R.id.br_btn_forward_page);
    btnRefresh = findViewById(R.id.br_btn_refresh);
    btnStop = findViewById(R.id.br_btn_stop);
    btnHome = findViewById(R.id.br_btn_home);
    btnMenu = findViewById(R.id.br_btn_menu);
    progress = findViewById(R.id.br_progress);
    webView = findViewById(R.id.br_webview);
    emptyView = findViewById(R.id.br_empty);
    emptyStatus = findViewById(R.id.br_empty_status);

    checkViewNotNull(addressInput, "br_address_input");
    checkViewNotNull(btnBack, "br_btn_back_page");
    checkViewNotNull(btnForward, "br_btn_forward_page");
    checkViewNotNull(btnRefresh, "br_btn_refresh");
    checkViewNotNull(btnStop, "br_btn_stop");
    checkViewNotNull(btnHome, "br_btn_home");
    checkViewNotNull(btnMenu, "br_btn_menu");
    checkViewNotNull(progress, "br_progress");
    checkViewNotNull(webView, "br_webview");
    checkViewNotNull(emptyView, "br_empty");
  }

  private void checkViewNotNull(Object view, String id) {
    if (view == null) {
      String msg = "View не найден: " + id + " — проверь activity_browser.xml";
      log.error(msg);
      throw new IllegalStateException(msg);
    }
  }

  private void setupWebView() {
    WebSettings s = webView.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setDatabaseEnabled(true);
    s.setAllowFileAccess(true);
    s.setAllowContentAccess(true);
    s.setUseWideViewPort(true);
    s.setLoadWithOverviewMode(true);
    s.setSupportZoom(true);
    s.setBuiltInZoomControls(true);
    s.setDisplayZoomControls(false);
    s.setSaveFormData(false);
    s.setJavaScriptCanOpenWindowsAutomatically(true);
    s.setSupportMultipleWindows(false);
    s.setCacheMode(WebSettings.LOAD_DEFAULT);
    s.setMediaPlaybackRequiresUserGesture(false);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
    }

    // ★ User-Agent: сначала базовый, потом явная настройка mobile/desktop
    String ua = s.getUserAgentString();
    if (ua != null && !ua.contains("Mealse")) {
      s.setUserAgentString(ua + " MealseBrowser/1.0");
    }
    applyUserAgent(s); // ← всегда, не только при первом запуске

    // Cookies
    CookieManager cm = CookieManager.getInstance();
    cm.setAcceptCookie(true);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      cm.setAcceptThirdPartyCookies(webView, true);
    }

    // ★ Adblock — создаём ДО webViewClient, чтобы был ready в shouldInterceptRequest
    try {
      adblockEngine = new AdblockEngine(this);
      log.log("AdblockEngine создан, enabled=" + settings.isAdblockEnabled());
    } catch (Throwable t) {
      adblockEngine = null;
      log.warn("AdblockEngine init failed: " + t.getMessage());
    }

    // ★ JS-мост для POST-форм (без setWebViewClient!)
    setupPostFormProxyInterceptor();

    // ★ Основной WebViewClient
    webView.setWebViewClient(new WebViewClient() {

      @Override
      public boolean shouldOverrideUrlLoading(WebView view, String url) {
        return handleUrlLoading(url);
      }

      @Override
      public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
          return handleUrlLoading(request.getUrl().toString());
        }
        return false;
      }

      @Override
      public void onPageStarted(WebView view, String url, Bitmap favicon) {
        super.onPageStarted(view, url, favicon);
        log.log("onPageStarted: " + url);
        pageLoading = true;
        updateLoadingUi(true);
        updateAddressBar(url);
        hideEmptyView();
      }

      @Override
      public void onPageFinished(WebView view, String url) {
        super.onPageFinished(view, url);
        log.log("onPageFinished: " + url);
        pageLoading = false;
        updateLoadingUi(false);
        updateAddressBar(url);
        updateBackForwardButtons();
        saveToHistory(url, view.getTitle());

        // ★ Шаг 6: инжектим POST-перехватчик
        if (view != null && url != null && !url.startsWith("about:") && !url.startsWith("file:")) {
          injectPostFormInterceptor(view);
        }
      }

      @Override
      public void onReceivedError(WebView view, WebResourceRequest request,
          android.webkit.WebResourceError error) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
          if (request.isForMainFrame()) {
            log.warn("onReceivedError: " + error.getErrorCode() + " — " + error.getDescription());
            pageLoading = false;
            updateLoadingUi(false);
            showErrorPageIfNeeded(error.getDescription());
          }
        }
      }

      // ★ Прокси через шлюз (только GET/HEAD) + Adblock
      @Override
      public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        try {
          String url = request.getUrl() != null ? request.getUrl().toString() : null;
          if (url == null)
            return null;

          // ★ Adblock — перехватываем ДО прокси
          if (settings != null && settings.isAdblockEnabled()) {
            AdblockEngine engine = adblockEngine;
            if (engine != null) {
              try {
                if (engine.shouldBlock(url)) {
                  log.log("adblock: BLOCK " + truncate(url, 120));
                  return buildEmptyResponse();
                }
              } catch (Throwable t) {
                log.warn("adblock check failed: " + t.getMessage());
              }
            }
          }

          if (proxyClient == null || proxyClient.isProxyDisabled()) {
            return null;
          }
          String method = request.getMethod();
          if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            return null;
          }
          if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return null;
          }

          // ★ Не проксируем запросы к самому шлюзу (это /ping, /signal/*, /proxy/*)
          if (gatewayLabel != null) {
            String gwIp = gatewayLabel.replace("🛡 ", "").trim();
            if (!gwIp.isEmpty() && url.contains(gwIp + ":8081")) {
              return null; // напрямую
            }
          }

          Map<String, String> reqHeaders = request.getRequestHeaders();
          WebResourceResponse resp = proxyClient.fetch(method, url, reqHeaders);
          return resp; // null → WebView загрузит напрямую
        } catch (Throwable t) {
          log.warn("shouldInterceptRequest: " + t.getMessage());
          return null;
        }
      }
    });

    // ★ WebChromeClient
    webView.setWebChromeClient(new WebChromeClient() {
      @Override
      public void onProgressChanged(WebView view, int newProgress) {
        progress.setProgress(newProgress);
        if (newProgress < 100) {
          progress.setVisibility(View.VISIBLE);
        } else {
          progress.setVisibility(View.GONE);
        }
      }

      @Override
      public void onReceivedTitle(WebView view, String title) {
        if (getSupportActionBar() != null && title != null) {
          getSupportActionBar().setTitle(title);
        }
      }
    });

    // ★ DownloadListener
    webView.setDownloadListener(new DownloadListener() {
      @Override
      public void onDownloadStart(String url, String userAgent, String contentDisposition,
          String mimeType, long contentLength) {
        log.log("onDownloadStart: " + url + " (" + mimeType + ", " + contentLength + " B)");
        handleDownload(url, userAgent, contentDisposition, mimeType, contentLength);
      }
    });
  }

  /**
   * ★ Adblock: пустой ответ 200 с CORS-заголовком.
   * Используется для заблокированных URL, чтобы WebView не показывал ошибку.
   */
  private WebResourceResponse buildEmptyResponse() {
    try {
      InputStream empty = new ByteArrayInputStream(new byte[0]);
      Map<String, String> headers = new HashMap<>();
      headers.put("Access-Control-Allow-Origin", "*");
      headers.put("Cache-Control", "no-store");
      return new WebResourceResponse("text/plain", "utf-8", 200, "OK", headers, empty);
    } catch (Throwable t) {
      return null;
    }
  }

  private void setupListeners() {
    btnBack.setOnClickListener(v -> {
      if (webView != null && webView.canGoBack())
        webView.goBack();
    });
    btnForward.setOnClickListener(v -> {
      if (webView != null && webView.canGoForward())
        webView.goForward();
    });
    btnRefresh.setOnClickListener(v -> {
      if (webView != null)
        webView.reload();
    });
    btnStop.setOnClickListener(v -> {
      if (webView != null)
        webView.stopLoading();
    });
    btnHome.setOnClickListener(v -> loadUrl(settings.getHomeUrl()));
    btnMenu.setOnClickListener(this::showBrowserPopupMenu);

    addressInput.setOnEditorActionListener((v, actionId, event) -> {
      if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE
          || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
        String input = addressInput.getText().toString();
        String url = settings.normalizeInput(input);
        if (!url.isEmpty())
          loadUrl(url);
        return true;
      }
      return false;
    });
  }

  // ============================================================
  // Popup-меню браузера
  // ============================================================

  private void showBrowserPopupMenu(View anchor) {
    PopupMenu popup = new PopupMenu(this, anchor);
    popup.getMenu().add(0, 1, 0, "🔗 Скопировать ссылку");
    popup.getMenu().add(0, 2, 1, "🏠 Домашняя страница");
    popup.getMenu().add(0, 3, 2, "📜 История");
    popup.getMenu().add(0, 7, 3, "💾 Сохранить страницу");
    popup.getMenu().add(0, 8, 4, "📖 Reader Mode");
    popup.getMenu().add(0, 4, 5, "⭐ Сохранённые сайты");
    popup.getMenu().add(0, 5, 6, "🔐 Пароли");
    popup.getMenu().add(0, 6, 7, "⚙ Настройки браузера");
    // ★ Шаг 8: возврат на главный экран приложения
    popup.getMenu().add(0, 9, 8, "🏡 На главный экран приложения");

    popup.setOnMenuItemClickListener(item -> {
      switch (item.getItemId()) {
        case 1:
          copyCurrentUrl();
          return true;
        case 2:
          loadUrl(settings.getHomeUrl());
          return true;
        case 3:
          openHistoryActivity();
          return true;
        case 4:
          openSavedSitesActivity();
          return true;
        case 5:
          openPasswordsActivity();
          return true;
        case 6:
          try {
            startActivity(new Intent(BrowserActivity.this, BrowserSettingsActivity.class));
          } catch (Throwable t) {
            toast("Ошибка: " + t.getMessage());
          }
          return true;
        case 7:
          saveCurrentPageAsMhtml();
          return true;
        case 8:
          openReaderMode();
          return true;
        case 9:
          openMainActivity();
          return true;
        default:
          return false;
      }
    });
    popup.show();
  }

  /**
   * ★ Шаг 8: возврат на главный экран приложения.
   *
   * Использует FLAG_ACTIVITY_CLEAR_TOP + SINGLE_TOP:
   *  - Если MainActivity уже в стеке (обычный случай) — она получит onNewIntent
   *    и все активности над ней (включая BrowserActivity) закроются.
   *  - Если MainActivity в стеке нет (например, браузер запущен из intent-filter
   *    по ссылке) — создастся новая MainActivity, а текущая закроется через finish().
   *
   * Дополнительно ставим FLAG_ACTIVITY_NEW_TASK, чтобы поведение было
   * предсказуемым при любом taskAffinity.
   */
  private void openMainActivity() {
    try {
      Intent i = new Intent(BrowserActivity.this, MainActivity.class);
      i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
          | Intent.FLAG_ACTIVITY_SINGLE_TOP
          | Intent.FLAG_ACTIVITY_NEW_TASK);
      startActivity(i);
      // Закрываем браузер, чтобы пользователь не вернулся в него кнопкой Back
      finish();
      log.log("openMainActivity: возврат на главный экран");
    } catch (Throwable t) {
      log.logError("openMainActivity", t);
      toast("Ошибка: " + t.getMessage());
    }
  }

  private void copyCurrentUrl() {
    try {
      String url = (webView != null && webView.getUrl() != null) ? webView.getUrl()
          : addressInput.getText().toString();
      if (url == null || url.isEmpty()) {
        toast("Нет ссылки");
        return;
      }
      ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
      if (cm != null) {
        cm.setPrimaryClip(ClipData.newPlainText("url", url));
        toast("🔗 Скопировано: " + url);
      }
    } catch (Exception e) {
      log.logError("copyCurrentUrl", e);
    }
  }

  private void openHistoryActivity() {
    try {
      startActivity(new Intent(this, HistoryActivity.class));
    } catch (Exception e) {
      log.logError("openHistoryActivity", e);
      toast("Ошибка: " + e.getMessage());
    }
  }

  private void openSavedSitesActivity() {
    try {
      startActivity(new Intent(this, SavedSitesActivity.class));
    } catch (Exception e) {
      log.logError("openSavedSitesActivity", e);
      toast("Ошибка: " + e.getMessage());
    }
  }

  private void openPasswordsActivity() {
    try {
      startActivity(new Intent(this, PasswordManagerActivity.class));
    } catch (Exception e) {
      log.logError("openPasswordsActivity", e);
      toast("Ошибка: " + e.getMessage());
    }
  }

  // ============================================================
  // Навигация
  // ============================================================

  private void loadUrl(String url) {
    if (url == null || url.isEmpty())
      return;
    if (webView == null)
      return;
    try {
      log.log("loadUrl: " + url);
      webView.loadUrl(url);
    } catch (Exception e) {
      log.logError("loadUrl", e);
      toast("Ошибка загрузки: " + e.getMessage());
    }
  }

  private boolean handleUrlLoading(String url) {
    if (url == null)
      return false;
    log.log("shouldOverrideUrlLoading: " + url);

    String lower = url.toLowerCase();

    // Спец-схемы → системный Intent
    if (lower.startsWith("mailto:") || lower.startsWith("tel:") || lower.startsWith("sms:")
        || lower.startsWith("geo:") || lower.startsWith("market:")) {
      try {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
      } catch (Exception e) {
        toast("Приложение не найдено");
      }
      return true;
    }

    if (lower.startsWith("intent://")) {
      try {
        Intent i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
      } catch (Exception e) {
        toast("Не удалось открыть ссылку");
      }
      return true;
    }

    if (lower.startsWith("file://")) {
      toast("file:// запрещён в браузере");
      return true;
    }

    // mealse-fs:// → Wi-Fi Direct
    if (lower.startsWith("mealse-fs://")) {
      if (wifiDirectDownloadHelper != null
          && wifiDirectDownloadHelper.canDownloadViaWifiDirect(url)) {
        wifiDirectDownloadHelper.download(url, MAS_FileDownloadManager.Storage.INTERNAL,
            new WifiDirectDownloadHelper.DownloadCallback() {
              @Override
              public void onStart(String name) {
                toast("📡 Wi-Fi Direct: " + name);
              }

              @Override
              public void onProgress(int percent) {
              }

              @Override
              public void onSuccess(java.io.File file) {
                toast("✅ Скачано через Wi-Fi Direct: " + file.getName());
              }

              @Override
              public void onError(String error) {
                toast("❌ Wi-Fi Direct: " + error);
              }

              @Override
              public void onCancel() {
                toast("Отменено");
              }
            });
      } else {
        toast("Wi-Fi Direct не подключён");
      }
      return true;
    }

    return false;
  }

  // ============================================================
  // UI-обновление
  // ============================================================

  private void updateAddressBar(String url) {
    try {
      if (addressInput == null || url == null)
        return;
      addressInput.setText(url);
      addressInput.setSelection(addressInput.getText().length());
      if (gatewayLabel != null) {
        addressInput.setHint(gatewayLabel + " · введите адрес");
      } else {
        addressInput.setHint("Введите адрес или поиск…");
      }
    } catch (Throwable ignored) {
    }
  }

  private void updateLoadingUi(boolean loading) {
    try {
      progress.setVisibility(loading ? View.VISIBLE : View.GONE);
      btnRefresh.setVisibility(loading ? View.GONE : View.VISIBLE);
      btnStop.setVisibility(loading ? View.VISIBLE : View.GONE);
    } catch (Throwable ignored) {
    }
  }

  private void updateBackForwardButtons() {
    try {
      if (webView != null) {
        btnBack.setEnabled(webView.canGoBack());
        btnForward.setEnabled(webView.canGoForward());
      }
    } catch (Throwable ignored) {
    }
  }

  private void hideEmptyView() {
    if (emptyView != null && emptyView.getVisibility() == View.VISIBLE) {
      emptyView.setVisibility(View.GONE);
    }
  }

  private void showErrorPageIfNeeded(CharSequence desc) {
    try {
      if (emptyView != null && emptyStatus != null) {
        if (webView != null && webView.getUrl() == null) {
          String mode = (gatewayLabel != null) ? gatewayLabel + " · через шлюз" : "🌐 Напрямую";
          emptyStatus.setText("Не удалось загрузить страницу\n" + "Режим: " + mode
              + (desc != null ? "\n" + desc : ""));
          emptyView.setVisibility(View.VISIBLE);
        }
      }
    } catch (Throwable ignored) {
    }
  }

  // ============================================================
  // Скачивание
  // ============================================================

  private void handleDownload(String url, String userAgent, String contentDisposition,
      String mimeType, long contentLength) {
    try {
      if (url == null || url.isEmpty())
        return;

      final String fileName = extractFileName(contentDisposition, url, mimeType);

      // Blob
      if (url.startsWith("blob:")) {
        if (blobDownloadHelper != null) {
          blobDownloadHelper.download(url, fileName, new BlobDownloadHelper.BlobCallback() {
            @Override
            public void onStart(String name) {
              toast("📥 Blob: " + name);
            }

            @Override
            public void onSuccess(java.io.File file) {
              toast("✅ Сохранено: " + file.getName());
            }

            @Override
            public void onError(String error) {
              toast("❌ Blob: " + error);
            }
          });
        } else {
          toast("Blob helper не инициализирован");
        }
        return;
      }

      if (isWifiDirectConnected()) {
        log.log("Wi-Fi Direct connected — будет скачано через него (Шаг 5)");
      }

      showDownloadDialog(url, fileName, mimeType);
    } catch (Exception e) {
      log.logError("handleDownload", e);
      toast("Ошибка: " + e.getMessage());
    }
  }

  private void showDownloadDialog(String url, String fileName, String mimeType) {
    FileTypeHelper.Category cat = FileTypeHelper.getCategoryByFileName(fileName);
    String catName = FileTypeHelper.getCategoryDisplayName(cat);
    String catFolder = FileTypeHelper.getCategoryFolder(cat);

    new AlertDialog.Builder(this).setTitle("📥 Скачать файл?")
        .setMessage(
            "Имя: " + fileName + "\nТип: " + catName + "\nПапка: getFilesDir()/" + catFolder + "/")
        .setPositiveButton("В «Приложение»", (d, w) -> {
          downloadManager.setCallback(new MAS_FileDownloadManager.DownloadCallback() {
            @Override
            public void onStart(String name, long total) {
              toast("📥 Загрузка: " + name + (total > 0 ? " (" + formatSize(total) + ")" : ""));
            }

            @Override
            public void onProgress(int percent) {
            }

            @Override
            public void onSuccess(File file) {
              toast("✅ Сохранено: " + file.getName() + " → " + catFolder + "/");
            }

            @Override
            public void onError(String error) {
              toast("❌ Ошибка: " + error);
            }

            @Override
            public void onCancel() {
              toast("Загрузка отменена");
            }
          });
          downloadManager.download(url, fileName, MAS_FileDownloadManager.Storage.INTERNAL);
        }).setNegativeButton("В Downloads", (d, w) -> {
          downloadManager.setCallback(new MAS_FileDownloadManager.DownloadCallback() {
            @Override
            public void onStart(String name, long total) {
              toast("📥 Загрузка: " + name + (total > 0 ? " (" + formatSize(total) + ")" : ""));
            }

            @Override
            public void onProgress(int percent) {
            }

            @Override
            public void onSuccess(File file) {
              toast("✅ Сохранено: " + file.getName());
            }

            @Override
            public void onError(String error) {
              toast("❌ Ошибка: " + error);
            }

            @Override
            public void onCancel() {
              toast("Загрузка отменена");
            }
          });
          downloadManager.download(url, fileName, MAS_FileDownloadManager.Storage.EXTERNAL);
        }).setNeutralButton("Отмена", null).show();
  }

  private String formatSize(long bytes) {
    if (bytes < 1024)
      return bytes + " B";
    if (bytes < 1024 * 1024)
      return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
    return String.format(java.util.Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0));
  }

  private String extractFileName(String contentDisposition, String url, String mimeType) {
    String fileName = null;
    if (contentDisposition != null && !contentDisposition.isEmpty()) {
      java.util.regex.Pattern p1 = java.util.regex.Pattern
          .compile("filename\\*=(?:UTF-8'')?([^;]+)", java.util.regex.Pattern.CASE_INSENSITIVE);
      java.util.regex.Matcher m1 = p1.matcher(contentDisposition);
      if (m1.find()) {
        try {
          fileName = java.net.URLDecoder.decode(m1.group(1).trim(), "UTF-8");
        } catch (Exception ignored) {
          fileName = m1.group(1).trim();
        }
      }
      if (fileName == null) {
        java.util.regex.Pattern p2 = java.util.regex.Pattern.compile("filename=\"?([^\";]+)\"?",
            java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m2 = p2.matcher(contentDisposition);
        if (m2.find())
          fileName = m2.group(1).trim();
      }
    }
    if (fileName == null || fileName.isEmpty()) {
      try {
        fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType);
      } catch (Throwable ignored) {
        fileName = null;
      }
    }
    if (fileName == null || fileName.isEmpty()) {
      try {
        Uri uri = Uri.parse(url);
        String last = uri.getLastPathSegment();
        if (last != null && !last.isEmpty())
          fileName = last;
      } catch (Throwable ignored) {
      }
    }
    if (fileName == null || fileName.isEmpty()) {
      fileName = "download_" + System.currentTimeMillis();
      if (mimeType != null) {
        String ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
        if (ext != null && !ext.isEmpty())
          fileName += "." + ext;
      }
    }
    fileName = fileName.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_");
    if (fileName.length() > 200)
      fileName = fileName.substring(0, 200);
    return fileName;
  }

  private boolean isWifiDirectConnected() {
    try {
      com.my.mealse.MealseApp app = (com.my.mealse.MealseApp) getApplication();
      com.my.mealse.util.WifiDirectManager wdm = app.getWifiDirectManager();
      return wdm != null && wdm.isConnected();
    } catch (Throwable t) {
      return false;
    }
  }

  // ============================================================
  // История
  // ============================================================

  private void saveToHistory(String url, String title) {
    try {
      if (url == null || url.isEmpty())
        return;
      if (url.startsWith("about:") || url.startsWith("chrome:") || url.startsWith("file:")) {
        return;
      }
      if (url.equals(lastSavedUrl)
          && (System.currentTimeMillis() - lastSaveTime < SAVE_DEBOUNCE_MS)) {
        return;
      }
      String finalTitle = (title == null || title.isEmpty()) ? url : title;
      historyHelper.addEntry(finalTitle, url, null);
      lastSavedUrl = url;
      lastSaveTime = System.currentTimeMillis();
      log.log("saveToHistory: " + url);
    } catch (Exception e) {
      log.logError("saveToHistory", e);
    }
  }

  // ============================================================
  // User-Agent
  // ============================================================

  private void applyUserAgent(WebSettings s) {
    String ua;
    if ("desktop".equals(settings.getUserAgentMode())) {
      ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " + "AppleWebKit/537.36 (KHTML, like Gecko) "
          + "Chrome/120.0.0.0 Safari/537.36 MealseBrowser/1.0";
    } else {
      ua = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 " + "MealseBrowser/1.0";
    }
    try {
      s.setUserAgentString(ua);
      log.log("applyUserAgent: " + (ua.contains("Windows") ? "DESKTOP" : "MOBILE"));
    } catch (Throwable t) {
      log.warn("applyUserAgent: " + t.getMessage());
    }
  }

  // ============================================================
  // MHTML
  // ============================================================

  private void saveCurrentPageAsMhtml() {
    if (webView == null)
      return;
    String url = webView.getUrl();
    String title = webView.getTitle();
    pageSaver.saveCurrentPage(url, title, new PageSaver.Callback() {
      @Override
      public void onStart(String name) {
        toast("💾 Сохранение: " + name);
      }

      @Override
      public void onSuccess(java.io.File file) {
        toast("✅ Сохранено: " + file.getName() + " → downloads/");
      }

      @Override
      public void onError(String error) {
        toast("❌ " + error);
      }
    });
  }

  // ============================================================
  // Reader Mode
  // ============================================================

  private void openReaderMode() {
    if (webView == null)
      return;
    final String currentUrl = webView.getUrl();
    if (currentUrl == null || currentUrl.startsWith("about:") || currentUrl.startsWith("file:")) {
      toast("Reader Mode недоступен для этой страницы");
      return;
    }
    toast("📖 Извлечение контента...");
    readerExtractor.extract(new ReaderModeExtractor.Callback() {
      @Override
      public void onExtracted(String title, String html) {
        String readerHtml = "<!DOCTYPE html><html><head>" + "<meta charset='utf-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'>" + "<title>"
            + escapeHtml(title) + "</title>" + "<style>"
            + "body{font-family:Georgia,serif;line-height:1.6;padding:16px;"
            + "max-width:720px;margin:0 auto;color:#222;background:#fafafa;}"
            + "h1,h2,h3{color:#111;margin-top:1.2em;}" + "img{max-width:100%;height:auto;}"
            + "a{color:#4C51BF;}" + "pre,code{background:#f0f0f0;padding:4px;border-radius:3px;"
            + "font-family:monospace;font-size:0.9em;overflow-x:auto;}"
            + "blockquote{border-left:3px solid #4C51BF;padding-left:12px;"
            + "color:#555;margin-left:0;}" + "@media(prefers-color-scheme:dark){"
            + "body{background:#1E1E1E;color:#d0d0d0;}" + "h1,h2,h3{color:#fff;}"
            + "pre,code{background:#2a2a2a;}" + "}" + "</style></head><body>" + "<h1>"
            + escapeHtml(title) + "</h1>" + "<p><small><a href='" + escapeHtml(currentUrl) + "'>"
            + "🔗 Оригинал: " + escapeHtml(currentUrl) + "</a></small></p>" + "<hr>" + html
            + "</body></html>";
        webView.loadDataWithBaseURL(currentUrl, readerHtml, "text/html", "utf-8", null);
        toast("📖 Reader Mode");
      }

      @Override
      public void onError(String error) {
        toast("📖 " + error);
      }
    });
  }

  private static String escapeHtml(String s) {
    if (s == null)
      return "";
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"",
        "&quot;");
  }

  // ============================================================
  // POST через прокси (JS-мост)
  // ============================================================

  private void setupPostFormProxyInterceptor() {
    try {
      // ★ Provider: возвращает АКТУАЛЬНЫЙ proxyClient (может быть null)
      PostFormProxyBridge bridge = new PostFormProxyBridge(this, webView, () -> proxyClient);
      webView.addJavascriptInterface(bridge, "MealseProxyBridge");
      log.log("PostFormProxyBridge установлен");
    } catch (Throwable t) {
      log.warn("setupPostFormProxyInterceptor: " + t.getMessage());
    }
  }

  private void injectPostFormInterceptor(WebView view) {
    String js = "(function(){" + "if(window.__mealsePostFormInstalled)return;"
        + "window.__mealsePostFormInstalled=true;"
        + "console.log('[Mealse] POST-form interceptor installed');"

        // pending: reqId → {resolve, reject}
        + "var pending={};"

        // Callback из Java
        + "window.__mealseProxyCallback=function(id,ok,status,bodyBase64,ct){"
        + "  var cb=pending[id];" + "  if(!cb)return;" + "  delete pending[id];" + "  if(ok){"
        + "    try{" + "      var bin=atob(bodyBase64||'');"
        + "      var bytes=new Uint8Array(bin.length);"
        + "      for(var i=0;i<bin.length;i++)bytes[i]=bin.charCodeAt(i);"
        + "      cb.resolve(new Response(bytes,{" + "        status:status,"
        + "        headers:{'Content-Type':ct||'application/octet-stream'}" + "      }));"
        + "    }catch(e){cb.reject(new Error(String(e)));}" + "  } else {"
        + "    cb.reject(new Error('proxy failed'));" + "  }" + "};"

        // Хелпер: безопасный base64 (UTF-8)
        + "function toBase64(s){" + "  try{return btoa(unescape(encodeURIComponent(s)));}"
        + "  catch(e){return '';}" + "}"

        // Хелпер: headers → JSON-строка
        + "function headersToJson(headers){" + "  var h={};" + "  try{"
        + "    if(!headers)return '{}';" + "    if(headers instanceof Headers){"
        + "      headers.forEach(function(v,k){h[k]=v;});"
        + "    } else if(Array.isArray(headers)){" + "      for(var i=0;i<headers.length;i++){"
        + "        var p=headers[i];" + "        if(p&&p.length>=2)h[p[0]]=p[1];" + "      }"
        + "    } else {" + "      for(var k in headers){"
        + "        if(Object.prototype.hasOwnProperty.call(headers,k)){"
        + "          h[k]=headers[k];" + "        }" + "      }" + "    }"
        + "  }catch(e){return '{}';}" + "  return JSON.stringify(h);" + "}"

        // Прокси-запрос через Java-мост
        + "function proxyFetch(method,url,bodyB64,headersJson){"
        + "  var reqId='r'+Date.now()+'_'+Math.floor(Math.random()*100000);"
        + "  return new Promise(function(resolve,reject){"
        + "    pending[reqId]={resolve:resolve,reject:reject};" + "    try{"
        + "      window.MealseProxyBridge.proxyRequest("
        + "        reqId,method,url,bodyB64,headersJson);" + "    }catch(e){"
        + "      delete pending[reqId];" + "      reject(new Error(String(e)));" + "      return;"
        + "    }" + "    setTimeout(function(){" + "      if(pending[reqId]){"
        + "        delete pending[reqId];" + "        reject(new Error('timeout'));" + "      }"
        + "    },30000);" + "  });" + "}"

        // ★ Основной перехватчик fetch
        + "var origFetch=window.fetch;" + "window.fetch=function(input,init){" + "  try{"
        + "    var method=((init&&init.method)||'GET').toUpperCase();"
        + "    var url=typeof input==='string'?input:(input&&input.url);"
        + "    if(!url)return origFetch.apply(this,arguments);"

        // ★★ КЛЮЧЕВОЕ: перехватываем ТОЛЬКО если прокси реально доступен
        + "    var bridge=window.MealseProxyBridge;" + "    var canProxy=false;" + "    try{"
        + "      canProxy=!!(bridge&&bridge.proxyRequest&&bridge.isProxyAvailable"
        + "        &&bridge.isProxyAvailable());" + "    }catch(e){canProxy=false;}"

        + "    if((method==='POST'||method==='PUT'||method==='PATCH')&&canProxy){"
        + "      var bodyB64='';" + "      if(init&&init.body){"
        + "        if(typeof init.body==='string'){" + "          bodyB64=toBase64(init.body);"
        + "          if(!bodyB64)return origFetch.apply(this,arguments);"
        + "        } else if(typeof URLSearchParams!=='undefined'"
        + "                  &&init.body instanceof URLSearchParams){"
        + "          bodyB64=toBase64(init.body.toString());"
        + "          if(!bodyB64)return origFetch.apply(this,arguments);"
        + "        } else if(init.body instanceof FormData){"
        + "          console.warn('[Mealse] FormData → direct');"
        + "          return origFetch.apply(this,arguments);"
        + "        } else if(init.body instanceof ArrayBuffer){" + "          try{"
        + "            var b=new Uint8Array(init.body);" + "            var s='';"
        + "            for(var i=0;i<b.length;i++)s+=String.fromCharCode(b[i]);"
        + "            bodyB64=btoa(s);"
        + "          }catch(e){return origFetch.apply(this,arguments);}" + "        } else {"
        + "          return origFetch.apply(this,arguments);" + "        }" + "      }"
        + "      var headersJson=headersToJson(init&&init.headers);"
        + "      return proxyFetch(method,url,bodyB64,headersJson);" + "    }" + "  }catch(e){"
        + "    console.warn('[Mealse] proxy error',e);" + "  }"
        + "  return origFetch.apply(this,arguments);" + "};"

        // ★ Перехват XMLHttpRequest для POST/PUT/PATCH
        + "try{" + "  var XHR=window.XMLHttpRequest;" + "  if(XHR&&XHR.prototype){"
        + "    var origOpen=XHR.prototype.open;" + "    var origSend=XHR.prototype.send;"
        + "    var origSetHeader=XHR.prototype.setRequestHeader;"
        + "    XHR.prototype.open=function(method,url){"
        + "      this.__mealse_method=(method||'GET').toUpperCase();"
        + "      this.__mealse_url=url;" + "      this.__mealse_headers={};"
        + "      return origOpen.apply(this,arguments);" + "    };"
        + "    XHR.prototype.setRequestHeader=function(k,v){"
        + "      try{if(this.__mealse_headers)this.__mealse_headers[k]=v;}catch(e){}"
        + "      return origSetHeader.apply(this,arguments);" + "    };"
        + "    XHR.prototype.send=function(body){" + "      try{"
        + "        var m=this.__mealse_method||'GET';" + "        var u=this.__mealse_url;"
        + "        var bridge=window.MealseProxyBridge;" + "        var canProxy=false;"
        + "        try{"
        + "          canProxy=!!(bridge&&bridge.proxyRequest&&bridge.isProxyAvailable"
        + "            &&bridge.isProxyAvailable());" + "        }catch(e){canProxy=false;}"
        + "        if((m==='POST'||m==='PUT'||m==='PATCH')&&canProxy&&u){"
        + "          var bodyB64='';" + "          if(body){"
        + "            if(typeof body==='string'){" + "              bodyB64=toBase64(body);"
        + "              if(!bodyB64)return origSend.apply(this,arguments);"
        + "            } else if(typeof URLSearchParams!=='undefined'"
        + "                      &&body instanceof URLSearchParams){"
        + "              bodyB64=toBase64(body.toString());"
        + "              if(!bodyB64)return origSend.apply(this,arguments);"
        + "            } else {" + "              return origSend.apply(this,arguments);"
        + "            }" + "          }"
        + "          var hJson=JSON.stringify(this.__mealse_headers||{});"
        + "          var xhr=this;"
        + "          var id='x'+Date.now()+'_'+Math.floor(Math.random()*100000);"
        + "          pending[id]={" + "            resolve:function(resp){" + "              try{"
        + "                resp.arrayBuffer().then(function(buf){" + "                  try{"
        + "                    Object.defineProperty(xhr,'status',{value:resp.status,configurable:true});"
        + "                    Object.defineProperty(xhr,'responseText',{value:new TextDecoder().decode(buf),configurable:true});"
        + "                    Object.defineProperty(xhr,'response',{value:buf,configurable:true});"
        + "                    if(xhr.onload)xhr.onload();"
        + "                    if(xhr.onreadystatechange){"
        + "                      try{Object.defineProperty(xhr,'readyState',{value:4,configurable:true});}catch(e){}"
        + "                      xhr.onreadystatechange();" + "                    }"
        + "                  }catch(e){if(xhr.onerror)xhr.onerror(e);}" + "                });"
        + "              }catch(e){if(xhr.onerror)xhr.onerror(e);}" + "            },"
        + "            reject:function(err){if(xhr.onerror)xhr.onerror(err);}" + "          };"
        + "          try{" + "            bridge.proxyRequest(id,m,u,bodyB64,hJson);"
        + "          }catch(e){delete pending[id];return origSend.apply(this,arguments);}"
        + "          setTimeout(function(){"
        + "            if(pending[id]){delete pending[id];if(xhr.onerror)xhr.onerror(new Error('timeout'));}"
        + "          },30000);" + "          return;" + "        }"
        + "      }catch(e){console.warn('[Mealse] XHR error',e);}"
        + "      return origSend.apply(this,arguments);" + "    };" + "  }"
        + "}catch(e){console.warn('[Mealse] XHR patch failed',e);}"

        + "})();";

    try {
      view.evaluateJavascript(js, null);
    } catch (Throwable t) {
      log.warn("injectPostFormInterceptor: " + t.getMessage());
    }
  }

  // ============================================================
  // Утилиты
  // ============================================================

  private void toast(String message) {
    try {
      Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    } catch (Throwable ignored) {
    }
  }

  // ★ Adblock: обрезка URL для лога
  private static String truncate(String s, int max) {
    if (s == null) return "null";
    if (max <= 0) return "";
    return s.length() > max ? s.substring(0, max) + "…" : s;
  }

  private void setupBackHandler() {
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        backCallback = () -> {
          if (!handleBackNavigation()) {
            finish();
          }
        };
        getOnBackInvokedDispatcher()
            .registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        log.log("setupBackHandler: OnBackInvokedCallback зарегистрирован (SDK 33+)");
      } else {
        log.log("setupBackHandler: SDK < 33, используется onBackPressed()");
      }
    } catch (Throwable t) {
      log.warn("setupBackHandler: " + t.getMessage());
    }
  }

  private boolean handleBackNavigation() {
    try {
      if (webView != null && webView.canGoBack()) {
        log.log("back: webView.goBack()");
        webView.goBack();
        return true;
      }
      log.log("back: webView.canGoBack()=false → finish()");
      return false;
    } catch (Throwable t) {
      log.warn("handleBackNavigation: " + t.getMessage());
      return false;
    }
  }
}