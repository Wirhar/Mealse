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
import android.view.inputmethod.EditorInfo;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.my.mealse.messenger.MessengerEvents;
import com.my.mealse.messenger.model.AttachmentInfo;
import com.my.mealse.messenger.model.Conversation;
import com.my.mealse.messenger.model.Message;
import com.my.mealse.messenger.repo.ConversationRepository;
import com.my.mealse.messenger.repo.MessageRepository;
import com.my.mealse.messenger.storage.AttachmentStorage;
import com.my.mealse.messenger.transport.MessageCommands;
import com.my.mealse.messenger.ui.MessageAdapter;
import com.my.mealse.model.TrustedNode;
import com.my.mealse.service.ConnectionManager;
import com.my.mealse.util.BroadcastUtils;
import com.my.mealse.util.CallStateManager;
import com.my.mealse.util.DeviceInfoHelper;
import com.my.mealse.util.IceConfigManager;
import com.my.mealse.util.MealseLogger;
import com.my.mealse.util.NodeManager;
import com.my.mealse.util.SecureAuthManager;
import com.my.mealse.util.WifiDirectManager;
import com.my.mealse.util.WifiDirectManager.Peer;

import com.my.mealse.messenger.model.ParticipantRole;
import com.my.mealse.messenger.repo.ParticipantRepository;

public class CallActivity extends Activity {

  private static final String TAG = "CallActivity";

  private static final String EXTRA_CALL_ID = "call_id";
  private static final String STATE_CALL_ID = "state_call_id";
  private static final String STATE_OFFER_HANDLED = "state_offer_handled";

  // ★ НОВОЕ: request codes для вложений в чате звонка
  private static final int REQ_PICK_APP_MANAGER = 5010;
  private static final int REQ_PICK_FILE = 5001;
  private static final int REQ_PICK_MEDIA = 5002;

  // WebView & UI
  private WebView webView;

  // Оверлей чата (Шаг 14.2)
  private ImageButton btnChatToggle;
  private View chatOverlay;
  private ImageButton chatBtnClose;
  private ListView chatList;
  private TextView chatEmpty;
  private EditText chatInput;
  private ImageButton chatBtnSend;
  private ImageButton chatBtnAttach; // ★ НОВОЕ
  private MessageAdapter chatAdapter;

  private String conversationId;
  private ConversationRepository chatConvRepo;
  private MessageRepository chatMsgRepo;

  // ★ НОВОЕ (правка B): флаг «звонок завершён, bye уже отправлен/получен».
  // Нужен, чтобы onDestroy не слал повторный bye:
  // - после handleBye (bye от собеседника уже пришёл — свой слать не надо);
  // - после endCallByUser (bye уже отправлен в единой точке завершения).
  // volatile — читается из onDestroy и из UI-потока.
  private volatile boolean byeAlreadySent = false;

  // ★ НОВОЕ (правка B): флаг «Activity уничтожается по инициативе пользователя».
  // Выставляется ТОЛЬКО в endCallByUser() (назад / кнопка «завершить») и
  // в handleBye() (собеседник завершил — нам тоже нечего слать, но звонок
  // уже завершён).
  // В onDestroy bye шлём только если endingByUser == true && !byeAlreadySent.
  // Если Activity вытеснена системой (isFinishing=true, но пользователь
  // не завершал) — endingByUser=false → bye НЕ шлём → звонок продолжается
  // на собеседнике, а мы, вернувшись, либо продолжим, либо завершим явно.
  private volatile boolean endingByUser = false;

  // ★ НОВОЕ (правка B): флаг «WebView уничтожен».
  // В логе: onDestroy → loadUrl("about:blank") → destroy(), но
  // evaluateJs из onResume/onPageFinished продолжает лететь и даёт
  // Uncaught ReferenceError: configureIce is not defined (about:blank:1).
  // evaluateJs должен проверять webViewDestroyed и не слать JS в мёртвый WebView.
  private volatile boolean webViewDestroyed = false;

