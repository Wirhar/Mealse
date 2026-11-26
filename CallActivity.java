package com.my.mealse;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.*;
import android.widget.*;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.my.mealse.messenger.MessengerEvents;
import com.my.mealse.messenger.model.AttachmentInfo;
import com.my.mealse.messenger.model.Conversation;
import com.my.mealse.messenger.model.Message;
import com.my.mealse.messenger.model.ParticipantRole;
import com.my.mealse.messenger.repo.ConversationRepository;
import com.my.mealse.messenger.repo.MessageRepository;
import com.my.mealse.messenger.repo.ParticipantRepository;
import com.my.mealse.messenger.transport.MessageCommands;
import com.my.mealse.messenger.ui.MessageAdapter;
import com.my.mealse.model.TrustedNode;
import com.my.mealse.service.ConnectionManager;
import com.my.mealse.util.*;
import com.my.mealse.util.WifiDirectManager.Peer;
import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import android.provider.MediaStore;
import androidx.core.content.FileProvider;
import com.my.mealse.messenger.storage.AttachmentStorage;

/**
 * CallActivity — экран звонка (WebRTC внутри WebView) + оверлей чата.
 *
 * ★ ГЛАВНОЕ ИЗМЕНЕНИЕ (retained-движок):
 *
 * WebView с PeerConnection больше НЕ принадлежит Activity. Он создаётся один раз на процесс (static sWebView), живёт в application-контексте и переживает уничтожение Activity системой (Don't keep activities / MIUI task-kill / OOM). Activity только attach'ит его в свой layout при onCreate и detach'ит в onDestroy.
 *
 * Из этого следуют правила: - onCreate имеет ДВЕ ветки: reuse (движок уже жив, тот же callId) и new (движок мёртв или другой callId) — см. onCreate. - onDestroy НЕ вызывает webView.destroy(), пока звонок не завершён. Уничтожение движка — только в endCallInternal(). - Все поля звонка (callId, targetIp, targetMode, byeAlreadySent и т.п.) стали static — их видят и сигнальный receiver, и WebAppInterface, даже когда Activity мертва. - Сигнальный broadcastReceiver (sSignalReceiver) — static, живёт на уровне процесса. Пока UI мёртв, сигналы буферизуются в sPendingSignals и flush'атся при возврате.
 *
 * ★ ЕДИНАЯ ТОЧКА ЗАВЕРШЕНИЯ (endCallInternal):
 *
 * Все пути завершения (пользователь → onBackPressed, кнопка «Завершить» в call.html → onCallEnded, ошибка JS → onCallError, собеседник → bye, отказ в разрешениях) сходятся в endCallInternal(notifyPeer, reason). Только там: sendBye (если нужно), clearCallActive, teardown движка, CallService.stop, finish UI. Это устраняет дубли bye и «зомби-звонки».
 *
 * ★ COOLDOWN/DEДУП — не здесь:
 *
 * Дедупликация offer по call_id и cooldown после bye — в ServerService (HTTP-путь) и MealseApp.handleWifiDirectSignal (WD-путь). CallActivity полагается на их фильтрацию и на processOffer в call.html, где повторный stale-offer на живом PC игнорируется, а на failed/closed — обрабатывается заново (см. комментарий к processOffer).
 */
public class CallActivity extends Activity {

  private static final String TAG = "CallActivity";

  private static final String EXTRA_CALL_ID = "call_id";
  private static final String STATE_CALL_ID = "state_call_id";
  private static final String STATE_OFFER_HANDLED = "state_offer_handled";

  // ★ Request codes для вложений в чате звонка (pick-mode FileManagerActivity / SAF)
  private static final int REQ_PICK_APP_MANAGER = 5010;
  // private static final int REQ_PICK_FILE = 5001;
  private static final int REQ_PICK_MEDIA = 5002;

  private static final int REQ_CAPTURE_PHOTO = 5003;
  private static final int REQ_CAPTURE_VIDEO = 5004;

  private Uri pendingCameraUri;
  private File pendingCameraFile;

  // ============================================================================
  // RETained Call Engine (static, переживает пересоздание Activity)
  // ============================================================================

  /** Lock для всех мутаций движка (создание/уничтожение). */
  private static final Object ENGINE_LOCK = new Object();

  /** Главный Handler для UI-операций из static-контекста. */
  private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

  /** Application context — используется, когда Activity уже мертва. */
  private static Context sAppCtx;

  /** Текущая Activity или null, если UI уничтожен, а движок жив. */
  private static CallActivity sUi;

  /** WebRTC-WebView. Создаётся на application-контексте, живёт вне Activity. */
  private static WebView sWebView;

  /** Флаг: call.html загружен (onPageFinished). */
  private static volatile boolean sWebViewLoaded = false;

  /** Флаг: движок уничтожен (destroy() вызван в endCallInternal). */
  private static volatile boolean sWebViewDestroyed = false;

  /** Флаг: configureIce/configureIceMode уже отправлены в WebView. */
  private static volatile boolean sEngineInitialized = false;

  /** Флаг: звонок завершён (endCallInternal отработал). */
  private static volatile boolean sCallEnded = false;

  // ----- Параметры текущего звонка -----
  private static volatile String sCallId;
  private static volatile String sTargetIp;
  private static volatile int sTargetPort = 8081;
  private static volatile TrustedNode.Mode sTargetMode = TrustedNode.Mode.AUTO;
  private static volatile String sTargetGatewayIp;
  private static volatile int sTargetGatewayPort = 8083;
  private static volatile String sTargetFingerprint;
  private static volatile String sLocalFingerprint = "";
  private static volatile boolean sIsWifiDirectMode = false;
  private static volatile boolean sIsIncoming = false;
  private static volatile String sIncomingOfferJson;
  private static volatile boolean sIncomingOfferHandled = false;

  /**
   * ★ НОВОЕ (замечание 2): targetNodeId в static-поле.
   *
   * Раньше targetNodeId — instance-поле. При reuse-ветке onCreate (пересоздание Activity) он читался из intent; если intent без "target_node_id" (запуск из уведомления) — терялся. При автосбросе CallStateManager (heartbeat > 15 с) getActiveTargetNode() = null → флаг переустанавливался с null.
   *
   * Теперь sTargetNodeId переживает reuse и автосброс. startCallServiceIfPermitted передаёт его в CallService → ensureCallActive не теряет данные узла.
   */
  private static volatile String sTargetNodeId;

  // ----- Флаги завершения -----
  private static volatile boolean sByeAlreadySent = false;
  private static volatile boolean sEndingByUser = false;

  // ----- SignalReceiver и WD-listener (static, живут вне Activity) -----
  private static volatile boolean sReceiverRegistered = false;
  private static BroadcastReceiver sSignalReceiver;
  private static WifiDirectManager sWifiDirectManager;
  private static WifiDirectManager.Listener sWdListener;

  // ----- Буфер сигналов, пока UI/WebView не готов -----
  private static final List<PendingSignal> sPendingSignals = new ArrayList<>();

  /** Счётчик отправленных сигналов (для лога). */
  private static final AtomicInteger sSignalSentCount = new AtomicInteger(0);

  /** Вложенный класс буферизованного сигнала. */
  private static class PendingSignal {
    final String type;
    final String json;

    PendingSignal(String type, String json) {
      this.type = type;
      this.json = json;
    }
  }

  /** ★ ИЗМЕНЕНО (п.18): всегда через MealseLogger.get(sAppCtx, TAG) — он сам кеширует. */
  private static MealseLogger slog() {
    if (sAppCtx == null)
      return null;
    return MealseLogger.get(sAppCtx, TAG);
  }

  // ============================================================================
  // Поля UI/чата (instance, живут вместе с Activity)
  // ============================================================================

  private ImageButton btnChatToggle;
  private View chatOverlay;
  private ImageButton chatBtnClose;
  private ListView chatList;
  private TextView chatEmpty;
  private EditText chatInput;
  private ImageButton chatBtnSend;
  private ImageButton chatBtnAttach;
  private MessageAdapter chatAdapter;

  private String conversationId;
  private ConversationRepository chatConvRepo;
  private MessageRepository chatMsgRepo;