  private final ExecutorService chatExecutor = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "CallChat");
    t.setDaemon(true);
    return t;
  });
  private final Handler chatUi = new Handler(Looper.getMainLooper());
  private boolean chatReceiverRegistered = false;

  /**
   * Приёмник событий мессенджера.
   *
   * ★ Улучшение №3: ACTION_MESSAGE_PROGRESS → adapter.notifyDataSetChanged(). MESSAGE_NEW / STATUS_CHANGED → reloadChat().
   */
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

      // ★ Улучшение №3: прогресс — дёшево, notifyDataSetChanged
      if (MessengerEvents.ACTION_MESSAGE_PROGRESS.equals(action)) {
        if (chatAdapter != null) {
          chatUi.post(() -> {
            if (chatAdapter != null)
              chatAdapter.notifyDataSetChanged();
          });
        }
        return;
      }

      // MESSAGE_NEW / MESSAGE_STATUS_CHANGED — полная перезагрузка
      if (MessengerEvents.ACTION_MESSAGE_NEW.equals(action)
          || MessengerEvents.ACTION_MESSAGE_STATUS_CHANGED.equals(action)) {
        reloadChat();
      }
    }
  };

  // Менеджеры
  private MealseLogger log;
  private SecureAuthManager secureAuthManager;
  private CallStateManager callStateManager;
  private IceConfigManager iceConfigManager;
  private NodeManager nodeManager;

  // ★ Wi-Fi Direct
  private WifiDirectManager wifiDirectManager;
  private WifiDirectManager.Listener wifiDirectListener;
  private boolean isWifiDirectMode = false;

  // Данные звонка
  private String targetNodeId;
  private String targetIp;
  private int targetPort;

  private String callId;
  private String localFingerprint;

  private String targetFingerprint;

  private TrustedNode.Mode targetMode = TrustedNode.Mode.AUTO;
  private String targetGatewayIp;
  private int targetGatewayPort = 8083;

  // Состояние
  private boolean isIncoming = false;
  private String incomingOfferJson;
  private boolean incomingOfferHandled = false;
  private boolean receiverRegistered = false;
  private boolean webViewLoaded = false;

  private final AtomicInteger signalSentCount = new AtomicInteger(0);

  private final List<PendingSignal> pendingSignals = new ArrayList<>();

  private static class PendingSignal {
    final String type;
    final String json;

    PendingSignal(String type, String json) {
      this.type = type;
      this.json = json;
    }
  }

  // ================= Фабричный метод =================

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

  // ================= BroadcastReceiver (HTTP-сигналинг) =================

  private final BroadcastReceiver signalReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      try {
        String action = intent.getAction();
        log.log("📩 Broadcast получен: action=" + action);

        String json = intent.getStringExtra("signal_json");
        if (json == null)
          json = intent.getStringExtra("raw");
        if (json == null) {
          log.warn("Пустой signal_json в broadcast");
          return;
        }

        log.log("📩 Signal JSON: " + truncate(json, 300));

        handleIncomingSignal(json);

      } catch (Exception e) {
        logError("Ошибка в signalReceiver", e);
      }
    }
  };

  private void handleIncomingSignal(String json) {
    JsonObject obj;
    try {
      obj = new Gson().fromJson(json, JsonObject.class);
    } catch (Exception e) {
      log.logError("Ошибка парсинга JSON сигнала", e);
      return;
    }
    if (obj == null || !obj.has("type")) {
      log.warn("Сигнал без type");
      return;
    }

    if (obj.has("call_id") && !obj.get("call_id").isJsonNull() && callId != null) {
      String incomingCallId = obj.get("call_id").getAsString();
      if (!callId.equals(incomingCallId)) {
        log.log("Игнорируем сигнал для другого call_id: " + shortId(incomingCallId) + " (наш: "
            + shortId(callId) + ")");
        return;
      }
    }

    String type = obj.get("type").getAsString();
    log.logSignal("RX", type, callId);

    if (!webViewLoaded) {
      log.log("WebView не готов → буферизую " + type);
      synchronized (pendingSignals) {
        pendingSignals.add(new PendingSignal(type, json));
      }
      return;
    }

    dispatchSignal(obj, type, json);
  }

  private void dispatchSignal(JsonObject obj, String type, String json) {
    String escaped = escapeForJs(json);

    switch (type) {
      case "offer":
        log.log("Обработка offer");
        if (isIncoming && !incomingOfferHandled) {
          incomingOfferJson = json;
          sendOfferToWebView(json);
          incomingOfferHandled = true;
        } else {
          evaluateJs("onRemoteOffer('" + escaped + "')");
        }
        break;
      case "answer":
        log.log("Обработка answer");
        evaluateJs("onRemoteAnswer('" + escaped + "')");
        break;
      case "candidate":
        log.log("Обработка candidate");
        evaluateJs("onRemoteCandidate('" + escaped + "')");
        break;
      case "bye":
        log.log("Обработка bye");
        handleBye(obj);
        break;
      // ★ ИЗМЕНЕНО: message/file_chunk идут через MessageReceiver,
      // CallActivity их не обрабатывает — просто не шумим в логе
      case "message":
      case "file_chunk":
        log.log("Сигнал " + type + " — обрабатывается MessageReceiver, пропуск");
        break;
      default:
        log.warn("Неизвестный тип сигнала: " + type);
    }
  }

  private void flushPendingSignals() {
    List<PendingSignal> copy;
    synchronized (pendingSignals) {
      if (pendingSignals.isEmpty())
        return;
      copy = new ArrayList<>(pendingSignals);
      pendingSignals.clear();
    }
    log.log("Flush буфера сигналов: " + copy.size());
    for (PendingSignal s : copy) {
      try {
        JsonObject obj = new Gson().fromJson(s.json, JsonObject.class);
        dispatchSignal(obj, s.type, s.json);
      } catch (Exception e) {
        logError("Ошибка flush сигнала " + s.type, e);
      }
    }
  }

  /**
   * ★ ИЗМЕНЕНО (правка B.2): обработка bye от собеседника.
   *
   * Что нового: - byeAlreadySent = true — свой bye уже не нужен (звонок завершён собеседником, дублирующий bye — лишний трафик и лишний лог); - endingByUser = true — фиксируем, что завершение инициировано (собеседником), чтобы onDestroy не пытался слать bye «от себя».
   *
   * Почему: раньше handleBye вызывал finish(), а onDestroy безусловно слал bye при isFinishing=true → собеседник получал bye дважды (в логе: 16:50:24.734 получен bye → 16:50:25.356 отправка своего bye).
   */
  private void handleBye(JsonObject obj) {
    String reason = "hangup";
    try {
      if (obj.has("payload") && obj.get("payload").isJsonObject()) {
        JsonObject payload = obj.getAsJsonObject("payload");
        if (payload.has("reason") && !payload.get("reason").isJsonNull()) {
          reason = payload.get("reason").getAsString();
        }
      }
    } catch (Exception e) {
      log.logError("Ошибка разбора reason", e);
    }

    final String finalReason = reason;
    log.log("Звонок завершён. Причина: " + finalReason);

    // ★ НОВОЕ: звонок завершён собеседником — свой bye не шлём.
    byeAlreadySent = true;
    endingByUser = true;

    runOnUiThread(() -> {
      try {
        String msg;
        switch (finalReason) {
          case "busy":
            msg = "Абонент занят";
            break;
          case "declined":
            msg = "Вызов отклонён";
            break;
          case "hangup":
          default:
            msg = "Звонок завершён";
            break;
        }
        Toast.makeText(CallActivity.this, msg, Toast.LENGTH_SHORT).show();
        finish();
      } catch (Exception e) {
        logError("Ошибка обработки bye в UI", e);
      }
    });
  }

  /**
   * ★ ИЗМЕНЕНО (правка B.5): не отправляем JS, если WebView уже уничтожен.
   *
   * В логе: onDestroy → loadUrl("about:blank") → destroy(), но evaluateJavascript("configureIce(...)") всё равно летел → Uncaught ReferenceError: configureIce is not defined (about:blank:1).
   *
   * Теперь проверяем webViewDestroyed и webView == null до runOnUiThread, и ещё раз внутри runOnUiThread (гонка с onDestroy).
   */
  private void evaluateJs(final String js) {
    if (webViewDestroyed || webView == null) {
      log.warn("evaluateJs: webView уничтожен/нулевой — пропуск");
      return;
    }
    runOnUiThread(() -> {
      try {
        if (webView != null && !webViewDestroyed) {
          log.log("→ JS: " + truncate(js, 150));
          webView.evaluateJavascript(js, result -> {
            log.log("← JS result: " + result);
          });
        } else {
          log.warn("evaluateJs: webView == null или destroyed (в runOnUiThread)");
        }
      } catch (Exception e) {
        logError("Ошибка evaluateJavascript", e);
      }
    });
  }

  private void sendOfferToWebView(String offerJson) {
    if (webView == null) {
      log.warn("sendOfferToWebView: webView == null");
      return;
    }
    String escaped = escapeForJs(offerJson);
    runOnUiThread(() -> {
      try {
        log.log("→ JS: onRemoteOffer (входящий)");
        webView.evaluateJavascript("onRemoteOffer('" + escaped + "')", null);
      } catch (Exception e) {
        logError("Ошибка отправки offer в JS", e);
      }
    });
  }

  // ================= Lifecycle =================

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    log = MealseLogger.get(this, TAG);
    log.log("══ onCreate START ══");

    try {
      log.log("Шаг 1: setContentView");
      setContentView(R.layout.activity_call);
      log.log("Шаг 1: OK");

      log.log("Шаг 2: findViewById");
      webView = findViewById(R.id.webview_call);

      if (webView == null) {
        throw new IllegalStateException("webview_call не найден в activity_call.xml");
      }
      log.log("Шаг 2: OK — webView=" + webView);

      log.log("Шаг 3: чтение Intent");
      Intent intent = getIntent();

      Bundle extras = intent.getExtras();
      if (extras != null) {
        StringBuilder sb = new StringBuilder();
        for (String key : extras.keySet()) {
          Object val = extras.get(key);
          String valStr = String.valueOf(val);
          if (valStr.length() > 200) {
            valStr = valStr.substring(0, 200) + "…(" + valStr.length() + " симв.)";
          }
          sb.append(key).append("=").append(valStr).append(", ");
        }
        log.log("Intent extras: " + sb);
      } else {
        log.log("Intent extras: null");
      }

      targetNodeId = intent.getStringExtra("target_node_id");
      targetIp = intent.getStringExtra("target_ip");
      targetPort = intent.getIntExtra("target_port", 8081);
      isIncoming = intent.getBooleanExtra("is_incoming", false);
      incomingOfferJson = intent.getStringExtra("incoming_offer_json");
      targetFingerprint = intent.getStringExtra("target_fingerprint");

      // ★ ИСПРАВЛЕНО (шаг 17.1): incoming_offer_json должен содержать
      // именно type="offer". Иначе (candidate/answer/bye — приходят
      // из IncomingCallActivity, если туда попал не-offer) —
      // игнорируем, CallActivity получит настоящий offer через
      // signalReceiver → evaluateJavascript("onRemoteOffer(...)").
      if (isIncoming && incomingOfferJson != null && !incomingOfferJson.isEmpty()) {
        try {
          com.google.gson.JsonObject check = com.google.gson.JsonParser
              .parseString(incomingOfferJson).getAsJsonObject();
          String t = check.has("type") && !check.get("type").isJsonNull()
              ? check.get("type").getAsString()
              : null;
          if (!"offer".equals(t)) {
            log.warn("incoming_offer_json type=" + t
                + " != offer — игнорирую, жду настоящий offer через signalReceiver");
            incomingOfferJson = null;
          }
        } catch (Exception e) {
          log.warn("incoming_offer_json не JSON (" + e.getMessage() + ") — игнорирую");
          incomingOfferJson = null;
        }
      }

      String modeStr = intent.getStringExtra("target_mode");
      targetMode = TrustedNode.Mode.fromString(modeStr);
      targetGatewayIp = intent.getStringExtra("target_gateway_ip");
      targetGatewayPort = intent.getIntExtra("target_gateway_port", 8083);

      log.log("target_node_id=" + targetNodeId);
      log.log("target_ip=" + targetIp);
      log.log("target_port=" + targetPort);
      log.log("is_incoming=" + isIncoming);
      log.log("target_fingerprint=" + targetFingerprint);
      log.log("target_mode=" + targetMode);
      log.log("target_gateway_ip=" + targetGatewayIp);
      log.log("target_gateway_port=" + targetGatewayPort);
      log.log("incoming_offer_json="
          + (incomingOfferJson != null ? incomingOfferJson.length() + " симв." : "null"));

      if (targetMode == TrustedNode.Mode.WIFI_DIRECT) {
        log.log("CallActivity: Wi-Fi Direct mode");
        isWifiDirectMode = true;

        MealseApp app = (MealseApp) getApplication();
        wifiDirectManager = app.getWifiDirectManager();
        if (wifiDirectManager == null || !wifiDirectManager.isWifiDirectSupported()) {
          log.error("Wi-Fi Direct недоступен — завершаем");
          Toast.makeText(this, "Wi-Fi Direct недоступен на этом устройстве", Toast.LENGTH_LONG)
              .show();
          finish();
          return;
        }

        if (!wifiDirectManager.isConnected()) {
          log.error("Wi-Fi Direct не подключён — пробуем HTTP-fallback");

          if (targetFingerprint != null && !targetFingerprint.isEmpty()) {
            try {
              NodeManager nm = new NodeManager(this);
              // ★ Ищем ВСЕ узлы с этим fingerprint, выбираем первый HTTP
              java.util.List<TrustedNode> candidates = nm.findAllByFingerprint(targetFingerprint);
              TrustedNode httpNode = null;
              for (TrustedNode n : candidates) {
                if (n.getMode() != TrustedNode.Mode.WIFI_DIRECT && n.getIpAddress() != null
                    && !n.getIpAddress().isEmpty()) {
                  httpNode = n;
                  break;
                }
              }
              if (httpNode == null && targetNodeId != null) {
                TrustedNode byId = nm.getNodeById(targetNodeId);
                if (byId != null && byId.getMode() != TrustedNode.Mode.WIFI_DIRECT
                    && byId.getIpAddress() != null && !byId.getIpAddress().isEmpty()) {
                  httpNode = byId;
                }
              }
              if (httpNode != null) {
                log.log("HTTP-fallback: переключаюсь на " + httpNode.getKey() + " (mode="
                    + httpNode.getMode() + ")");
                targetIp = httpNode.getIpAddress();
                targetPort = httpNode.getPort();
                targetMode = httpNode.getMode() != null ? httpNode.getMode()
                    : TrustedNode.Mode.AUTO;
                isWifiDirectMode = false;
              } else {
                log.warn("HTTP-fallback: HTTP-узел не найден (candidates=" + candidates.size()
                    + ", targetNodeId=" + targetNodeId + ")");
              }
            } catch (Exception e) {
              log.warn("HTTP-fallback: " + e.getMessage());
            }
          } else {
            log.warn("HTTP-fallback: targetFingerprint пуст — некуда переключаться");
          }

          if (isWifiDirectMode) {
            log.error("Wi-Fi Direct не подключён, HTTP-fallback не сработал — завершаем");
            Toast.makeText(this, "Wi-Fi Direct не подключён. Подключитесь в режиме Wi-Fi Direct.",
                Toast.LENGTH_LONG).show();
            finish();
            return;
          } else {
            log.log("Wi-Fi Direct → HTTP-fallback OK: targetIp=" + targetIp + ":" + targetPort
                + ", targetMode=" + targetMode);
          }
        } else {
          log.log("Wi-Fi Direct: connected=true, isGO=" + wifiDirectManager.isGroupOwner()
              + ", remotePeer=" + wifiDirectManager.getRemotePeer());
        }
      }

      if (targetIp == null || targetIp.isEmpty()) {
        log.error("target_ip пустой — завершаем");
        Toast.makeText(this, "Не указан IP узла", Toast.LENGTH_LONG).show();
        finish();
        return;
      }

      String selfIp = DeviceInfoHelper.getSelfIp(this);
      String selfFp = null;
      try {
        SecureAuthManager tmpAuth = new SecureAuthManager(this);
        selfFp = tmpAuth.getDeviceFingerprint();
      } catch (Exception e) {
        log.warn("Не удалось получить self fingerprint: " + e.getMessage());
      }
      log.log("selfIp=" + selfIp + ", selfFp=" + selfFp);

      if (selfFp != null && targetFingerprint != null && selfFp.equals(targetFingerprint)) {
        log.error("КРИТИЧНО: target_fingerprint == selfFp (" + selfFp + ") — блокирую");
        Toast.makeText(this, "Нельзя позвонить самому себе", Toast.LENGTH_LONG).show();
        finish();
        return;
      }

      if (!isWifiDirectMode) {
        if (isIncoming && selfIp != null && selfIp.equals(targetIp)) {
          log.error("КРИТИЧНО: is_incoming=true, но target_ip == selfIp (" + targetIp + ")");

          if (nodeManager == null)
            nodeManager = new NodeManager(this);
          if (targetNodeId != null) {
            TrustedNode node = nodeManager.getNodeById(targetNodeId);
            if (node != null && node.getIpAddress() != null
                && !node.getIpAddress().equals(selfIp)) {
              log.log("Восстановили targetIp из NodeManager: " + node.getIpAddress() + ":"
                  + node.getPort());
              targetIp = node.getIpAddress();
              targetPort = node.getPort();
            } else {
              log.error("Не удалось восстановить targetIp — завершаем");
              Toast.makeText(this, "Ошибка: не указан IP вызывающего", Toast.LENGTH_LONG).show();
              finish();
              return;
            }
          } else {
            log.error("target_node_id=null, targetIp == selfIp — завершаем");
            Toast.makeText(this, "Ошибка: не указан IP вызывающего", Toast.LENGTH_LONG).show();
            finish();
            return;
          }
        }

        if (!isIncoming && selfIp != null && selfIp.equals(targetIp)) {
          log.error("КРИТИЧНО: is_incoming=false, но target_ip == selfIp (" + targetIp + ")");
          Toast.makeText(this, "Нельзя позвонить самому себе", Toast.LENGTH_LONG).show();
          finish();
          return;
        }
      }

      log.log("Шаг 4: call_id");

      if (savedInstanceState != null) {
        callId = savedInstanceState.getString(STATE_CALL_ID);
        incomingOfferHandled = savedInstanceState.getBoolean(STATE_OFFER_HANDLED, false);
        log.log("call_id из savedInstanceState: " + callId);
        log.log("incomingOfferHandled: " + incomingOfferHandled);
      }
      if (callId == null) {
        callId = intent.getStringExtra(EXTRA_CALL_ID);
        log.log("call_id из intent: " + callId);
      }
      if (callId == null) {
        callId = UUID.randomUUID().toString();
        log.log("call_id сгенерирован: " + callId);
      }
      log.log("Итоговый call_id=" + callId);

      if (isIncoming && (callId == null || callId.isEmpty())) {
        log.error("КРИТИЧНО: is_incoming=true, но call_id пустой — "
            + "сигналы answer/candidate будут отфильтрованы!");
      }

      log.log("Шаг 5: SecureAuthManager");
      secureAuthManager = new SecureAuthManager(this);
      localFingerprint = secureAuthManager.getDeviceFingerprint();
      if (localFingerprint == null)
        localFingerprint = "";
      log.log("localFingerprint=" + localFingerprint);

      log.log("Шаг 6: IceConfigManager");
      iceConfigManager = new IceConfigManager(this);
      String iceJson = iceConfigManager.buildIceConfigJson();
      log.log("ICE config: " + iceJson);

      log.log("Шаг 7: CallStateManager");
      callStateManager = new CallStateManager(this);
      if (callStateManager.isCallActive()) {
        String activeCallId = callStateManager.getActiveCallId();
        log.log("Активный звонок обнаружен: " + shortId(activeCallId));
        if (activeCallId != null && !activeCallId.equals(callId)) {
          log.warn("Уже идёт другой звонок — завершаем");
          Toast.makeText(this, "Уже идёт другой звонок", Toast.LENGTH_LONG).show();
          finish();
          return;
        }
      }
      callStateManager.setCallActive(callId, targetNodeId);
      log.log("CallStateManager: callActive=true, callId=" + shortId(callId) + ", targetNodeId="
          + targetNodeId);

      log.log("Шаг 8: проверка USE_FULL_SCREEN_INTENT");
      checkFullScreenIntentPermission();

      log.log("Шаг 9: настройка WebView");
      setupWebView();

      if (isWifiDirectMode) {
        log.log("Шаг 9.5: подписка на Wi-Fi Direct listener");
        subscribeWifiDirectListener();
      } else {
        log.log("Шаг 9.5: регистрация signalReceiver (HTTP)");
        registerSignalReceiver();
      }

      log.log("Шаг 10: запрос разрешений");
      requestPermissions();

      try {
        if (!isWifiDirectMode && targetIp != null && !targetIp.isEmpty()) {
          com.my.mealse.util.NodeManager nm = new com.my.mealse.util.NodeManager(this);
          com.my.mealse.model.TrustedNode targetNode = nm.findByIpPort(targetIp, targetPort);
          if (targetNode != null) {
            com.my.mealse.util.NodeEnricher.enrich(this, targetNode);
          }
        }
      } catch (Exception e) {
        log.warn("enrich: " + e.getMessage());
      }

      // ★ Шаг 14.2: чат звонка
      log.log("Шаг 11: initChat");
      initChatViews();
      initCallConversation();
      subscribeChatReceiver();

      log.log("══ onCreate END ══ — итоговый targetIp=" + targetIp + ":" + targetPort + ", callId="
          + shortId(callId) + ", isIncoming=" + isIncoming + ", targetMode=" + targetMode
          + ", localFingerprint=" + localFingerprint + ", isWifiDirectMode=" + isWifiDirectMode);

    } catch (Exception e) {
      logError("КРИТИЧЕСКАЯ ОШИБКА в onCreate", e);
      Toast.makeText(this, "Ошибка запуска звонка: " + e.getMessage(), Toast.LENGTH_LONG).show();
      finish();
    }

    // ★ УБРАНО: ручная регистрация OnBackInvokedCallback для API 33+.
    //
    // Было:
    // if (Build.VERSION.SDK_INT >= 33) {
    // try {
    // getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
    // android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
    // this::handleBackPressed);
    // log.log("onCreate: OnBackInvokedCallback зарегистрирован");
    // } catch (Exception e) {
    // logError("onCreate: registerOnBackInvokedCallback", e);
    // }
    // }
    //
    // Стало: BaseActivity.registerBackInvoked() делает это сам в onCreate()
    // и сводит колбэк в onBackPressed(). Двойная регистрация привела бы к
    // двойному вызову onBackPressed → двойному hideChat()/finish().
    //
    // ВАЖНО: в CallActivity нужно переименовать handleBackPressed() в
    // onBackPressed() (см. ниже) — иначе BaseActivity его не вызовет.
    log.log("onCreate: OnBackInvokedCallback регистрируется в BaseActivity");
  }

  // ============================================================
  // Чат звонка (Шаг 14.2)
  // ============================================================

  /**
   * ★ ИЗМЕНЕНО (правка B.6): обработчик chat_btn_attach открывает ТОТ ЖЕ диалог выбора источника, что и в ChatActivity (3 варианта: файловый менеджер / системный файл / медиа).
   *
   * БЫЛО: только FileManagerActivity (1 вариант) — отсюда жалоба «в чате звонка не все варианты передачи файлов, что в текстовом чате».
   *
   * СТАЛО: showAttachSourceDialog() — три варианта, коды запросов REQ_PICK_APP_MANAGER / REQ_PICK_FILE / REQ_PICK_MEDIA (см. константы в начале класса и onActivityResult ниже).
   */
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
        log.warn("initChatViews: не все View найдены — чат звонка отключён");
        return;
      }

      chatConvRepo = new ConversationRepository(this);
      chatMsgRepo = new MessageRepository(this);
      chatAdapter = new MessageAdapter(this, localFingerprint);
      chatList.setAdapter(chatAdapter);

      btnChatToggle.setOnClickListener(v -> toggleChat());
      if (chatBtnClose != null) {
        chatBtnClose.setOnClickListener(v -> hideChat());
      }
      chatBtnSend.setOnClickListener(v -> sendChatMessage());

      // ★ ИЗМЕНЕНО: полный диалог выбора источника (3 варианта).
      if (chatBtnAttach != null) {
        chatBtnAttach.setOnClickListener(v -> showAttachSourceDialog());
        log.log(
            "initChatViews: chat_btn_attach найден — вложения в чате звонка доступны (3 источника)");
      }

      log.log("initChatViews: OK");
    } catch (Exception e) {
      logError("initChatViews", e);
    }
  }

  /**
   * ★ НОВОЕ (правка B.7): диалог выбора источника вложения — те же 3 варианта, что в ChatActivity: 1. 📦 Файловый менеджер Mealse (pick_mode=true) 2. 📄 Системный файл (ACTION_OPEN_DOCUMENT, 3.🖼 Медиа (ACTION_GET_CONTENT,image/* video/* audio/*)
   *
   * Раньше в CallActivity обработчик chat_btn_attach открывал только FileManagerActivity — отсюда жалоба «в чате звонка не все варианты».
   */

  private void showAttachSourceDialog() {
    if (conversationId == null || conversationId.isEmpty()) {
      Toast.makeText(this, "Чат ещё не готов", Toast.LENGTH_SHORT).show();
      return;
    }
    final CharSequence[] items = { "📦 Файловый менеджер Mealse", "📄 Системный файл (любой)",
        "🖼 Медиа (фото/видео/аудио)" };
    new android.app.AlertDialog.Builder(this).setTitle("Прикрепить")
        .setItems(items, (dialog, which) -> {
          try {
            switch (which) {
              case 0: {
                // Файловый менеджер приложения в режиме «выбрать файл».
                // FileManagerActivity должен поддерживать pick_mode=true
                // и возвращать Uri выбранного файла через setResult.
                Intent i = new Intent(CallActivity.this,
                    com.my.mealse.filemanager.ui.FileManagerActivity.class);
                i.putExtra("pick_mode", true);
                startActivityForResult(i, REQ_PICK_APP_MANAGER);
                break;
              }
              case 1: {
                Intent f = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                f.addCategory(Intent.CATEGORY_OPENABLE);
                f.setType("*/*");
                startActivityForResult(f, REQ_PICK_FILE);
                break;
              }
              case 2: {
                Intent m = new Intent(Intent.ACTION_GET_CONTENT);
                m.setType("*/*");
                m.putExtra(Intent.EXTRA_MIME_TYPES,
                    new String[] { "image/*", "video/*", "audio/*" });
                startActivityForResult(m, REQ_PICK_MEDIA);
                break;
              }
            }
          } catch (Exception e) {
            logError("showAttachSourceDialog: запуск", e);
            Toast.makeText(CallActivity.this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG)
                .show();
          }
        }).setNegativeButton("Отмена", null).show();
  }

  /**
   * ★ НОВОЕ (правка B.7): приём результата от pick-активити. Три кода запроса → единая обработка handlePickedAttachment.
   */

  /**
   * ★ НОВОЕ (правка B.7): единая обработка выбранного вложения — копирует в AttachmentStorage и отправляет через MessageCommands.sendFile в текущий чат звонка (conversationId). Работает в фоне (chatExecutor).
   */
  private void handlePickedAttachment(Uri uri, int requestCode) {
    final String convId = conversationId;
    if (convId == null || convId.isEmpty()) {
      Toast.makeText(this, "Чат ещё не готов", Toast.LENGTH_SHORT).show();
      return;
    }
    // Имя/MIME/размер — из ContentResolver
    String displayName = null;
    String mime = null;
    long size = 0;
    try {
      android.content.ContentResolver cr = getContentResolver();
      android.database.Cursor c = cr.query(uri, null, null, null, null);
      if (c != null) {
        int nameIdx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
        int sizeIdx = c.getColumnIndex(android.provider.OpenableColumns.SIZE);
        if (c.moveToFirst()) {
          if (nameIdx >= 0)
            displayName = c.getString(nameIdx);
          if (sizeIdx >= 0)
            size = c.getLong(sizeIdx);
        }
        c.close();
      }
      mime = cr.getType(uri);
    } catch (Exception e) {
      log.warn("handlePickedAttachment: metadata — " + e.getMessage());
    }
    if (displayName == null || displayName.isEmpty()) {
      displayName = "file_" + System.currentTimeMillis();
    }
    if (mime == null || mime.isEmpty()) {
      mime = AttachmentInfo.guessMimeByName(displayName);
    }

    final String finalName = displayName;
    final String finalMime = mime;
    final long finalSize = size;

    chatExecutor.execute(() -> {
      try {
        String messageId = UUID.randomUUID().toString();
        File saved = AttachmentStorage.saveFromUri(CallActivity.this, convId, messageId, uri,
            finalName);
        if (saved == null) {
          chatUi.post(() -> Toast
              .makeText(CallActivity.this, "Не удалось скопировать файл", Toast.LENGTH_LONG)
              .show());
          return;
        }
        AttachmentInfo info = new AttachmentInfo(saved.getAbsolutePath(), finalName, finalMime,
            saved.length());
        MessageCommands.sendFile(CallActivity.this, convId, info, callId);
        chatUi.post(() -> {
          Toast.makeText(CallActivity.this, "📎 Отправляется: " + finalName, Toast.LENGTH_SHORT)
              .show();
          reloadChat();
        });
      } catch (Exception e) {
        logError("handlePickedAttachment", e);
        chatUi.post(() -> Toast
            .makeText(CallActivity.this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show());
      }
    });
  }

  private void initCallConversation() {
    if (chatConvRepo == null)
      return;
    if (targetFingerprint == null || targetFingerprint.isEmpty()) {
      log.warn("initCallConversation: targetFingerprint пуст — чат звонка недоступен");
      return;
    }
    chatExecutor.execute(() -> {
      try {
        // ★ ИЗМЕНЕНО: чат звонка = PRIVATE-чат с собеседником, а не CALL по callId.
        // Это позволяет переиспользовать историю сообщений между звонками
        // и видеть чат в общем списке диалогов после завершения звонка.
        Conversation conv = chatConvRepo.getOrCreatePrivate(targetFingerprint, null);
        if (conv == null) {
          log.warn("initCallConversation: не удалось создать PRIVATE-чат");
          return;
        }
        conversationId = conv.getId();
        log.log("initCallConversation: conv=" + shortId(conversationId) + " (PRIVATE, peer="
            + shortId(targetFingerprint) + ")");
        ensureCallParticipants(conversationId);
        chatUi.post(this::reloadChat);
      } catch (Exception e) {
        logError("initCallConversation", e);
      }
    });
  }

  /**
   * ★ ФИКС: CALL-чат создаётся без участников → sendText не находит target. Добавляем self (OWNER) и peer (MEMBER), если их нет.
   */
  private void ensureCallParticipants(String convId) {
    try {
      ParticipantRepository partRepo = new ParticipantRepository(this);
      if (partRepo.countByConversation(convId) > 0) {
        log.log("ensureCallParticipants: уже есть участники для " + shortId(convId));
        return;
      }

      String selfFp = localFingerprint != null ? localFingerprint : "";
      String selfDeviceId = "";
      try {
        selfDeviceId = DeviceInfoHelper.getDeviceId(this);
        if (selfDeviceId == null)
          selfDeviceId = "";
      } catch (Exception ignored) {
      }

      // Self — OWNER
      partRepo.add(convId, null, selfFp, selfDeviceId, "Я", ParticipantRole.OWNER);

      // Peer — MEMBER
      String peerFp = targetFingerprint;
      String peerDeviceId = null;
      String peerNodeKey = null;
      String peerName = "Собеседник";

      try {
        NodeManager nm = new NodeManager(this);
        TrustedNode peerNode = null;
        if (peerFp != null && !peerFp.isEmpty()) {
          peerNode = nm.findByFingerprint(peerFp);
        }
        if (peerNode == null && targetNodeId != null) {
          peerNode = nm.getNodeById(targetNodeId);
        }
        if (peerNode != null) {
          peerNodeKey = peerNode.getKey();
          peerDeviceId = peerNode.getDeviceId();
          if (peerNode.getName() != null)
            peerName = peerNode.getName();
        }
      } catch (Exception e) {
        log.warn("ensureCallParticipants: NodeManager: " + e.getMessage());
      }

      if (peerFp != null && !peerFp.isEmpty()) {
        partRepo.add(convId, peerNodeKey, peerFp, peerDeviceId, peerName, ParticipantRole.MEMBER);
      } else if (peerDeviceId != null && !peerDeviceId.isEmpty()) {
        partRepo.add(convId, peerNodeKey, null, peerDeviceId, peerName, ParticipantRole.MEMBER);
      } else {
        log.warn("ensureCallParticipants: нет ни fingerprint, ни deviceId peer");
      }

      log.log("ensureCallParticipants: добавлено, теперь " + partRepo.countByConversation(convId)
          + " участников для " + shortId(convId));
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
      f.addAction(MessengerEvents.ACTION_MESSAGE_PROGRESS); // ★ Улучшение №3
      BroadcastUtils.registerNotExported(this, chatRefreshReceiver, f);
      chatReceiverRegistered = true;
      log.log("subscribeChatReceiver: OK (NEW + STATUS + PROGRESS)");
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
    if (chatOverlay.getVisibility() == View.VISIBLE) {
      hideChat();
    } else {
      showChat();
    }
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
      } catch (Exception e) {
        log.logError("markRead in showChat", e);
      }
    }
  }

  private void hideChat() {
    if (chatOverlay == null)
      return;
    chatOverlay.setVisibility(View.GONE);
    if (btnChatToggle != null) {
      btnChatToggle.setVisibility(View.VISIBLE);
    }
  }

  private void reloadChat() {
    if (conversationId == null || chatMsgRepo == null || chatAdapter == null)
      return;
    chatExecutor.execute(() -> {
      try {
        List<Message> list = chatMsgRepo.getByConversation(conversationId, 50, 0);
        java.util.Collections.reverse(list); // ★ старые сверху
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
        if (count > 0) {
          chatList.setSelection(count - 1);
        }
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
      // ★ передаём callId
      String messageId = MessageCommands.sendText(this, conversationId, text, callId);
      if (messageId == null) {
        Toast.makeText(this, "Не удалось отправить", Toast.LENGTH_SHORT).show();
        return;
      }
      chatInput.setText("");
      chatUi.postDelayed(this::reloadChat, 150);
    } catch (Exception e) {
      logError("sendChatMessage", e);
    }
  }

  // ============================================================
  // ★ НОВОЕ: вложения в чате звонка (pick из внутреннего менеджера)
  // ============================================================

  private void showChatAttachDialog() {
    if (conversationId == null) {
      Toast.makeText(this, "Чат ещё не готов", Toast.LENGTH_SHORT).show();
      return;
    }
    String[] options = { "📁 Файл (системный проводник)", "📦 Приложение (внутренний менеджер)",
        "🖼 Галерея (фото/видео)" };
    new android.app.AlertDialog.Builder(this).setTitle("Прикрепить").setItems(options, (d, w) -> {
      switch (w) {
        case 0:
          pickFileForChat();
          break;
        case 1:
          pickFromAppManagerForChat();
          break;
        case 2:
          pickMediaForChat();
          break;
      }
    }).setNegativeButton("Отмена", null).show();
  }

  private void pickFileForChat() {
    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    i.addCategory(Intent.CATEGORY_OPENABLE);
    i.setType("*/*");
    try {
      startActivityForResult(i, REQ_PICK_FILE);
    } catch (Exception e) {
      toast("Не удалось открыть проводник: " + e.getMessage());
    }
  }

  /**
   * ★ НОВОЕ: открыть FileManagerActivity в pick-mode. Пользователь выбирает файл из локальных источников (📱 Это устройство / 📦 Приложение), результат возвращается в onActivityResult.
   */
  private void pickFromAppManagerForChat() {
    try {
      Intent i = new Intent(this, com.my.mealse.filemanager.ui.FileManagerActivity.class);
      i.putExtra(com.my.mealse.filemanager.ui.FileManagerActivity.EXTRA_PICK_MODE, true);
      startActivityForResult(i, REQ_PICK_APP_MANAGER);
    } catch (Exception e) {
      toast("Не удалось открыть файловый менеджер: " + e.getMessage());
    }
  }

  private void pickMediaForChat() {
    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
    i.addCategory(Intent.CATEGORY_OPENABLE);
    i.setType("*/*");
    i.putExtra(Intent.EXTRA_MIME_TYPES, new String[] { "image/*", "video/*" });
    try {
      startActivityForResult(Intent.createChooser(i, "Выбрать медиа"), REQ_PICK_MEDIA);
    } catch (Exception e) {
      toast("Не удалось открыть галерею: " + e.getMessage());
    }
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);

    if (resultCode != RESULT_OK) {
      log.log("onActivityResult: resultCode != OK (req=" + requestCode + ")");
      return;
    }

    // ★ НОВОЕ: результат из внутреннего менеджера (pick-mode)
    if (requestCode == REQ_PICK_APP_MANAGER && data != null) {
      String path = data
          .getStringExtra(com.my.mealse.filemanager.ui.FileManagerActivity.EXTRA_PICKED_PATH);
      String name = data
          .getStringExtra(com.my.mealse.filemanager.ui.FileManagerActivity.EXTRA_PICKED_NAME);
      String mime = data
          .getStringExtra(com.my.mealse.filemanager.ui.FileManagerActivity.EXTRA_PICKED_MIME);
      long size = data
          .getLongExtra(com.my.mealse.filemanager.ui.FileManagerActivity.EXTRA_PICKED_SIZE, 0);
      if (path == null || path.isEmpty()) {
        toast("Файл не выбран");
        return;
      }
      handlePickedPathForChat(path, name, mime, size);
      return;
    }

    // SAF-пути (системный проводник / галерея)
    switch (requestCode) {
      case REQ_PICK_FILE:
      case REQ_PICK_MEDIA:
        if (data != null && data.getData() != null) {
          handlePickedUriForChat(data.getData());
        }
        break;
    }
  }

  /**
   * ★ НОВОЕ: файл из внутреннего менеджера (путь уже валиден, srcFile — реальный файл в 📱/📦). Копируем в messenger/attachments через MessageCommands.sendFile (он сам делает AttachmentStorage.saveFromFile). Исходный файл НЕ удаляем.
   */
  private void handlePickedPathForChat(String path, String name, String mime, long size) {
    if (conversationId == null) {
      toast("Чат ещё не готов");
      return;
    }
    chatExecutor.execute(() -> {
      try {
        File src = new File(path);
        if (!src.exists() || !src.isFile()) {
          chatUi.post(() -> toast("Файл недоступен"));
          return;
        }
        long actualSize = src.length();
        if (actualSize <= 0) {
          chatUi.post(() -> toast("Пустой файл"));
          return;
        }
        if (actualSize > MessageCommands.MAX_ATTACHMENT_BYTES) {
          chatUi.post(() -> toast("Файл слишком большой (" + AttachmentInfo.formatSize(actualSize)
              + " > " + AttachmentInfo.formatSize(MessageCommands.MAX_ATTACHMENT_BYTES) + ")"));
          return;
        }
        AttachmentInfo info = new AttachmentInfo(src.getAbsolutePath(),
            name != null ? name : src.getName(),
            mime != null ? mime : AttachmentInfo.guessMimeByName(src.getName()), actualSize);
        sendAttachmentToChat(info, src, false); // внешний файл — не удалять
      } catch (Exception e) {
        logError("handlePickedPathForChat", e);
        chatUi.post(() -> toast("Ошибка: " + e.getMessage()));
      }
    });
  }

  /**
   * ★ НОВОЕ: SAF-файл (системный проводник / галерея) — копируем во временный файл в cache, затем отправляем (tmp удаляем после отправки).
   */
  private void handlePickedUriForChat(Uri uri) {
    if (conversationId == null) {
      toast("Чат ещё не готов");
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
          // noinspection ResultOfMethodCallIgnored
          tmp.delete();
          chatUi.post(() -> toast("Пустой файл"));
          return;
        }
        if (size > MessageCommands.MAX_ATTACHMENT_BYTES) {
          // noinspection ResultOfMethodCallIgnored
          tmp.delete();
          chatUi.post(() -> toast("Файл слишком большой (" + AttachmentInfo.formatSize(size) + " > "
              + AttachmentInfo.formatSize(MessageCommands.MAX_ATTACHMENT_BYTES) + ")"));
          return;
        }
        AttachmentInfo info = new AttachmentInfo(tmp.getAbsolutePath(), name, mime, size);
        sendAttachmentToChat(info, tmp, true); // tmp — удалить после отправки
      } catch (Exception e) {
        logError("handlePickedUriForChat", e);
        chatUi.post(() -> toast("Ошибка: " + e.getMessage()));
      }
    });
  }

  /**
   * ★ НОВОЕ: обёртка над MessageCommands.sendFile для чата звонка.
   *
   * @param deleteSrcAfter удалять ли srcFile после отправки (true — для tmp из cache).
   */
  private void sendAttachmentToChat(AttachmentInfo info, File srcFile, boolean deleteSrcAfter) {
    if (conversationId == null) {
      toast("Чат ещё не готов");
      return;
    }
    try {
      // ★ передаём callId
      String messageId = MessageCommands.sendFile(this, conversationId, info, callId);
      if (messageId == null) {
        chatUi.post(() -> toast("Не удалось отправить файл"));
        if (deleteSrcAfter && srcFile != null && srcFile.exists()) {
          // noinspection ResultOfMethodCallIgnored
          srcFile.delete();
        }
        return;
      }
      log.log("sendAttachmentToChat: msg=" + shortId(messageId) + ", file=" + info.getFileName()
          + ", size=" + info.getSizeHuman());
      chatUi.post(() -> {
        toast("📎 Отправлено: " + info.getFileName());
        chatUi.postDelayed(this::reloadChat, 150);
      });
      if (deleteSrcAfter && srcFile != null && srcFile.exists()
          && srcFile.getAbsolutePath().startsWith(getCacheDir().getAbsolutePath())) {
        // noinspection ResultOfMethodCallIgnored
        srcFile.delete();
      }
    } catch (Exception e) {
      logError("sendAttachmentToChat", e);
      chatUi.post(() -> toast("Ошибка: " + e.getMessage()));
    }
  }

  // ============================================================
  // Wi-Fi Direct listener
  // ============================================================

  private void subscribeWifiDirectListener() {
    if (wifiDirectManager == null) {
      log.warn("subscribeWifiDirectListener: wifiDirectManager == null");
      return;
    }
    if (wifiDirectListener != null) {
      log.log("subscribeWifiDirectListener: уже подписан — пропускаю");
      return;
    }

    try {
      wifiDirectListener = new WifiDirectManager.Listener() {
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
          log.log("WD listener: onConnected (уже было во время звонка)");
        }

        @Override
        public void onDisconnected() {
          log.warn("WD listener: onDisconnected — завершаем звонок");
          runOnUiThread(() -> {
            try {
              Toast.makeText(CallActivity.this, "Wi-Fi Direct соединение потеряно",
                  Toast.LENGTH_LONG).show();
              finish();
            } catch (Exception e) {
              logError("onDisconnected: finish", e);
            }
          });
        }

        @Override
        public void onSignal(String json) {
          log.log("📩 WD signal JSON: " + truncate(json, 300));
          try {
            handleIncomingSignal(json);
          } catch (Exception e) {
            logError("WD listener: onSignal", e);
          }
        }

        @Override
        public void onError(String message) {
          log.error("WD listener: onError — " + message);
        }
      };

      wifiDirectManager.addListener(wifiDirectListener);
      log.log("subscribeWifiDirectListener: listener установлен, listenerCount="
          + wifiDirectManager.getListenerCount());
    } catch (Exception e) {
      logError("subscribeWifiDirectListener", e);
    }
  }

  private void unsubscribeWifiDirectListener() {
    if (wifiDirectManager == null || wifiDirectListener == null) {
      log.log("unsubscribeWifiDirectListener: нечего снимать");
      return;
    }
    try {
      wifiDirectManager.removeListener(wifiDirectListener);
      log.log("unsubscribeWifiDirectListener: listener снят, осталось="
          + wifiDirectManager.getListenerCount());
      wifiDirectListener = null;
    } catch (Exception e) {
      logError("unsubscribeWifiDirectListener", e);
    }
  }

  private void registerSignalReceiver() {
    if (receiverRegistered)
      return;
    try {
      log.log("Регистрация signalReceiver (onCreate)");
      IntentFilter filter = new IntentFilter("com.my.mealse.SIGNAL");
      BroadcastUtils.registerNotExported(this, signalReceiver, filter);
      receiverRegistered = true;
      log.log("signalReceiver зарегистрирован (SDK=" + Build.VERSION.SDK_INT + ")");
    } catch (Exception e) {
      logError("Ошибка регистрации receiver", e);
    }
  }

  private void unregisterSignalReceiver() {
    if (!receiverRegistered)
      return;
    try {
      BroadcastUtils.unregisterSafely(this, signalReceiver);
      receiverRegistered = false;
      log.log("signalReceiver снят");
    } catch (Exception e) {
      logError("Ошибка снятия receiver", e);
    }
  }

  private void setupWebView() {
    try {
      WebSettings settings = webView.getSettings();
      settings.setJavaScriptEnabled(true);
      settings.setMediaPlaybackRequiresUserGesture(false);
      settings.setAllowFileAccess(true);
      settings.setAllowFileAccessFromFileURLs(true);
      settings.setAllowUniversalAccessFromFileURLs(true);
      settings.setDomStorageEnabled(true);
      settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
      log.log("WebSettings настроены");

      webView.addJavascriptInterface(new WebAppInterface(), "android");
      log.log("JS-интерфейс 'android' добавлен");

      webView.setWebViewClient(new WebViewClient() {
        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
          super.onPageStarted(view, url, favicon);
          log.log("🌐 onPageStarted: " + url);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
          super.onPageFinished(view, url);
          log.log("🌐 onPageFinished: " + url);
          webViewLoaded = true;

          try {
            String iceJson = iceConfigManager.buildIceConfigJsonForMode(
                targetMode != null ? targetMode.name() : "AUTO", targetGatewayIp,
                targetGatewayPort);
            String escapedIce = iceJson.replace("\\", "\\\\").replace("'", "\\'");
            view.evaluateJavascript("configureIce('" + escapedIce + "')", null);
            log.log("→ JS: configureIce(" + iceJson + ") [mode="
                + (targetMode != null ? targetMode.name() : "AUTO") + "]");

            String modeName = targetMode != null ? targetMode.name() : "AUTO";
            String modeScript = "configureIceMode('" + modeName + "'" + ", '"
                + (targetGatewayIp != null ? targetGatewayIp : "") + "'" + ", " + targetGatewayPort
                + ")";
            view.evaluateJavascript(modeScript, null);
            log.log("→ JS: " + modeScript);

            if (isIncoming) {
              view.evaluateJavascript("setIncomingMode(true)", null);
              log.log("→ JS: setIncomingMode(true)");
            } else {
              view.evaluateJavascript("setIncomingMode(false)", null);
              log.log("→ JS: setIncomingMode(false)");
            }

            if (isIncoming && incomingOfferJson != null && !incomingOfferHandled) {
              view.postDelayed(() -> {
                log.log("Отправка сохранённого offer через 300ms");
                sendOfferToWebView(incomingOfferJson);
                incomingOfferHandled = true;
              }, 300);
            }

            view.postDelayed(() -> flushPendingSignals(), 400);
          } catch (Exception e) {
            logError("Ошибка в onPageFinished", e);
          }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
            WebResourceError error) {
          super.onReceivedError(view, request, error);
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            log.error("🌐 onReceivedError: " + error.getDescription() + " для " + request.getUrl());
          }
        }
      });
      log.log("WebViewClient установлен");

      log.log("Установка WebChromeClient для разрешений камеры/микрофона");
      webView.setWebChromeClient(new WebChromeClient() {
        @Override
        public void onPermissionRequest(final PermissionRequest request) {
          log.log(
              "WebChromeClient.onPermissionRequest: " + Arrays.toString(request.getResources()));
          runOnUiThread(() -> {
            try {
              request.grant(request.getResources());
              log.log("WebChromeClient: разрешения выданы");
            } catch (Exception e) {
              logError("Ошибка grant()", e);
            }
          });
        }

        @Override
        public boolean onConsoleMessage(android.webkit.ConsoleMessage cm) {
          log.log("[console] " + cm.message() + " (" + cm.sourceId() + ":" + cm.lineNumber() + ")");
          return true;
        }
      });
      log.log("WebChromeClient установлен");

      log.log("Загрузка call.html");
      webView.loadUrl("file:///android_asset/call.html");

    } catch (Exception e) {
      logError("Ошибка setupWebView", e);
    }
  }

  @Override
  protected void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
    try {
      if (callId != null) {
        outState.putString(STATE_CALL_ID, callId);
        log.log("onSaveInstanceState: callId=" + shortId(callId));
      }
      outState.putBoolean(STATE_OFFER_HANDLED, incomingOfferHandled);
    } catch (Exception e) {
      logError("Ошибка onSaveInstanceState", e);
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    log.log("══ onResume START ══");
    if (!isWifiDirectMode && !receiverRegistered) {
      registerSignalReceiver();
    }
    if (isWifiDirectMode && wifiDirectListener == null && wifiDirectManager != null) {
      log.log("onResume: переподписка на Wi-Fi Direct listener");
      subscribeWifiDirectListener();
    }
    if (chatOverlay != null && chatOverlay.getVisibility() == View.VISIBLE
        && conversationId != null) {
      try {
        MessageCommands.markRead(this, conversationId);
      } catch (Exception e) {
        log.logError("markRead in onResume", e);
      }
    }
    log.log("══ onResume END ══");
  }

  @Override
  protected void onPause() {
    log.log("onPause START");
    log.log("onPause END");
    super.onPause();
  }

  /**
   * ★ ИЗМЕНЕНО (правка B.4): onDestroy больше НЕ шлёт bye безусловно.
   *
   * БЫЛО: if (isFinishing) { sendBye(); } → при вытеснении Activity системой (isFinishing=true, но пользователь лишь открыл FileManagerActivity) собеседник получал bye, звонок рвался. В логе: 16:50:02 onPause (ушли в FileManager) ... 16:52:01 onDestroy: isFinishing=true → отправка bye — то есть звонок рвался не из-за FileManager, а из-за того, что система убила фоновую task CallActivity (taskAffinity="").
   *
   * СТАЛО: - bye шлём ТОЛЬКО если endingByUser && !byeAlreadySent. На практике к моменту onDestroy один из флагов уже выставлен (endCallByUser или handleBye), так что onDestroy bye не шлёт никогда — это safety-net на случай, если finish() вызван из кода минуя endCallByUser. - webViewDestroyed=true ДО loadUrl("about:blank"), чтобы evaluateJs не летел в мёртвый WebView.
   */
  @Override
  protected void onDestroy() {
    log.log("onDestroy START");
    try {
      unsubscribeWifiDirectListener();
    } catch (Exception e) {
      logError("onDestroy: unsubscribeWifiDirectListener", e);
    }

    // ★ ИЗМЕНЕНО: bye только если пользователь завершил звонок
    // и bye ещё не отправлен.
    if (endingByUser && !byeAlreadySent) {
      log.log("onDestroy: endingByUser=true, bye ещё не отправлен → отправка bye");
      byeAlreadySent = true;
      try {
        sendBye();
      } catch (Exception e) {
        logError("onDestroy: sendBye", e);
      }
    } else {
      log.log("onDestroy: bye НЕ отправляем (endingByUser=" + endingByUser + ", byeAlreadySent="
          + byeAlreadySent + ")");
    }

    try {
      CallStateManager csm = new CallStateManager(this);
      csm.clearCallActive();
    } catch (Exception e) {
      logError("onDestroy: clearCallActive", e);
    }

    // ★ ИЗМЕНЕНО: помечаем WebView уничтоженным ДО loadUrl("about:blank"),
    // чтобы evaluateJs из onResume/onPageFinished не летел в about:blank
    // (в логе: Uncaught ReferenceError: configureIce is not defined (about:blank:1)).
    webViewDestroyed = true;
    try {
      if (webView != null) {
        webView.loadUrl("about:blank");
        webView.clearHistory();
        webView.removeAllViews();
        webView.destroy();
        webView = null;
        log.log("WebView очищен");
      }
    } catch (Exception e) {
      logError("onDestroy: webView.destroy", e);
    }

    // Отписываемся от чата
    try {
      if (chatReceiverRegistered) {
        unregisterReceiver(chatRefreshReceiver);
        chatReceiverRegistered = false;
      }
    } catch (Exception ignored) {
    }

    log.log("onDestroy END");
    super.onDestroy();
  }

  private void checkFullScreenIntentPermission() {
    if (Build.VERSION.SDK_INT < 34) {
      log.log("API < 34: full-screen intent не требует разрешения");
      return;
    }

    try {
      android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
      if (nm == null) {
        log.warn("NotificationManager == null");
        return;
      }
      boolean canUse = nm.canUseFullScreenIntent();
      log.log("canUseFullScreenIntent=" + canUse);

      if (!canUse) {
        log.warn("Разрешение USE_FULL_SCREEN_INTENT не выдано, открываю настройки");
        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT);
        intent.setData(Uri.parse("package:" + getPackageName()));
        try {
          startActivity(intent);
          log.log("Открыты настройки USE_FULL_SCREEN_INTENT");
        } catch (Exception e) {
          log.logError("Не удалось открыть настройки full-screen intent", e);
        }
      }
    } catch (Exception e) {
      logError("Ошибка проверки full-screen intent", e);
    }
  }

  // ================= JS-интерфейс =================

  class WebAppInterface {

    @JavascriptInterface
    public void sendSignal(String type, String sdp, String candidate) {
      try {
        int n = signalSentCount.incrementAndGet();

        log.logSignal("TX", type, callId);
        log.log("sendSignal #" + n + ": type=" + type + ", sdp="
            + (sdp != null ? sdp.length() + " симв." : "null") + ", candidate="
            + (candidate != null ? candidate : "null"));
        log.log("sendSignal #" + n + ": targetIp=" + targetIp + ":" + targetPort + ", mode="
            + targetMode + ", isIncoming=" + isIncoming + ", isWifiDirectMode=" + isWifiDirectMode);

        if (!isWifiDirectMode && (targetIp == null || targetIp.isEmpty())) {
          log.error("sendSignal #" + n + ": targetIp пустой — сигнал " + type + " НЕ отправлен");
          return;
        }

        JsonObject obj = new JsonObject();
        obj.addProperty("type", type);
        obj.addProperty("from_device_id", DeviceInfoHelper.getDeviceId(CallActivity.this));
        obj.addProperty("from_fingerprint", localFingerprint);
        obj.addProperty("call_id", callId);
        obj.addProperty("timestamp", System.currentTimeMillis() / 1000);
        obj.addProperty("mode", targetMode != null ? targetMode.name() : "AUTO");

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
            log.logError("Ошибка разбора candidate", e);
          }
        }
        obj.add("payload", payload);

        String body = obj.toString();
        log.log("sendSignal #" + n + " body: " + truncate(body, 200));

        if (isWifiDirectMode) {
          if (wifiDirectManager != null && wifiDirectManager.isConnected()) {
            wifiDirectManager.sendSignal(body);
            log.log("sendSignal #" + n + ": WifiDirectManager.sendSignal, type=" + type);
          } else {
            log.error("sendSignal #" + n + ": Wi-Fi Direct не подключён — сигнал " + type
                + " НЕ отправлен");
          }
          return;
        }

        Intent svc = new Intent(CallActivity.this, ConnectionManager.class);
        svc.setAction(ConnectionManager.ACTION_SEND_SIGNAL);
        svc.putExtra(ConnectionManager.EXTRA_TARGET_IP, targetIp);
        svc.putExtra(ConnectionManager.EXTRA_TARGET_PORT, targetPort);
        svc.putExtra(ConnectionManager.EXTRA_SIGNAL_TYPE, type);
        svc.putExtra(ConnectionManager.EXTRA_SIGNAL_BODY, body);
        startForegroundService(svc);
        log.log("sendSignal #" + n + ": ConnectionManager запущен, type=" + type + ", targetIp="
            + targetIp + ":" + targetPort);
      } catch (Exception e) {
        logError("Ошибка sendSignal", e);
      }
    }

    @JavascriptInterface
    public void onCallEnded() {
      log.log("JS: onCallEnded");
      runOnUiThread(() -> {
        try {
          sendBye();
          finish();
        } catch (Exception e) {
          logError("Ошибка onCallEnded", e);
        }
      });
    }

    @JavascriptInterface
    public void onCallError(String error) {
      log.error("JS: onCallError — " + error);
      runOnUiThread(() -> {
        try {
          Toast.makeText(CallActivity.this, "Ошибка: " + error, Toast.LENGTH_LONG).show();
          finish();
        } catch (Exception e) {
          logError("Ошибка onCallError", e);
        }
      });
    }

    @JavascriptInterface
    public void onLog(String message) {
      log.log("[JS] " + message);
    }
  }

  private void sendBye() {
    try {
      log.log("sendBye: callId=" + shortId(callId) + ", targetIp=" + targetIp + ":" + targetPort
          + ", isWifiDirectMode=" + isWifiDirectMode);

      JsonObject obj = new JsonObject();
      obj.addProperty("type", "bye");
      obj.addProperty("from_device_id", DeviceInfoHelper.getDeviceId(this));
      obj.addProperty("from_fingerprint", localFingerprint != null ? localFingerprint : "");
      obj.addProperty("call_id", callId);
      obj.addProperty("timestamp", System.currentTimeMillis() / 1000);

      if (isWifiDirectMode) {
        if (wifiDirectManager != null && wifiDirectManager.isConnected()) {
          wifiDirectManager.sendSignal(obj.toString());
          log.log("bye отправлен через Wi-Fi Direct");
        } else {
          log.warn("sendBye: Wi-Fi Direct не подключён — bye не отправлен");
        }
        return;
      }

      if (targetIp == null || targetIp.isEmpty()) {
        log.warn("sendBye: targetIp пустой — bye не отправлен");
        return;
      }

      Intent svc = new Intent(this, ConnectionManager.class);
      svc.setAction(ConnectionManager.ACTION_SEND_SIGNAL);
      svc.putExtra(ConnectionManager.EXTRA_TARGET_IP, targetIp);
      svc.putExtra(ConnectionManager.EXTRA_TARGET_PORT, targetPort);
      svc.putExtra(ConnectionManager.EXTRA_SIGNAL_TYPE, "bye");
      svc.putExtra(ConnectionManager.EXTRA_SIGNAL_BODY, obj.toString());
      startForegroundService(svc);
      log.log("bye отправлен на " + targetIp + ":" + targetPort);
    } catch (Exception e) {
      logError("Ошибка отправки bye", e);
    }
  }

  // ================= Разрешения =================

  private void requestPermissions() {
    try {
      boolean hasCamera = checkSelfPermission(
          Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
      boolean hasMic = checkSelfPermission(
          Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
      log.log("Разрешения: camera=" + hasCamera + ", mic=" + hasMic);

      if (!hasCamera || !hasMic) {
        log.log("Запрашиваю разрешения...");
        requestPermissions(
            new String[] { Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO }, 100);
      } else {
        log.log("Все разрешения есть");
      }
    } catch (Exception e) {
      logError("Ошибка requestPermissions", e);
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions,
      int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    log.log("onRequestPermissionsResult: requestCode=" + requestCode);

    if (requestCode == 100) {
      try {
        boolean granted = grantResults.length > 0
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
            && grantResults[1] == PackageManager.PERMISSION_GRANTED;
        log.log("granted=" + granted);

        if (!granted) {
          log.warn("Разрешения отклонены");
          Toast.makeText(this, "Для звонка нужны разрешения камеры и микрофона", Toast.LENGTH_LONG)
              .show();
          finish();
        }
      } catch (Exception e) {
        logError("Ошибка onRequestPermissionsResult", e);
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

      if (webView != null && webView.canGoBack()) {
        log.log("onBackPressed: webView.canGoBack=true");
        webView.goBack();
      } else {
        log.log("onBackPressed: finish (bye отправит onDestroy)");
        super.onBackPressed();
      }
    } catch (Exception e) {
      logError("Ошибка onBackPressed", e);
      super.onBackPressed();
    }
  }

  /**
   * ★ НОВОЕ: обработчик системного «назад» для API 33+.
   *
   * Логика идентична onBackPressed(), но вызывается через OnBackInvokedDispatcher.registerOnBackInvokedCallback(PRIORITY_DEFAULT, this::handleBackPressed).
   *
   * Отличие от onBackPressed(): вместо super.onBackPressed() вызываем finish() — OnBackInvokedCallback не переопределяет onBackPressed(), super.onBackPressed() бросит IllegalStateException на некоторых прошивках.
   */
  private void handleBackPressed() {
    try {
      if (chatOverlay != null && chatOverlay.getVisibility() == View.VISIBLE) {
        log.log("handleBackPressed: закрываю оверлей чата");
        hideChat();
        return;
      }

      if (webView != null && webView.canGoBack()) {
        log.log("handleBackPressed: webView.canGoBack=true");
        webView.goBack();
      } else {
        log.log("handleBackPressed: finish (bye отправит onDestroy)");
        finish();
      }
    } catch (Exception e) {
      logError("Ошибка handleBackPressed", e);
      finish();
    }
  }

  /**
   * ★ НОВОЕ (правка B.3): единая точка завершения звонка по инициативе пользователя. Вызывать из: - handleBackPressed() (аппаратная «назад»); - обработчика кнопки «завершить» в call.html (через @JavascriptInterface); - пункта меню «Завершить звонок», если появится.
   *
   * Логика: 1. Если bye уже отправлен/получен (byeAlreadySent) — только finish(), второй bye не шлём. 2. Иначе: endingByUser=true, byeAlreadySent=true, sendBye(), finish().
   *
   * Почему не в onDestroy: onDestroy вызывается и при вытеснении Activity системой (isFinishing=true, но пользователь звонок не завершал). Раньше onDestroy слал bye в этом случае → звонок рвался у собеседника (в логе: FileManager → onDestroy → bye). Теперь bye шлётся только из endCallByUser() — осознанное действие пользователя.
   */
  private void endCallByUser() {
    if (byeAlreadySent) {
      log.log("endCallByUser: bye уже отправлен/получен — только finish");
      endingByUser = true;
      finish();
      return;
    }
    endingByUser = true;
    byeAlreadySent = true;
    log.log("endCallByUser: отправка bye, callId=" + shortId(callId) + ", targetIp=" + targetIp
        + ", isWifiDirectMode=" + isWifiDirectMode);
    try {
      sendBye();
    } catch (Exception e) {
      logError("endCallByUser: sendBye", e);
    }
    finish();
  }

  // ================= Хелперы =================

  private void logError(String message, Throwable e) {
    if (log != null) {
      log.logError(message, e);
    } else {
      Log.e(TAG, message, e);
    }
  }

  private String escapeForJs(String json) {
    if (json == null)
      return "";
    return json.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r");
  }

  private String shortId(String id) {
    if (id == null)
      return "?";
    return id.length() > 8 ? id.substring(0, 8) : id;
  }

  private String truncate(String s, int max) {
    if (s == null)
      return "null";
    return s.length() > max ? s.substring(0, max) + "..." : s;
  }

  private void toast(String message) {
    try {
      Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    } catch (Throwable ignored) {
    }
  }

  // ★ НОВОЕ: хелперы для SAF-пути (ACTION_OPEN_DOCUMENT / GET_CONTENT)
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
}