  private final ExecutorService chatExecutor = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "CallChat");
    t.setDaemon(true);
    return t;
  });
  private final Handler chatUi = new Handler(Looper.getMainLooper());
  private boolean chatReceiverRegistered = false;

  private final BroadcastReceiver chatRefreshReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      if (intent == null)
        return;
      String action = intent.getAction();
      if (action == null)
        return;

      String convId = intent.getStringExtra(MessengerEvents.EXTRA_CONVERSATION_ID);
      if (convId == null || !convId.equals(conversationId))
        return;

      if (MessengerEvents.ACTION_MESSAGE_PROGRESS.equals(action)) {
        if (chatAdapter != null) {
          chatUi.post(() -> {
            if (chatAdapter != null)
              chatAdapter.notifyDataSetChanged();
          });
        }
        return;
      }
      if (MessengerEvents.ACTION_MESSAGE_NEW.equals(action)
          || MessengerEvents.ACTION_MESSAGE_STATUS_CHANGED.equals(action)) {
        reloadChat();
      }
    }
  };

  private MealseLogger log;
  private SecureAuthManager secureAuthManager;
  private NodeManager nodeManager;

  /** targetNodeId — id узла из NodeManager (нужен чату и participants). */
  private String targetNodeId;

  // ============================================================================
  // Фабричный метод (без изменений)
  // ============================================================================

  public static Intent newCallIntent(Context context, TrustedNode node) {
    Intent intent = new Intent(context, CallActivity.class);
    intent.putExtra("target_node_id", node.getId());
    intent.putExtra("target_ip", node.getIpAddress());
    intent.putExtra("target_port", node.getPort());
    intent.putExtra("target_fingerprint", node.getFingerprint());
    intent.putExtra("target_mode", node.getMode().name());
    intent.putExtra("target_gateway_ip", node.getGatewayIp());
    intent.putExtra("target_gateway_port", node.getGatewayPort());
    return intent;
  }

  // ============================================================================
  // Публичный статический API
  // ============================================================================

  public static boolean isCallEngineActive(String callId) {
    synchronized (ENGINE_LOCK) {
      if (sWebView == null || sCallEnded)
        return false;
      if (callId == null)
        return true;
      return callId.equals(sCallId);
    }
  }

  // ===== P3: static heartbeat — не держит Activity, один цикл на процесс =====

  private static CallStateManager sCallStateManager;

  // ===== P1: gate инициализации JS и старта FGS по runtime-разрешениям =====
  private static volatile boolean sJsInitSent = false;
  private static volatile boolean sCallServiceStarted = false;

  /**
   * ★ P0 (BUG-CA6): OnBackInvokedCallback для API 33+. Регистрируется в onCreate ДО развилки reuse/new.
   */
  private android.window.OnBackInvokedCallback backInvokedCallback;

  // ============================================================================
  // onCreate — две ветки (reuse / new)
  // ============================================================================

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    log = MealseLogger.get(this, TAG);
    sAppCtx = getApplicationContext();
    sUi = this;
    log.log("══ onCreate START ══");

    try {
      setContentView(R.layout.activity_call);

      // ★ P0: OnBackInvokedCallback ДО развилки reuse/new (иначе в reuse-ветке
      // callback не зарегистрируется — там ранний return).
      if (Build.VERSION.SDK_INT >= 33) {
        try {
          backInvokedCallback = () -> {
            if (!isFinishing() && !isDestroyed()) {
              onBackPressed();
            }
          };
          getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
              android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backInvokedCallback);
          log.log("onCreate: OnBackInvokedCallback зарегистрирован (API 33+)");
        } catch (Throwable t) {
          log.warn("onCreate: registerOnBackInvokedCallback: " + t.getMessage());
        }
      }

      Intent intent = getIntent();
      String requestedCallId = savedInstanceState != null
          ? savedInstanceState.getString(STATE_CALL_ID)
          : intent.getStringExtra(EXTRA_CALL_ID);

      // ------------------------------------------------------------------
      // ВЕТКА 1: движок жив, тот же callId → reattach UI
      // ------------------------------------------------------------------
      synchronized (ENGINE_LOCK) {
        if (sWebView != null && !sCallEnded && sCallId != null && sCallId.equals(requestedCallId)) {
          log.log("onCreate: REUSE движка, callId=" + shortId(sCallId));

          // ★ ИЗМЕНЕНО (замечание 2): targetNodeId из intent ИЛИ sTargetNodeId
          String nid = intent.getStringExtra("target_node_id");
          if (nid != null && !nid.isEmpty()) {
            targetNodeId = nid;
            sTargetNodeId = nid;
          } else {
            targetNodeId = sTargetNodeId; // не теряем при запуске из уведомления
          }

          sCallStateManager = new CallStateManager(this);

          attachRetainedWebView();
          checkFullScreenIntentPermission();
          requestPermissions();
          registerSignalReceiver();

          if (sIsWifiDirectMode)
            subscribeWifiDirectListener();

          initChatViews();
          initCallConversation();
          subscribeChatReceiver();

          startCallServiceIfPermitted();

          flushPendingSignals();
          evaluateJs("onAppForegrounded()");

          log.log("══ onCreate END (reuse) ══");
          return;
        }

        if (sWebView != null && !sCallEnded && sCallId != null
            && !sCallId.equals(requestedCallId)) {
          Toast.makeText(this, "Уже идёт другой звонок", Toast.LENGTH_LONG).show();
          finish();
          return;
        }
      }

      // ------------------------------------------------------------------
      // ВЕТКА 2: новый звонок
      // ------------------------------------------------------------------
      targetNodeId = intent.getStringExtra("target_node_id");
      // ★ НОВОЕ (замечание 2): сохраняем в static — переживает reuse/автосброс
      sTargetNodeId = targetNodeId;

      sTargetIp = intent.getStringExtra("target_ip");
      sTargetPort = intent.getIntExtra("target_port", 8081);
      sIsIncoming = intent.getBooleanExtra("is_incoming", false);
      sIncomingOfferJson = intent.getStringExtra("incoming_offer_json");
      sTargetFingerprint = intent.getStringExtra("target_fingerprint");
      sTargetMode = TrustedNode.Mode.fromString(intent.getStringExtra("target_mode"));
      sTargetGatewayIp = intent.getStringExtra("target_gateway_ip");
      sTargetGatewayPort = intent.getIntExtra("target_gateway_port", 8083);

      log.log("target_node_id=" + targetNodeId);
      log.log("target_ip=" + sTargetIp);
      log.log("target_port=" + sTargetPort);
      log.log("is_incoming=" + sIsIncoming);
      log.log("target_fingerprint=" + sTargetFingerprint);
      log.log("target_mode=" + sTargetMode);
      log.log("incoming_offer_json="
          + (sIncomingOfferJson != null ? sIncomingOfferJson.length() + " симв." : "null"));

      if (savedInstanceState != null && sIsIncoming) {
        log.warn("onCreate: пересоздание после смерти процесса — stale offer сброшен");
        sIncomingOfferJson = null;
      }

      if (sIsIncoming && sIncomingOfferJson != null && !sIncomingOfferJson.isEmpty()) {
        try {
          JsonObject check = com.google.gson.JsonParser.parseString(sIncomingOfferJson)
              .getAsJsonObject();
          String t = check.has("type") && !check.get("type").isJsonNull()
              ? check.get("type").getAsString()
              : null;
          if (!"offer".equals(t)) {
            log.warn("incoming_offer_json type=" + t + " != offer — игнорирую");
            sIncomingOfferJson = null;
          }
        } catch (Exception e) {
          log.warn("incoming_offer_json не JSON (" + e.getMessage() + ") — игнорирую");
          sIncomingOfferJson = null;
        }
      }

      // ===== Wi-Fi Direct: проверка + HTTP-fallback =====
      sIsWifiDirectMode = (sTargetMode == TrustedNode.Mode.WIFI_DIRECT);
      sWifiDirectManager = ((MealseApp) getApplication()).getWifiDirectManager();

      if (sIsWifiDirectMode) {
        if (sWifiDirectManager == null || !sWifiDirectManager.isWifiDirectSupported()) {
          Toast.makeText(this, "Wi-Fi Direct недоступен", Toast.LENGTH_LONG).show();
          finish();
          return;
        }
        if (!sWifiDirectManager.isConnected()) {
          log.error("Wi-Fi Direct не подключён — пробуем HTTP-fallback");
          TrustedNode httpNode = null;
          try {
            NodeManager nm = new NodeManager(this);
            if (sTargetFingerprint != null && !sTargetFingerprint.isEmpty()) {
              for (TrustedNode n : nm.findAllByFingerprint(sTargetFingerprint)) {
                if (n.getMode() != TrustedNode.Mode.WIFI_DIRECT && n.getIpAddress() != null
                    && !n.getIpAddress().isEmpty()) {
                  httpNode = n;
                  break;
                }
              }
            }
            if (httpNode == null && targetNodeId != null) {
              TrustedNode byId = nm.getNodeById(targetNodeId);
              if (byId != null && byId.getMode() != TrustedNode.Mode.WIFI_DIRECT
                  && byId.getIpAddress() != null && !byId.getIpAddress().isEmpty()) {
                httpNode = byId;
              }
            }
          } catch (Exception e) {
            log.warn("HTTP-fallback: " + e.getMessage());
          }

          if (httpNode != null) {
            log.log("Wi-Fi Direct → HTTP-fallback: " + httpNode.getKey());
            sTargetIp = httpNode.getIpAddress();
            sTargetPort = httpNode.getPort();
            sTargetMode = httpNode.getMode() != null ? httpNode.getMode() : TrustedNode.Mode.AUTO;
            sIsWifiDirectMode = false;
          } else {
            Toast.makeText(this, "Wi-Fi Direct не подключён. Подключитесь в режиме Wi-Fi Direct.",
                Toast.LENGTH_LONG).show();
            finish();
            return;
          }
        }
      }

      if (sTargetIp == null || sTargetIp.isEmpty()) {
        Toast.makeText(this, "Не указан IP узла", Toast.LENGTH_LONG).show();
        finish();
        return;
      }

      // ===== Self-call защита =====
      secureAuthManager = new SecureAuthManager(this);
      sLocalFingerprint = secureAuthManager.getDeviceFingerprint();
      if (sLocalFingerprint == null)
        sLocalFingerprint = "";

      String selfIp = DeviceInfoHelper.getSelfIp(this);
      if ((!sLocalFingerprint.isEmpty() && sLocalFingerprint.equals(sTargetFingerprint))
          || (!sIsWifiDirectMode && selfIp != null && selfIp.equals(sTargetIp))) {
        Toast.makeText(this, "Нельзя позвонить самому себе", Toast.LENGTH_LONG).show();
        finish();
        return;
      }

      // ===== callId =====
      sCallId = requestedCallId != null ? requestedCallId : UUID.randomUUID().toString();

      sCallEnded = false;
      sByeAlreadySent = false;
      sEndingByUser = false;
      sIncomingOfferHandled = false;
      sWebViewLoaded = false;
      sWebViewDestroyed = false;
      sEngineInitialized = false;
      sJsInitSent = false;
      sCallServiceStarted = false;
      synchronized (sPendingSignals) {
        sPendingSignals.clear();
      }

      log.log("callId=" + sCallId + ", isIncoming=" + sIsIncoming + ", mode=" + sTargetMode);

      // ===== Менеджеры =====
      sCallStateManager = new CallStateManager(this);
      if (sCallStateManager.isCallActive()) {
        String active = sCallStateManager.getActiveCallId();
        if (active != null && !active.equals(sCallId)) {
          Toast.makeText(this, "Уже идёт другой звонок", Toast.LENGTH_LONG).show();
          finish();
          return;
        }
      }
      sCallStateManager.setCallActive(sCallId, targetNodeId);

      // ===== UI/движок =====
      checkFullScreenIntentPermission();
      setupWebView();
      registerSignalReceiver();
      if (sIsWifiDirectMode)
        subscribeWifiDirectListener();
      requestPermissions();

      initChatViews();
      initCallConversation();
      subscribeChatReceiver();

      startCallServiceIfPermitted();

      log.log("══ onCreate END (new) ══ — targetIp=" + sTargetIp + ":" + sTargetPort + ", callId="
          + shortId(sCallId) + ", isIncoming=" + sIsIncoming + ", isWifiDirectMode="
          + sIsWifiDirectMode);

    } catch (Exception e) {
      logError("КРИТИЧЕСКАЯ ОШИБКА в onCreate", e);
      Toast.makeText(this, "Ошибка запуска звонка: " + e.getMessage(), Toast.LENGTH_LONG).show();
      finish();
    }
  }

  /** ★ static (замечание 2 — работает без Activity). */
  private static String resolvePeerName() {
    try {
      if (sTargetFingerprint != null && !sTargetFingerprint.isEmpty() && sAppCtx != null) {
        NodeManager nm = new NodeManager(sAppCtx);
        TrustedNode n = nm.findByFingerprint(sTargetFingerprint);
        if (n != null && n.getName() != null && !n.getName().isEmpty())
          return n.getName();
      }
    } catch (Exception ignored) {
    }
    return sTargetIp != null ? sTargetIp : "собеседник";
  }

  /** P1: выданы ли runtime-разрешения CAMERA + RECORD_AUDIO. */
  private static boolean hasMediaPermissions() {
    if (sAppCtx == null)
      return false;
    try {
      return sAppCtx
          .checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
          && sAppCtx.checkSelfPermission(
              Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * ★ ИЗМЕНЕНО (замечание 2): передаём sTargetNodeId в CallService. Чтобы ensureCallActive не терял данные узла при автосбросе.
   */
  private static void startCallServiceIfPermitted() {
    if (sCallEnded || sCallId == null || sAppCtx == null)
      return;
    if (!hasMediaPermissions()) {
      MealseLogger l = slog();
      if (l != null)
        l.log("startCallServiceIfPermitted: разрешения не выданы — жду grant");
      return;
    }
    try {
      // ★ ФИКС (замечание 2): + sTargetNodeId
      CallService.start(sAppCtx, sCallId, resolvePeerName(), sTargetNodeId);
      sCallServiceStarted = true;
      MealseLogger l = slog();
      if (l != null)
        l.log("startCallServiceIfPermitted: CallService.start OK, callId=" + shortIdStatic(sCallId)
            + ", node=" + sTargetNodeId);
    } catch (Exception e) {
      MealseLogger l = slog();
      if (l != null)
        l.logError("startCallServiceIfPermitted", e);
    }
  }

  /**
   * P1: отправляет setIncomingMode (+ сохранённый offer) в JS только после grant.
   */
  private static void onMediaPermissionsReady() {
    if (sCallEnded || sWebView == null || sWebViewDestroyed || !sWebViewLoaded)
      return;
    if (sJsInitSent)
      return;
    if (!hasMediaPermissions()) {
      MealseLogger l = slog();
      if (l != null)
        l.log("onMediaPermissionsReady: жду CAMERA/RECORD_AUDIO");
      return;
    }
    sJsInitSent = true;
    evaluateJs("setIncomingMode(" + sIsIncoming + ")");
    MealseLogger l = slog();
    if (l != null)
      l.log("→ JS: setIncomingMode(" + sIsIncoming + ") [after grant]");

    if (sIsIncoming && sIncomingOfferJson != null && !sIncomingOfferHandled) {
      evaluateJs("onRemoteOffer('" + escapeForJsStatic(sIncomingOfferJson) + "')");
      sIncomingOfferHandled = true;
      if (l != null)
        l.log("→ JS: onRemoteOffer (сохранённый) [after grant]");
    }
    flushPendingSignals();
  }

  // ============================================================================
  // attachRetainedWebView
  // ============================================================================

  private void attachRetainedWebView() {
    View placeholder = findViewById(R.id.webview_call);
    if (placeholder == null) {
      throw new IllegalStateException("webview_call не найден в activity_call.xml");
    }
    ViewGroup parent = (ViewGroup) placeholder.getParent();
    int index = parent.indexOfChild(placeholder);
    parent.removeView(placeholder);
    if (placeholder instanceof WebView) {
      try {
        ((WebView) placeholder).destroy();
      } catch (Exception ignored) {
      }
    }
    if (sWebView.getParent() instanceof ViewGroup) {
      ((ViewGroup) sWebView.getParent()).removeView(sWebView);
    }
    parent.addView(sWebView, index);
    try {
      sWebView.onResume();
    } catch (Exception ignored) {
    }
    log.log("attachRetainedWebView: OK, loaded=" + sWebViewLoaded);
  }

  // ============================================================================
  // setupWebView
  // ============================================================================

  private void setupWebView() {
    try {
      sWebView = new WebView(getApplicationContext());
      WebSettings settings = sWebView.getSettings();
      settings.setJavaScriptEnabled(true);
      settings.setMediaPlaybackRequiresUserGesture(false);
      settings.setDomStorageEnabled(true);
      settings.setAllowFileAccess(false);
      settings.setAllowFileAccessFromFileURLs(false);
      settings.setAllowUniversalAccessFromFileURLs(false);
      settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
      log.log("WebSettings настроены (file-access off, mixed-content never)");

      sWebView.addJavascriptInterface(new WebAppInterface(), "android");
      log.log("JS-интерфейс 'android' добавлен");

      sWebView.setWebViewClient(new EngineWebViewClient());
      log.log("WebViewClient установлен (static)");
      sWebView.setWebChromeClient(new EngineWebChromeClient());
      log.log("WebChromeClient установлен (static)");

      attachRetainedWebView();
      sWebView.loadUrl("file:///android_asset/call.html");
      log.log("call.html загружается");
    } catch (Exception e) {
      logError("Ошибка setupWebView", e);
    }
  }

  // ============================================================================
  // evaluateJs
  // ============================================================================

  private static void evaluateJs(final String js) {
    if (sWebViewDestroyed) {
      MealseLogger l = slog();
      if (l != null)
        l.warn("evaluateJs: webView уничтожен — пропуск");
      return;
    }
    sMainHandler.post(() -> {
      WebView w = sWebView;
      if (w == null || sWebViewDestroyed) {
        MealseLogger l = slog();
        if (l != null)
          l.warn("evaluateJs: webView == null/destroyed");
        return;
      }
      try {
        w.evaluateJavascript(js, null);
      } catch (Exception e) {
        MealseLogger l = slog();
        if (l != null)
          l.logError("evaluateJavascript", e);
      }
    });
  }

  // ============================================================================
  // Static приём сигналов
  // ============================================================================

  private static void ensureStaticSignalReceiver() {
    if (sSignalReceiver != null)
      return;
    sSignalReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        try {
          if (intent == null)
            return;
          String json = intent.getStringExtra("signal_json");
          if (json == null)
            json = intent.getStringExtra("raw");
          if (json == null)
            return;
          handleIncomingSignal(json);
        } catch (Exception e) {
          MealseLogger l = slog();
          if (l != null)
            l.logError("sSignalReceiver", e);
        }
      }
    };
  }

  private static void handleIncomingSignal(String json) {
    JsonObject obj;
    try {
      obj = new Gson().fromJson(json, JsonObject.class);
    } catch (Exception e) {
      MealseLogger l = slog();
      if (l != null)
        l.logError("handleIncomingSignal: parse", e);
      return;
    }
    if (obj == null || !obj.has("type"))
      return;

    if (obj.has("call_id") && !obj.get("call_id").isJsonNull() && sCallId != null) {
      String incomingCallId = obj.get("call_id").getAsString();
      if (!sCallId.equals(incomingCallId)) {
        MealseLogger l = slog();
        if (l != null)
          l.log("Игнорируем сигнал для другого call_id: " + shortIdStatic(incomingCallId));
        return;
      }
    }

    String type = obj.get("type").getAsString();
    MealseLogger l = slog();
    if (l != null)
      l.logSignal("RX", type, sCallId);

    if (!sWebViewLoaded || sWebView == null || sWebViewDestroyed) {
      synchronized (sPendingSignals) {
        if (sPendingSignals.size() < 256)
          sPendingSignals.add(new PendingSignal(type, json));
      }
      return;
    }

    if (!hasMediaPermissions() && !"bye".equals(type)) {
      synchronized (sPendingSignals) {
        if (sPendingSignals.size() < 256)
          sPendingSignals.add(new PendingSignal(type, json));
      }
      return;
    }

    dispatchSignal(obj, type, json);
  }

  private static void dispatchSignal(JsonObject obj, String type, String json) {
    String escaped = escapeForJsStatic(json);
    MealseLogger l = slog();
    switch (type) {
      case "offer":
        if (l != null)
          l.log("dispatch: offer → onRemoteOffer");
        evaluateJs("onRemoteOffer('" + escaped + "')");
        break;
      case "answer":
        if (l != null)
          l.log("dispatch: answer → onRemoteAnswer");
        evaluateJs("onRemoteAnswer('" + escaped + "')");
        break;
      case "candidate":
        if (l != null)
          l.log("dispatch: candidate → onRemoteCandidate");
        evaluateJs("onRemoteCandidate('" + escaped + "')");
        break;
      case "bye":
        if (l != null)
          l.log("dispatch: bye → handleBye");
        handleBye(obj);
        break;
      case "message":
      case "file_chunk":
        break;
      default:
        if (l != null)
          l.warn("dispatch: неизвестный type=" + type);
    }
  }

  private static void flushPendingSignals() {
    if (!hasMediaPermissions())
      return;

    List<PendingSignal> copy;
    synchronized (sPendingSignals) {
      if (sPendingSignals.isEmpty())
        return;
      copy = new ArrayList<>(sPendingSignals);
      sPendingSignals.clear();
    }
    MealseLogger l = slog();
    if (l != null)
      l.log("flushPendingSignals: " + copy.size());
    for (PendingSignal s : copy) {
      try {
        JsonObject obj = new Gson().fromJson(s.json, JsonObject.class);
        dispatchSignal(obj, s.type, s.json);
      } catch (Exception e) {
        MealseLogger lg = slog();
        if (lg != null)
          lg.logError("flushPendingSignals: " + s.type, e);
      }
    }
  }

  // ============================================================================
  // SignalReceiver / WD-listener — static
  // ============================================================================

  private static void registerSignalReceiver() {
    if (sReceiverRegistered)
      return;
    try {
      ensureStaticSignalReceiver();
      IntentFilter filter = new IntentFilter("com.my.mealse.SIGNAL");
      BroadcastUtils.registerNotExported(sAppCtx, sSignalReceiver, filter);
      sReceiverRegistered = true;
      MealseLogger l = slog();
      if (l != null)
        l.log("sSignalReceiver зарегистрирован (static)");
    } catch (Exception e) {
      MealseLogger l = slog();
      if (l != null)
        l.logError("registerSignalReceiver", e);
    }
  }

  private static void unregisterSignalReceiver() {
    if (!sReceiverRegistered)
      return;
    try {
      BroadcastUtils.unregisterSafely(sAppCtx, sSignalReceiver);
      sReceiverRegistered = false;
      MealseLogger l = slog();
      if (l != null)
        l.log("sSignalReceiver снят (static)");
    } catch (Exception e) {
      MealseLogger l = slog();
      if (l != null)
        l.logError("unregisterSignalReceiver", e);
    }
  }

  private static void subscribeWifiDirectListener() {
    if (sWifiDirectManager == null)
      return;
    if (sWdListener != null)
      return;
    try {
      sWdListener = new WifiDirectManager.Listener() {
        @Override
        public void onPeersChanged(List<Peer> peers) {
        }

        @Override
        public void onDiscoveryStarted() {
        }

        @Override
        public void onDiscoveryStopped() {
        }

        @Override
        public void onConnected(Peer remote, boolean isGroupOwner) {
          MealseLogger l = slog();
          if (l != null)
            l.log("WD: onConnected (уже было)");
        }

        @Override
        public void onDisconnected() {
          MealseLogger l = slog();
          if (l != null)
            l.warn("WD: onDisconnected — завершаем звонок");
          sMainHandler.post(() -> {
            try {
              Toast.makeText(sAppCtx, "Wi-Fi Direct соединение потеряно", Toast.LENGTH_LONG).show();
              endCallInternal(true, "connection_lost");
            } catch (Exception ignored) {
            }
          });
        }

        /**
         * P4: ТОЛЬКО offer (candidate/answer/bye — через MealseApp broadcast).
         */
        @Override
        public void onSignal(String json) {
          try {
            JsonObject obj = new Gson().fromJson(json, JsonObject.class);
            if (obj == null || !obj.has("type"))
              return;
            String type = obj.get("type").getAsString();
            if (!"offer".equals(type))
              return; // остальное — через broadcast
            handleIncomingSignal(json);
          } catch (Exception e) {
            MealseLogger l = slog();
            if (l != null)
              l.logError("WD onSignal (offer)", e);
          }
        }

        @Override
        public void onError(String message) {
          MealseLogger l = slog();
          if (l != null)
            l.error("WD onError: " + message);
        }
      };
      sWifiDirectManager.addListener(sWdListener);
      MealseLogger l = slog();
      if (l != null)
        l.log("subscribeWifiDirectListener: OK (offer-only)");
    } catch (Exception e) {
      MealseLogger l = slog();
      if (l != null)
        l.logError("subscribeWifiDirectListener", e);
    }
  }

  private static void unsubscribeWifiDirectListener() {
    if (sWifiDirectManager == null || sWdListener == null)
      return;
    try {
      sWifiDirectManager.removeListener(sWdListener);
      sWdListener = null;
    } catch (Exception ignored) {
    }
  }

  // ============================================================================
  // Завершение звонка
  // ============================================================================

  private static void handleBye(JsonObject obj) {
    String reason = "hangup";
    try {
      if (obj.has("payload") && obj.get("payload").isJsonObject()) {
        JsonObject p = obj.getAsJsonObject("payload");
        if (p.has("reason") && !p.get("reason").isJsonNull()) {
          reason = p.get("reason").getAsString();
        }
      }
    } catch (Exception ignored) {
    }
    final String r = reason;

    sMainHandler.post(() -> {
      String msg;
      switch (r) {
        case "busy":
          msg = "Абонент занят";
          break;
        case "declined":
          msg = "Вызов отклонён";
          break;
        default:
          msg = "Звонок завершён";
      }
      try {
        Toast.makeText(sAppCtx, msg, Toast.LENGTH_SHORT).show();
      } catch (Exception ignored) {
      }
    });

    endCallInternal(false, r);
  }

  private void endCallByUser() {
    endCallInternal(true, "hangup");
  }

  /**
   * ★ Единая точка завершения.
   */
  private static void endCallInternal(boolean notifyPeer, String reason) {
    synchronized (ENGINE_LOCK) {
      if (sCallEnded) {
        MealseLogger l = slog();
        if (l != null)
          l.log("endCallInternal: уже завершён — игнор");
        return;
      }
      sCallEnded = true;
      sEndingByUser = true;

      MealseLogger l = slog();
      if (l != null)
        l.log("endCallInternal: notifyPeer=" + notifyPeer + ", reason=" + reason);

      if (notifyPeer && !sByeAlreadySent) {
        sByeAlreadySent = true;
        sendBye(reason);
      }

      unregisterSignalReceiver();
      unsubscribeWifiDirectListener();

      synchronized (sPendingSignals) {
        sPendingSignals.clear();
      }

      final WebView w = sWebView;
      sWebViewDestroyed = true;
      sWebViewLoaded = false;
      sEngineInitialized = false;
      sJsInitSent = false;
      sWebView = null;
      sMainHandler.post(() -> {
        if (w != null) {
          try {
            w.loadUrl("about:blank");
          } catch (Exception ignored) {
          }
          try {
            w.destroy();
          } catch (Exception ignored) {
          }
        }
      });

      try {
        if (sCallStateManager != null)
          sCallStateManager.clearCallActive();
        else
          new CallStateManager(sAppCtx).clearCallActive();
      } catch (Exception ignored) {
      }

      if (sCallServiceStarted) {
        try {
          CallService.stop(sAppCtx);
        } catch (Exception ignored) {
        }
        sCallServiceStarted = false;
      }

      final CallActivity ui = sUi;
      if (ui != null) {
        ui.runOnUiThread(() -> {
          try {
            ui.finish();
          } catch (Exception ignored) {
          }
        });
      }

      sCallId = null;
      sTargetIp = null;
      sTargetFingerprint = null;
      sTargetNodeId = null; // ★ НОВОЕ (замечание 2): сброс
      sIncomingOfferJson = null;
      sIsIncoming = false;
      sIsWifiDirectMode = false;
    }
  }

  private static void sendBye(String reason) {
    try {
      JsonObject obj = new JsonObject();
      obj.addProperty("type", "bye");
      obj.addProperty("from_device_id", DeviceInfoHelper.getDeviceId(sAppCtx));
      obj.addProperty("from_fingerprint", sLocalFingerprint != null ? sLocalFingerprint : "");
      obj.addProperty("call_id", sCallId);
      obj.addProperty("timestamp", System.currentTimeMillis() / 1000);

      JsonObject payload = new JsonObject();
      payload.addProperty("reason", reason != null ? reason : "hangup");
      obj.add("payload", payload);

      MealseLogger l = slog();
      if (l != null)
        l.log("sendBye: callId=" + shortIdStatic(sCallId) + ", reason=" + reason + ", wd="
            + sIsWifiDirectMode);

      if (sIsWifiDirectMode) {
        if (sWifiDirectManager != null && sWifiDirectManager.isConnected()) {
          sWifiDirectManager.sendSignal(obj.toString());
        }
        return;
      }
      if (sTargetIp == null || sTargetIp.isEmpty())
        return;

      Intent svc = new Intent(sAppCtx, ConnectionManager.class);
      svc.setAction(ConnectionManager.ACTION_SEND_SIGNAL);
      svc.putExtra(ConnectionManager.EXTRA_TARGET_IP, sTargetIp);
      svc.putExtra(ConnectionManager.EXTRA_TARGET_PORT, sTargetPort);
      svc.putExtra(ConnectionManager.EXTRA_SIGNAL_TYPE, "bye");
      svc.putExtra(ConnectionManager.EXTRA_SIGNAL_BODY, obj.toString());
      sAppCtx.startForegroundService(svc);
    } catch (Exception e) {
      MealseLogger l = slog();
      if (l != null)
        l.logError("sendBye", e);
    }
  }

  // ============================================================================
  // Lifecycle
  // ============================================================================

  @Override
  protected void onResume() {
    super.onResume();
    sUi = this;
    log.log("══ onResume START ══");

    if (!sCallEnded) {
      startCallServiceIfPermitted();
      onMediaPermissionsReady();

      // ★ ИЗМЕНЕНО (замечание 2): используем sTargetNodeId, не targetNodeId
      if (sCallStateManager != null && sCallId != null) {
        try {
          String node = (sTargetNodeId != null) ? sTargetNodeId : targetNodeId;
          sCallStateManager.setCallActive(sCallId, node);
        } catch (Exception ignored) {
        }
      }

      if (!sIsWifiDirectMode && !sReceiverRegistered)
        registerSignalReceiver();
      if (sIsWifiDirectMode && sWdListener == null)
        subscribeWifiDirectListener();
    }

    evaluateJs("onAppForegrounded()");
    flushPendingSignals();

    if (chatOverlay != null && chatOverlay.getVisibility() == View.VISIBLE
        && conversationId != null) {
      try {
        MessageCommands.markRead(this, conversationId);
      } catch (Exception ignored) {
      }
    }
    log.log("══ onResume END ══");
  }

  @Override
  protected void onPause() {
    log.log("onPause");
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    log.log("onDestroy START (callEnded=" + sCallEnded + ")");

    // ★ P0 (п.19): снимаем OnBackInvokedCallback
    if (Build.VERSION.SDK_INT >= 33 && backInvokedCallback != null) {
      try {
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backInvokedCallback);
        log.log("onDestroy: OnBackInvokedCallback снят");
      } catch (Throwable ignored) {
      }
      backInvokedCallback = null;
    }

    unsubscribeChatReceiver();

    if (!sCallEnded && sWebView != null) {
      try {
        if (sWebView.getParent() instanceof ViewGroup) {
          ((ViewGroup) sWebView.getParent()).removeView(sWebView);
        }
        log.log("onDestroy: WebView detach (звонок продолжается)");
      } catch (Exception e) {
        logError("onDestroy: detach WebView", e);
      }
    } else {
      log.log("onDestroy: движок уже уничтожен (callEnded=" + sCallEnded + ")");
    }

    sUi = null;
    log.log("onDestroy END");
    super.onDestroy();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    log.log("onNewIntent: " + intent.getAction());

    String newCallId = intent.getStringExtra(EXTRA_CALL_ID);
    if (newCallId == null) {
      evaluateJs("onAppForegrounded()");
      return;
    }

    synchronized (ENGINE_LOCK) {
      if (sWebView != null && !sCallEnded && newCallId.equals(sCallId)) {
        log.log("onNewIntent: тот же callId — reuse, onAppForegrounded");
        evaluateJs("onAppForegrounded()");
        return;
      }
      if (sWebView != null && !sCallEnded) {
        log.warn("onNewIntent: другой callId (" + shortId(newCallId) + "), текущий "
            + shortId(sCallId) + " — игнорирую новый");
        Toast.makeText(this, "Уже идёт другой звонок", Toast.LENGTH_LONG).show();
      }
    }
  }

  @Override
  public void onBackPressed() {
    try {
      if (chatOverlay != null && chatOverlay.getVisibility() == View.VISIBLE) {
        log.log("onBackPressed: закрываю оверлей чата");
        hideChat();
        return;
      }
      log.log("onBackPressed → endCallByUser");
      endCallByUser();
    } catch (Exception e) {
      logError("onBackPressed", e);
      endCallByUser();
    }
  }

  @Override
  protected void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
    try {
      if (sCallId != null) {
        outState.putString(STATE_CALL_ID, sCallId);
        log.log("onSaveInstanceState: callId=" + shortId(sCallId));
      }
      outState.putBoolean(STATE_OFFER_HANDLED, sIncomingOfferHandled);
    } catch (Exception ignored) {
    }
  }

  // ============================================================================
  // Разрешения
  // ============================================================================

  private void requestPermissions() {
    try {
      boolean hasCamera = checkSelfPermission(
          Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
      boolean hasMic = checkSelfPermission(
          Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
      log.log("Разрешения: camera=" + hasCamera + ", mic=" + hasMic);

      if (!hasCamera || !hasMic) {
        requestPermissions(
            new String[] { Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO }, 100);
      }
    } catch (Exception e) {
      logError("requestPermissions", e);
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions,
      int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    log.log("onRequestPermissionsResult: requestCode=" + requestCode);

    if (requestCode != 100)
      return;
    try {
      boolean granted = grantResults.length >= 2
          && grantResults[0] == PackageManager.PERMISSION_GRANTED
          && grantResults[1] == PackageManager.PERMISSION_GRANTED;
      log.log("granted=" + granted);

      if (!granted) {
        Toast.makeText(this, "Для звонка нужны камера и микрофон", Toast.LENGTH_LONG).show();
        endCallInternal(true, "declined");
        return;
      }

      startCallServiceIfPermitted();
      onMediaPermissionsReady();
    } catch (Exception e) {
      logError("onRequestPermissionsResult", e);
    }
  }

  // ============================================================================
  // Проверка USE_FULL_SCREEN_INTENT
  // ============================================================================

  private void checkFullScreenIntentPermission() {
    if (Build.VERSION.SDK_INT < 34)
      return;
    try {
      android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
      if (nm == null)
        return;
      if (!nm.canUseFullScreenIntent()) {
        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT);
        intent.setData(Uri.parse("package:" + getPackageName()));
        try {
          startActivity(intent);
        } catch (Exception ignored) {
        }
      }
    } catch (Exception e) {
      logError("checkFullScreenIntentPermission", e);
    }
  }

  // ============================================================================
  // WebAppInterface — static
  // ============================================================================

  static class WebAppInterface {

    @JavascriptInterface
    public void sendSignal(String type, String sdp, String candidate) {
      try {
        int n = sSignalSentCount.incrementAndGet();
        MealseLogger l = slog();
        if (l != null)
          l.logSignal("TX", type, sCallId);

        if (!sIsWifiDirectMode && (sTargetIp == null || sTargetIp.isEmpty())) {
          if (l != null)
            l.warn("sendSignal #" + n + ": targetIp пуст — " + type + " не отправлен");
          return;
        }

        JsonObject obj = new JsonObject();
        obj.addProperty("type", type);
        obj.addProperty("from_device_id", DeviceInfoHelper.getDeviceId(sAppCtx));
        obj.addProperty("from_fingerprint", sLocalFingerprint);
        obj.addProperty("call_id", sCallId);
        obj.addProperty("timestamp", System.currentTimeMillis() / 1000);
        obj.addProperty("mode", sTargetMode != null ? sTargetMode.name() : "AUTO");

        JsonObject payload = new JsonObject();
        if ("offer".equals(type) || "answer".equals(type)) {
          payload.addProperty("sdp", sdp);
        } else if ("candidate".equals(type) && candidate != null) {
          try {
            JsonObject c = new Gson().fromJson(candidate, JsonObject.class);
            if (c.has("candidate"))
              payload.addProperty("candidate", c.get("candidate").getAsString());
            if (c.has("sdpMid") && !c.get("sdpMid").isJsonNull())
              payload.addProperty("sdpMid", c.get("sdpMid").getAsString());
            if (c.has("sdpMLineIndex") && !c.get("sdpMLineIndex").isJsonNull())
              payload.addProperty("sdpMLineIndex", c.get("sdpMLineIndex").getAsInt());
          } catch (Exception e) {
            if (l != null)
              l.logError("sendSignal: candidate parse", e);
          }
        }
        obj.add("payload", payload);
        String body = obj.toString();

        if (sIsWifiDirectMode) {
          if (sWifiDirectManager != null && sWifiDirectManager.isConnected()) {
            sWifiDirectManager.sendSignal(body);
          } else if (l != null) {
            l.warn("sendSignal: WD не подключён — " + type + " не отправлен");
          }
          return;
        }

        Intent svc = new Intent(sAppCtx, ConnectionManager.class);
        svc.setAction(ConnectionManager.ACTION_SEND_SIGNAL);
        svc.putExtra(ConnectionManager.EXTRA_TARGET_IP, sTargetIp);
        svc.putExtra(ConnectionManager.EXTRA_TARGET_PORT, sTargetPort);
        svc.putExtra(ConnectionManager.EXTRA_SIGNAL_TYPE, type);
        svc.putExtra(ConnectionManager.EXTRA_SIGNAL_BODY, body);
        sAppCtx.startForegroundService(svc);
        if (l != null)
          l.log("sendSignal #" + n + ": ConnectionManager, type=" + type);
      } catch (Exception e) {
        MealseLogger l = slog();
        if (l != null)
          l.logError("sendSignal", e);
      }
    }

    @JavascriptInterface
    public void onCallEnded() {
      MealseLogger l = slog();
      if (l != null)
        l.log("JS: onCallEnded");
      endCallInternal(true, "hangup");
    }

    @JavascriptInterface
    public void onCallError(String error) {
      MealseLogger l = slog();
      if (l != null)
        l.error("JS: onCallError — " + error);
      sMainHandler.post(() -> {
        try {
          Toast.makeText(sAppCtx, "Ошибка: " + error, Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {
        }
      });
      endCallInternal(true, "error");
    }

    @JavascriptInterface
    public void onRemoteVideoStalled() {
      sMainHandler.post(() -> {
        try {
          Toast.makeText(sAppCtx, "Видео собеседника недоступно (камера занята/фон)",
              Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
        }
      });
    }

    @JavascriptInterface
    public void onLog(String message) {
      MealseLogger l = slog();
      if (l != null)
        l.log("[JS] " + message);
    }
  }

  // ============================================================================
  // Чат звонка
  // ============================================================================

  private void initChatViews() {
    try {
      btnChatToggle = findViewById(R.id.chat_btn_toggle);
      chatOverlay = findViewById(R.id.chat_overlay);
      chatBtnClose = findViewById(R.id.chat_btn_close);
      chatList = findViewById(R.id.chat_list);
      chatEmpty = findViewById(R.id.chat_empty);
      chatInput = findViewById(R.id.chat_input);
      chatBtnSend = findViewById(R.id.chat_btn_send);
      chatBtnAttach = findViewById(R.id.chat_btn_attach);

      if (btnChatToggle == null || chatOverlay == null || chatList == null || chatInput == null
          || chatBtnSend == null) {
        log.warn("initChatViews: не все View найдены — чат отключён");
        return;
      }

      chatConvRepo = new ConversationRepository(this);
      chatMsgRepo = new MessageRepository(this);
      chatAdapter = new MessageAdapter(this, sLocalFingerprint);
      chatList.setAdapter(chatAdapter);

      btnChatToggle.setOnClickListener(v -> toggleChat());
      if (chatBtnClose != null)
        chatBtnClose.setOnClickListener(v -> hideChat());
      chatBtnSend.setOnClickListener(v -> sendChatMessage());
      if (chatBtnAttach != null) {
        chatBtnAttach.setOnClickListener(v -> showAttachSourceDialog());
        log.log("initChatViews: chat_btn_attach найден — вложения доступны");
      }
      chatInput.setOnEditorActionListener((v, actionId, event) -> {
        if (actionId == EditorInfo.IME_ACTION_SEND) {
          sendChatMessage();
          return true;
        }
        return false;
      });
      log.log("initChatViews: OK");
    } catch (Exception e) {
      logError("initChatViews", e);
    }
  }

  private void showAttachSourceDialog() {
    if (conversationId == null || conversationId.isEmpty()) {
      Toast.makeText(this, "Чат ещё не готов", Toast.LENGTH_SHORT).show();
      return;
    }
    final CharSequence[] items = { "📷 Фото с камеры", "🎥 Видео с камеры",
        "🖼 Галерея (фото/видео)", "📦 Внутренний менеджер" };
    new android.app.AlertDialog.Builder(this).setTitle("Прикрепить")
        .setItems(items, (dialog, which) -> {
          try {
            switch (which) {
              case 0:
                capturePhoto();
                break;
              case 1:
                captureVideo();
                break;
              case 2:
                pickMedia();
                break;
              case 3:
                pickFromAppManager();
                break;
            }
          } catch (Exception e) {
            logError("showAttachSourceDialog", e);
          }
        }).setNegativeButton("Отмена", null).show();
  }

  private void pickFromAppManager() {
    Intent i = new Intent(this, com.my.mealse.filemanager.ui.FileManagerActivity.class);
    i.putExtra(com.my.mealse.filemanager.ui.FileManagerActivity.EXTRA_PICK_MODE, true);
    startActivityForResult(i, REQ_PICK_APP_MANAGER);
  }

  private void pickMedia() {
    Intent m = new Intent(Intent.ACTION_GET_CONTENT);
    m.addCategory(Intent.CATEGORY_OPENABLE);
    m.setType("*/*");
    m.putExtra(Intent.EXTRA_MIME_TYPES, new String[] { "image/*", "video/*" });
    startActivityForResult(m, REQ_PICK_MEDIA);
  }

  private void capturePhoto() {
    if (conversationId == null) {
      toast("Чат не готов");
      return;
    }
    try {
      File dir = AttachmentStorage.getConversationDir(this, conversationId);
      pendingCameraFile = new File(dir, "photo_" + System.currentTimeMillis() + ".jpg");
      pendingCameraUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider",
          pendingCameraFile);
      Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
      i.putExtra(MediaStore.EXTRA_OUTPUT, pendingCameraUri);
      i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
      startActivityForResult(i, REQ_CAPTURE_PHOTO);
    } catch (Exception e) {
      toast("Не удалось открыть камеру: " + e.getMessage());
    }
  }

  private void captureVideo() {
    if (conversationId == null) {
      toast("Чат не готов");
      return;
    }
    try {
      File dir = AttachmentStorage.getConversationDir(this, conversationId);
      pendingCameraFile = new File(dir, "video_" + System.currentTimeMillis() + ".mp4");
      pendingCameraUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider",
          pendingCameraFile);
      Intent i = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
      i.putExtra(MediaStore.EXTRA_OUTPUT, pendingCameraUri);
      i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
      startActivityForResult(i, REQ_CAPTURE_VIDEO);
    } catch (Exception e) {
      toast("Не удалось открыть камеру: " + e.getMessage());
    }
  }

  private void handleCapturedFile() {
    File f = pendingCameraFile;
    pendingCameraFile = null;
    pendingCameraUri = null;
    if (f == null || !f.exists()) {
      toast("Файл не создан");
      return;
    }
    long size = f.length();
    if (size <= 0) {
      f.delete();
      toast("Пустой файл");
      return;
    }
    if (size > MessageCommands.MAX_ATTACHMENT_BYTES) {
      f.delete();
      toast("Файл слишком большой");
      return;
    }
    AttachmentInfo info = new AttachmentInfo(f.getAbsolutePath(), f.getName(),
        AttachmentInfo.guessMimeByName(f.getName()), size);
    sendAttachmentToChat(info, f, false);
  }

  private void initCallConversation() {
    if (chatConvRepo == null)
      return;
    if (sTargetFingerprint == null || sTargetFingerprint.isEmpty()) {
      log.warn("initCallConversation: targetFingerprint пуст");
      return;
    }
    chatExecutor.execute(() -> {
      try {
        Conversation conv = chatConvRepo.getOrCreatePrivate(sTargetFingerprint, null);
        if (conv == null)
          return;
        conversationId = conv.getId();
        log.log("initCallConversation: conv=" + shortId(conversationId) + " (PRIVATE, peer="
            + shortId(sTargetFingerprint) + ")");
        ensureCallParticipants(conversationId);
        chatUi.post(this::reloadChat);
      } catch (Exception e) {
        logError("initCallConversation", e);
      }
    });
  }

  private void ensureCallParticipants(String convId) {
    try {
      ParticipantRepository partRepo = new ParticipantRepository(this);
      if (partRepo.countByConversation(convId) > 0)
        return;

      String selfFp = sLocalFingerprint != null ? sLocalFingerprint : "";
      String selfDeviceId = "";
      try {
        selfDeviceId = DeviceInfoHelper.getDeviceId(this);
      } catch (Exception ignored) {
      }

      partRepo.add(convId, null, selfFp, selfDeviceId, "Я", ParticipantRole.OWNER);

      String peerFp = sTargetFingerprint;
      String peerDeviceId = null;
      String peerNodeKey = null;
      String peerName = "Собеседник";
      try {
        NodeManager nm = new NodeManager(this);
        TrustedNode peerNode = null;
        if (peerFp != null && !peerFp.isEmpty())
          peerNode = nm.findByFingerprint(peerFp);
        if (peerNode == null && targetNodeId != null)
          peerNode = nm.getNodeById(targetNodeId);
        if (peerNode != null) {
          peerNodeKey = peerNode.getKey();
          peerDeviceId = peerNode.getDeviceId();
          if (peerNode.getName() != null)
            peerName = peerNode.getName();
        }
      } catch (Exception ignored) {
      }

      if (peerFp != null && !peerFp.isEmpty()) {
        partRepo.add(convId, peerNodeKey, peerFp, peerDeviceId, peerName, ParticipantRole.MEMBER);
      } else if (peerDeviceId != null && !peerDeviceId.isEmpty()) {
        partRepo.add(convId, peerNodeKey, null, peerDeviceId, peerName, ParticipantRole.MEMBER);
      }
      log.log("ensureCallParticipants: добавлено для " + shortId(convId));
    } catch (Exception e) {
      logError("ensureCallParticipants", e);
    }
  }

  private void subscribeChatReceiver() {
    if (chatReceiverRegistered)
      return;
    try {
      IntentFilter f = new IntentFilter();
      f.addAction(MessengerEvents.ACTION_MESSAGE_NEW);
      f.addAction(MessengerEvents.ACTION_MESSAGE_STATUS_CHANGED);
      f.addAction(MessengerEvents.ACTION_MESSAGE_PROGRESS);
      BroadcastUtils.registerNotExported(this, chatRefreshReceiver, f);
      chatReceiverRegistered = true;
    } catch (Exception e) {
      logError("subscribeChatReceiver", e);
    }
  }

  private void unsubscribeChatReceiver() {
    if (!chatReceiverRegistered)
      return;
    try {
      BroadcastUtils.unregisterSafely(this, chatRefreshReceiver);
      chatReceiverRegistered = false;
    } catch (Exception ignored) {
    }
  }

  private void toggleChat() {
    if (chatOverlay == null)
      return;
    if (chatOverlay.getVisibility() == View.VISIBLE)
      hideChat();
    else
      showChat();
  }

  private void showChat() {
    if (chatOverlay == null)
      return;
    chatOverlay.setVisibility(View.VISIBLE);
    btnChatToggle.setVisibility(View.GONE);
    reloadChat();
    if (conversationId != null) {
      try {
        MessageCommands.markRead(this, conversationId);
      } catch (Exception ignored) {
      }
    }
  }

  private void hideChat() {
    if (chatOverlay == null)
      return;
    chatOverlay.setVisibility(View.GONE);
    if (btnChatToggle != null)
      btnChatToggle.setVisibility(View.VISIBLE);
  }

  private void reloadChat() {
    if (conversationId == null || chatMsgRepo == null || chatAdapter == null)
      return;
    chatExecutor.execute(() -> {
      try {
        List<Message> list = chatMsgRepo.getByConversation(conversationId, 50, 0);
        java.util.Collections.reverse(list);
        chatUi.post(() -> {
          if (chatAdapter == null)
            return;
          chatAdapter.replaceAll(list);
          updateChatEmpty(list.isEmpty());
          scrollChatToBottom();
        });
      } catch (Exception e) {
        logError("reloadChat", e);
      }
    });
  }

  private void updateChatEmpty(boolean empty) {
    if (chatEmpty == null || chatList == null)
      return;
    chatEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
    chatList.setVisibility(empty ? View.GONE : View.VISIBLE);
  }

  private void scrollChatToBottom() {
    if (chatList == null || chatAdapter == null)
      return;
    chatList.post(() -> {
      try {
        int count = chatAdapter.getCount();
        if (count > 0)
          chatList.setSelection(count - 1);
      } catch (Exception ignored) {
      }
    });
  }

  private void sendChatMessage() {
    if (conversationId == null || chatInput == null)
      return;
    String text = chatInput.getText().toString().trim();
    if (text.isEmpty())
      return;
    try {
      String messageId = MessageCommands.sendText(this, conversationId, text, sCallId);
      if (messageId == null)
        return;
      chatInput.setText("");
      chatUi.postDelayed(this::reloadChat, 150);
    } catch (Exception e) {
      logError("sendChatMessage", e);
    }
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (resultCode != RESULT_OK) {
      if (requestCode == REQ_CAPTURE_PHOTO || requestCode == REQ_CAPTURE_VIDEO) {
        // ★ MIUI/HyperOS иногда возвращают RESULT_CANCELED, хотя файл снят.
        // Если pendingCameraFile существует и непуст — обрабатываем как успех,
        // иначе теряем снимок на багованных прошивках.
        if (pendingCameraFile != null && pendingCameraFile.exists()
            && pendingCameraFile.length() > 0) {
          log.log("onActivityResult: RESULT_CANCELED, но файл есть — обрабатываю");
          handleCapturedFile();
        } else {
          if (pendingCameraFile != null && pendingCameraFile.exists())
            pendingCameraFile.delete();
          pendingCameraFile = null;
          pendingCameraUri = null;
        }
      }
      return;
    }

    if (requestCode == REQ_PICK_APP_MANAGER && data != null) {
      ArrayList<String> paths = data.getStringArrayListExtra(
          com.my.mealse.filemanager.ui.FileManagerActivity.EXTRA_PICKED_PATHS);
      if (paths == null || paths.isEmpty()) {
        String p = data
            .getStringExtra(com.my.mealse.filemanager.ui.FileManagerActivity.EXTRA_PICKED_PATH);
        if (p != null) {
          paths = new ArrayList<>();
          paths.add(p);
        }
      }
      if (paths == null || paths.isEmpty()) {
        toast("Ничего не выбрано");
        return;
      }
      handlePickedPaths(paths);
      return;
    }

    switch (requestCode) {
      case REQ_PICK_MEDIA:
        if (data != null && data.getData() != null)
          handlePickedUriForChat(data.getData());
        break;
      case REQ_CAPTURE_PHOTO:
      case REQ_CAPTURE_VIDEO:
        handleCapturedFile();
        break;
    }
  }

  /** «Тихая» отправка без тоста/reload — для батча. */
  // ★ УДАЛЕНО: REQ_PICK_FILE больше не используется — SAF-выбор файла убран,
  // остался только внутренний менеджер (REQ_PICK_APP_MANAGER).

  /** «Тихая» отправка без тоста/reload — для батча. */
  /**
   * «Тихая» отправка без тоста/reload — для батча.
   *
   * ★ Guard: conversationId == null → сразу false (без NPE в sendFile). ★ Логируем через log.warn, а не logError(null) — иначе логгер может упасть на t.getMessage()/printStackTrace() при null Throwable. ★ deleteSrc + cache-guard сохранены для одиночных временных файлов (галерея/камера). Для zip, собранного через zipDirectory, удаление делается вызывающим кодом БЕЗУСЛОВНО (см. handlePickedPaths) — потому что sendFile копирует синхронно, а расположение zip не гарантировано внутри cache.
   */
  private boolean sendAttachmentCore(AttachmentInfo info, File src, boolean deleteSrc) {
    if (conversationId == null) {
      log.warn("sendAttachmentCore: conversationId == null — пропуск");
      return false;
    }
    try {
      String id = MessageCommands.sendFile(this, conversationId, info, sCallId);
      if (id == null)
        return false;
      if (deleteSrc && src != null && src.exists()
          && src.getAbsolutePath().startsWith(getCacheDir().getAbsolutePath()))
        src.delete();
      return true;
    } catch (Exception e) {
      logError("sendAttachmentCore", e);
      return false;
    }
  }

  private void handlePickedPaths(List<String> paths) {
    if (conversationId == null) {
      toast("Чат не готов");
      return;
    }
    chatExecutor.execute(() -> {
      int sent = 0, failed = 0;
      for (String p : paths) {
        File f = new File(p);
        if (!f.exists()) {
          failed++;
          continue;
        }
        try {
          if (f.isDirectory()) {
            File zip = AttachmentStorage.zipDirectory(this, f);
            if (zip == null || zip.length() > MessageCommands.MAX_ATTACHMENT_BYTES) {
              if (zip != null)
                zip.delete();
              failed++;
              continue;
            }
            AttachmentInfo info = new AttachmentInfo(zip.getAbsolutePath(), f.getName() + ".zip",
                "application/zip", zip.length());
            boolean ok = sendAttachmentCore(info, zip, true);
            if (ok)
              sent++;
            else
              failed++;
            // ★ zip — наш временный артефакт. sendFile копирует СИНХРОННО,
            // поэтому удаляем безусловно: cache-guard внутри core мог не
            // сработать, если zipDirectory кладёт zip вне getCacheDir().
            if (zip.exists())
              zip.delete();
          } else {
            if (f.length() > MessageCommands.MAX_ATTACHMENT_BYTES) {
              failed++;
              continue;
            }
            AttachmentInfo info = new AttachmentInfo(f.getAbsolutePath(), f.getName(),
                AttachmentInfo.guessMimeByName(f.getName()), f.length());
            if (sendAttachmentCore(info, f, false))
              sent++;
            else
              failed++;
          }
        } catch (Exception e) {
          logError("handlePickedPaths", e);
          failed++;
        }
      }
      final int fs = sent, ff = failed;
      chatUi.post(() -> {
        toast("📎 Отправлено: " + fs + (ff > 0 ? ", ошибок: " + ff : ""));
        reloadChat();
      });
    });
  }

  private void handlePickedUriForChat(Uri uri) {
    if (conversationId == null) {
      toast("Чат не готов");
      return;
    }
    chatExecutor.execute(() -> {
      try {
        String name = queryDisplayName(uri);
        String mime = getContentResolver().getType(uri);
        File tmp = new File(getCacheDir(),
            "chat_attach_" + System.currentTimeMillis() + "_" + sanitize(name));
        try (InputStream in = getContentResolver().openInputStream(uri);
            OutputStream out = new FileOutputStream(tmp)) {
          if (in == null)
            throw new IOException("null input");
          byte[] buf = new byte[64 * 1024];
          int r;
          while ((r = in.read(buf)) != -1)
            out.write(buf, 0, r);
        }
        long size = tmp.length();
        if (size <= 0) {
          tmp.delete();
          chatUi.post(() -> toast("Пустой файл"));
          return;
        }
        if (size > MessageCommands.MAX_ATTACHMENT_BYTES) {
          tmp.delete();
          chatUi.post(() -> toast("Файл слишком большой"));
          return;
        }
        AttachmentInfo info = new AttachmentInfo(tmp.getAbsolutePath(), name, mime, size);
        sendAttachmentToChat(info, tmp, true);
      } catch (Exception e) {
        logError("handlePickedUriForChat", e);
        chatUi.post(() -> toast("Ошибка: " + e.getMessage()));
      }
    });
  }

  private void sendAttachmentToChat(AttachmentInfo info, File srcFile, boolean deleteSrcAfter) {
    if (conversationId == null) {
      toast("Чат не готов");
      return;
    }
    try {
      String messageId = MessageCommands.sendFile(this, conversationId, info, sCallId);
      if (messageId == null) {
        chatUi.post(() -> toast("Не удалось отправить"));
        if (deleteSrcAfter && srcFile != null && srcFile.exists())
          srcFile.delete();
        return;
      }
      log.log("sendAttachmentToChat: msg=" + shortId(messageId) + ", file=" + info.getFileName());
      chatUi.post(() -> {
        toast("📎 Отправлено: " + info.getFileName());
        chatUi.postDelayed(this::reloadChat, 150);
      });
      if (deleteSrcAfter && srcFile != null && srcFile.exists()
          && srcFile.getAbsolutePath().startsWith(getCacheDir().getAbsolutePath())) {
        srcFile.delete();
      }
    } catch (Exception e) {
      logError("sendAttachmentToChat", e);
      chatUi.post(() -> toast("Ошибка: " + e.getMessage()));
    }
  }

  // ============================================================================
  // Хелперы
  // ============================================================================

  private void logError(String message, Throwable e) {
    if (log != null)
      log.logError(message, e);
    else
      Log.e(TAG, message, e);
  }

  private static String escapeForJsStatic(String json) {
    if (json == null)
      return "";
    return json.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r");
  }

  private static String shortIdStatic(String id) {
    if (id == null)
      return "?";
    return id.length() > 8 ? id.substring(0, 8) : id;
  }

  private String shortId(String id) {
    return shortIdStatic(id);
  }

  private String truncate(String s, int max) {
    if (s == null)
      return "null";
    return s.length() > max ? s.substring(0, max) + "..." : s;
  }

  private void toast(String msg) {
    try {
      Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    } catch (Throwable ignored) {
    }
  }

  private String queryDisplayName(Uri uri) {
    String fallback = "file_" + System.currentTimeMillis();
    try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
      if (c != null && c.moveToFirst()) {
        int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
        if (idx >= 0) {
          String name = c.getString(idx);
          if (name != null && !TextUtils.isEmpty(name))
            return name;
        }
      }
    } catch (Throwable ignored) {
    }
    return fallback;
  }

  private static String sanitize(String s) {
    if (s == null || s.isEmpty())
      return "file";
    return s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
  }

  private static class EngineWebViewClient extends WebViewClient {

    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
      super.onPageStarted(view, url, favicon);
      MealseLogger l = slog();
      if (l != null)
        l.log("🌐 onPageStarted: " + url);
    }

    @Override
    public void onPageFinished(WebView view, String url) {
      super.onPageFinished(view, url);
      MealseLogger l = slog();
      if (l != null)
        l.log("🌐 onPageFinished: " + url);
      sWebViewLoaded = true;

      if (sEngineInitialized) {
        flushPendingSignals();
        return;
      }
      sEngineInitialized = true;

      try {
        IceConfigManager icm = new IceConfigManager(sAppCtx);
        String iceJson = icm.buildIceConfigJsonForMode(
            sTargetMode != null ? sTargetMode.name() : "AUTO", sTargetGatewayIp,
            sTargetGatewayPort);
        evaluateJs("configureIce('" + escapeForJsStatic(iceJson) + "')");
        if (l != null)
          l.log("→ JS: configureIce(" + iceJson + ")");

        String modeName = sTargetMode != null ? sTargetMode.name() : "AUTO";
        evaluateJs("configureIceMode('" + modeName + "', '"
            + (sTargetGatewayIp != null ? sTargetGatewayIp : "") + "', " + sTargetGatewayPort
            + ")");
        if (l != null)
          l.log("→ JS: configureIceMode(" + modeName + ")");

        onMediaPermissionsReady();

        sMainHandler.postDelayed(() -> flushPendingSignals(), 400);
      } catch (Exception e) {
        MealseLogger lg = slog();
        if (lg != null)
          lg.logError("Ошибка в onPageFinished", e);
      }
    }

    @Override
    public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
      super.onReceivedError(view, request, error);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        MealseLogger l = slog();
        if (l != null)
          l.error("🌐 onReceivedError: " + error.getDescription() + " для " + request.getUrl());
      }
    }
  }

  private static class EngineWebChromeClient extends WebChromeClient {

    @Override
    public void onPermissionRequest(final PermissionRequest request) {
      sMainHandler.post(() -> {
        MealseLogger l = slog();
        try {
          String origin = request.getOrigin() != null ? request.getOrigin().toString() : "";
          if (l != null)
            l.log("onPermissionRequest: origin=" + origin + ", resources="
                + Arrays.toString(request.getResources()));

          boolean isOurAsset = origin.isEmpty() || origin.equals("null")
              || origin.equals("file:///") || origin.startsWith("file:///android_asset")
              || origin.startsWith("file://");
          if (!isOurAsset) {
            if (l != null)
              l.warn("onPermissionRequest: origin=" + origin + " — deny (не наш asset)");
            request.deny();
            return;
          }

          List<String> allowed = new ArrayList<>();
          for (String r : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)
                || PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) {
              allowed.add(r);
            }
          }
          if (allowed.isEmpty()) {
            if (l != null)
              l.warn("onPermissionRequest: нет video/audio capture — deny");
            request.deny();
            return;
          }

          request.grant(allowed.toArray(new String[0]));
          if (l != null)
            l.log("onPermissionRequest: granted " + allowed + " (origin=" + origin + ")");
        } catch (Exception e) {
          if (l != null)
            l.logError("onPermissionRequest", e);
          try {
            request.deny();
          } catch (Exception ignored) {
          }
        }
      });
    }

    @Override
    public boolean onConsoleMessage(android.webkit.ConsoleMessage cm) {
      MealseLogger l = slog();
      if (l != null)
        l.log("[console] " + cm.message() + " (" + cm.sourceId() + ":" + cm.lineNumber() + ")");
      return true;
    }

    @Override
    public boolean onJsAlert(WebView view, String url, String message,
        final android.webkit.JsResult result) {
      sMainHandler.post(() -> {
        CallActivity ui = sUi;
        if (ui == null || ui.isFinishing() || ui.isDestroyed()) {
          result.cancel();
          return;
        }
        try {
          new android.app.AlertDialog.Builder(ui).setMessage(message)
              .setPositiveButton(android.R.string.ok, (d, w) -> result.confirm())
              .setCancelable(false).show();
        } catch (Exception e) {
          result.cancel();
        }
      });
      return true;
    }

    @Override
    public boolean onJsConfirm(WebView view, String url, String message,
        final android.webkit.JsResult result) {
      sMainHandler.post(() -> {
        CallActivity ui = sUi;
        if (ui == null || ui.isFinishing() || ui.isDestroyed()) {
          result.cancel();
          return;
        }
        try {
          new android.app.AlertDialog.Builder(ui).setMessage(message)
              .setPositiveButton(android.R.string.ok, (d, w) -> result.confirm())
              .setNegativeButton(android.R.string.cancel, (d, w) -> result.cancel())
              .setCancelable(false).show();
        } catch (Exception e) {
          result.cancel();
        }
      });
      return true;
    }
  }
}