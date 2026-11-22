package com.my.mealse;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import com.google.gson.JsonObject;
import com.my.mealse.service.ConnectionManager;
import com.my.mealse.util.DeviceInfoHelper;
import com.my.mealse.util.IncomingCallNotifier;
import com.my.mealse.util.MealseLogger;
import com.my.mealse.util.SecureAuthManager;

import com.my.mealse.util.WifiDirectManager;

public class IncomingCallActivity extends Activity {

  private static final String TAG = "IncomingCall";

  public static final String ACTION_DECLINE = "com.my.mealse.INCOMING_DECLINE";

  public static final String EXTRA_CALL_ID = "call_id";
  public static final String EXTRA_CALLER_NAME = "caller_name";
  public static final String EXTRA_CALLER_IP = "caller_ip";
  public static final String EXTRA_CALLER_PORT = "caller_port";
  public static final String EXTRA_OFFER_JSON = "offer_json";
  public static final String EXTRA_WARNING = "warning";

  // ★ НОВЫЕ extras для режима подключения
  public static final String EXTRA_TARGET_MODE = "target_mode";
  public static final String EXTRA_GATEWAY_IP = "gateway_ip";
  public static final String EXTRA_GATEWAY_PORT = "gateway_port";
  public static final String EXTRA_TARGET_FINGERPRINT = "target_fingerprint";

  private MealseLogger log;

  private String callId;
  private String callerName;
  private String callerIp;
  private int callerPort;
  private String offerJson;

  // ★ НОВЫЕ поля
  private String targetMode = "AUTO";
  private String gatewayIp;
  private int gatewayPort = 8083;
  private String targetFingerprint;

  /**
   * ★ НОВОЕ: WD-менеджер. Нужен, чтобы при targetMode == "WIFI_DIRECT" отправлять bye через WifiDirectManager.sendSignal, а не через ConnectionManager (HTTP). В WD-сети HTTP-IP собеседника из обычной Wi-Fi недоступен — ConnectionManager упал бы в таймаут.
   */
  private WifiDirectManager wifiDirectManager;

  // ================= Фабричный метод =================

  /**
   * ★ РАСШИРЕННЫЙ: полная сигнатура с режимом и fingerprint.
   */
  /**
   * ★ ИЗМЕНЕНО: NEW_TASK | CLEAR_TOP | SINGLE_TOP.
   *
   * Было: только NEW_TASK. Если IncomingCallActivity уже в task (но не на вершине — например, поверх неё CallActivity), повторный startActivity создавал ВТОРОЙ экземпляр → пользователь видел два экрана входящего, каждый onAccept → CallActivity заново → 3 answer на один call_id (d964dbeb).
   *
   * Стало: CLEAR_TOP | SINGLE_TOP — существующий экземпляр поднимается наверх и получает onNewIntent (обновляет поля/UI), второй не создаётся. launchMode="singleTop" в манифесте + эти флаги — надёжно.
   */
  public static Intent newIntent(Context context, String callId, String callerName, String callerIp,
      int callerPort, String offerJson, String warning, String targetMode, String gatewayIp,
      int gatewayPort, String targetFingerprint) {
    Intent i = new Intent(context, IncomingCallActivity.class);
    i.putExtra(EXTRA_CALL_ID, callId);
    i.putExtra(EXTRA_CALLER_NAME, callerName);
    i.putExtra(EXTRA_CALLER_IP, callerIp);
    i.putExtra(EXTRA_CALLER_PORT, callerPort);
    i.putExtra(EXTRA_OFFER_JSON, offerJson);
    i.putExtra(EXTRA_WARNING, warning);
    i.putExtra(EXTRA_TARGET_MODE, targetMode);
    i.putExtra(EXTRA_GATEWAY_IP, gatewayIp);
    i.putExtra(EXTRA_GATEWAY_PORT, gatewayPort);
    i.putExtra(EXTRA_TARGET_FINGERPRINT, targetFingerprint);
    // ★ CLEAR_TOP | SINGLE_TOP: не создаём второй экземпляр,
    // а доставляем в onNewIntent существующего.
    i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    return i;
  }

  /**
   * ★ ОБРАТНАЯ СОВМЕСТИМОСТЬ: старая сигнатура без режима. Использует дефолтные AUTO, null, 8083, null.
   */
  @Deprecated
  public static Intent newIntent(Context context, String callId, String callerName, String callerIp,
      int callerPort, String offerJson, String warning) {
    return newIntent(context, callId, callerName, callerIp, callerPort, offerJson, warning, "AUTO",
        null, 8083, null);
  }

  // ================= Lifecycle =================

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    log = MealseLogger.get(this, TAG);
    log.log("══ onCreate START ══");

    // ★ НОВОЕ: WD-менеджер — для WD-ветки в sendBye
    try {
      wifiDirectManager = ((MealseApp) getApplication()).getWifiDirectManager();
    } catch (Exception e) {
      log.warn("onCreate: getWifiDirectManager: " + e.getMessage());
      wifiDirectManager = null;
    }

    // Показ поверх lock screen
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      setShowWhenLocked(true);
      setTurnScreenOn(true);
      log.log("setShowWhenLocked(true), setTurnScreenOn(true) (API >= 27)");
    } else {
      getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
          | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
          | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
      log.log("FLAG_SHOW_WHEN_LOCKED | TURN_SCREEN_ON | KEEP_SCREEN_ON (API < 27)");
    }

    Intent intent = getIntent();
    log.log("Intent action=" + intent.getAction());
    dumpExtras(intent, "onCreate");

    readFieldsFromIntent(intent);

    if (callerIp == null || callerIp.isEmpty()) {
      log.error(
          "КРИТИЧНО: callerIp пустой! В CallActivity уйдёт target_ip=null и звонок не отправится.");
    }

    // Быстрое отклонение без UI
    if (ACTION_DECLINE.equals(intent.getAction())) {
      log.log("ACTION_DECLINE: быстрое отклонение звонка " + shortId(callId));
      sendBye("declined");
      new IncomingCallNotifier(this).cancel();
      finish();
      log.log("══ onCreate END (decline) ══");
      return;
    }

    setContentView(R.layout.activity_incoming_call);

    TextView tvName = findViewById(R.id.tv_caller_name);
    TextView tvIp = findViewById(R.id.tv_caller_ip);
    TextView tvWarning = findViewById(R.id.tv_warning);
    Button btnAccept = findViewById(R.id.btn_accept);
    Button btnDecline = findViewById(R.id.btn_decline);

    tvName.setText(callerName != null ? callerName : "Неизвестный");

    StringBuilder ipLine = new StringBuilder();
    ipLine.append(callerIp).append(":").append(callerPort);
    if ("GATEWAY".equalsIgnoreCase(targetMode)) {
      ipLine.append("  [через шлюз]");
    } else if ("DIRECT".equalsIgnoreCase(targetMode)) {
      ipLine.append("  [напрямую]");
    }
    tvIp.setText(ipLine.toString());

    String warning = getIntent().getStringExtra(EXTRA_WARNING);
    if (warning != null && !warning.isEmpty()) {
      tvWarning.setText("⚠ " + warning);
      tvWarning.setVisibility(TextView.VISIBLE);
    } else {
      tvWarning.setVisibility(TextView.GONE);
    }

    btnAccept.setOnClickListener(v -> onAccept());
    btnDecline.setOnClickListener(v -> onDecline());

    log.log("UI инициализирован, кнопки навешены");
    log.log("══ onCreate END ══");
    // ★ НОВОЕ: регистрация OnBackInvokedCallback для API 33+.
    // Без этого «Назад» на экране входящего не отклоняет звонок
    // (система игнорирует onBackPressed при enableOnBackInvokedCallback="true").
    if (Build.VERSION.SDK_INT >= 33) {
      try {
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBackPressed);
        log.log("onCreate: OnBackInvokedCallback зарегистрирован");
      } catch (Throwable t) {
        log.warn("onCreate: registerOnBackInvokedCallback: " + t.getMessage());
      }
    }
  }

  /**
   * ВАЖНО: если в манифесте у IncomingCallActivity стоит android:launchMode="singleTask" или "singleTop", Android не будет создавать новый экземпляр при повторном вызове — вместо onCreate вызовется onNewIntent. Без этого метода новый callerIp/offerJson будут проигнорированы, и звонок уйдёт не туда.
   */
  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    log.log("══ onNewIntent START ══");
    log.log("onNewIntent action=" + intent.getAction());
    dumpExtras(intent, "onNewIntent");

    // Обязательно: чтобы getIntent() дальше возвращал новый Intent
    setIntent(intent);

    // Быстрое отклонение
    if (ACTION_DECLINE.equals(intent.getAction())) {
      log.log("onNewIntent: ACTION_DECLINE");
      readFieldsFromIntent(intent);
      sendBye("declined");
      new IncomingCallNotifier(this).cancel();
      finish();
      log.log("══ onNewIntent END (decline) ══");
      return;
    }

    // Обычный новый звонок: обновляем поля и UI
    readFieldsFromIntent(intent);

    try {
      TextView tvName = findViewById(R.id.tv_caller_name);
      TextView tvIp = findViewById(R.id.tv_caller_ip);
      TextView tvWarning = findViewById(R.id.tv_warning);

      if (tvName != null)
        tvName.setText(callerName != null ? callerName : "Неизвестный");

      if (tvIp != null) {
        // ★ ИЗМЕНЕНО: показываем режим
        StringBuilder ipLine = new StringBuilder();
        ipLine.append(callerIp).append(":").append(callerPort);
        if ("GATEWAY".equalsIgnoreCase(targetMode)) {
          ipLine.append("  [через шлюз]");
        } else if ("DIRECT".equalsIgnoreCase(targetMode)) {
          ipLine.append("  [напрямую]");
        }
        tvIp.setText(ipLine.toString());
      }

      if (tvWarning != null) {
        String warning = intent.getStringExtra(EXTRA_WARNING);
        if (warning != null && !warning.isEmpty()) {
          tvWarning.setText("⚠ " + warning);
          tvWarning.setVisibility(TextView.VISIBLE);
        } else {
          tvWarning.setVisibility(TextView.GONE);
        }
      }
    } catch (Exception e) {
      log.logError("onNewIntent: ошибка обновления UI", e);
    }

    log.log("══ onNewIntent END ══");
  }

  // ================= Кнопки =================

  /**
   * ★ ИЗМЕНЕНО: убран FLAG_ACTIVITY_NO_HISTORY.
   *
   * Было: NEW_TASK | NO_HISTORY. CallActivity не оставалась в истории — при возврате пользователя из фона она не восстанавливалась, и retained-движок (хотя и жив) не attach'ился к новой Activity.
   *
   * Стало: только NEW_TASK. CallActivity в истории; при возврате система поднимет её (onNewIntent → reuse движка в CallActivity.onCreate).
   */
  private void onAccept() {
    log.log("══ onAccept START ══");
    log.log("Принимаем звонок " + shortId(callId) + " от " + callerIp + ":" + callerPort + ", mode="
        + targetMode + ", gatewayIp=" + gatewayIp + ":" + gatewayPort + ", targetFingerprint="
        + targetFingerprint);

    if (callerIp == null || callerIp.isEmpty()) {
      log.error("callerIp пустой — не можем запустить CallActivity!");
      finish();
      return;
    }

    new IncomingCallNotifier(this).cancel();

    Intent callIntent = new Intent(this, CallActivity.class);
    callIntent.putExtra("call_id", callId);
    callIntent.putExtra("target_ip", callerIp);
    callIntent.putExtra("target_port", callerPort);
    callIntent.putExtra("incoming_offer_json", offerJson);
    callIntent.putExtra("is_incoming", true);
    callIntent.putExtra("target_mode", targetMode);
    callIntent.putExtra("target_gateway_ip", gatewayIp);
    callIntent.putExtra("target_gateway_port", gatewayPort);
    callIntent.putExtra("target_fingerprint", targetFingerprint);
    // ★ NEW_TASK без NO_HISTORY — CallActivity остаётся в истории
    callIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

    log.log("Запуск CallActivity: target_ip=" + callerIp + ", target_port=" + callerPort
        + ", call_id=" + shortId(callId) + ", is_incoming=true" + ", target_mode=" + targetMode
        + ", target_fingerprint=" + targetFingerprint + ", offerJson="
        + (offerJson != null ? offerJson.length() + " симв." : "null"));

    startActivity(callIntent);
    finish();
    log.log("══ onAccept END ══");
  }

  private void onDecline() {
    log.log("══ onDecline START ══");
    log.log("Отклоняем звонок " + shortId(callId) + " от " + callerIp + ":" + callerPort);
    sendBye("declined");
    new IncomingCallNotifier(this).cancel();
    finish();
    log.log("══ onDecline END ══");
  }

  // ================= Отправка bye =================

  /**
   * ★ ИЗМЕНЕНО: WD-ветка.
   *
   * Если targetMode == "WIFI_DIRECT" и wifiDirectManager.isConnected() — отправляем bye через WifiDirectManager.sendSignal (JSON-кадр в WD-сокет). Иначе — ConnectionManager (HTTP /signal/bye), как раньше.
   *
   * Причина: в WD-сети HTTP-IP собеседника из обычной Wi-Fi недоступен, ConnectionManager упал бы в таймаут, и собеседник не получил бы bye.
   */
  private void sendBye(String reason) {
    try {
      log.log("sendBye: callId=" + shortId(callId) + ", reason=" + reason + ", targetIp=" + callerIp
          + ":" + callerPort + ", targetMode=" + targetMode);

      if (callerIp == null || callerIp.isEmpty()) {
        log.error("sendBye: callerIp пустой — bye не отправить");
        return;
      }

      SecureAuthManager auth = new SecureAuthManager(this);
      String fp = auth.getDeviceFingerprint();
      if (fp == null)
        fp = "";

      JsonObject payload = new JsonObject();
      payload.addProperty("reason", reason);

      JsonObject obj = new JsonObject();
      obj.addProperty("type", "bye");
      obj.addProperty("from_device_id", DeviceInfoHelper.getDeviceId(this));
      obj.addProperty("from_fingerprint", fp);
      obj.addProperty("call_id", callId);
      obj.addProperty("timestamp", System.currentTimeMillis() / 1000);
      obj.add("payload", payload);

      String body = obj.toString();
      log.log("sendBye: body=" + body);

      // ★ НОВОЕ: WD-ветка
      if ("WIFI_DIRECT".equalsIgnoreCase(targetMode)) {
        if (wifiDirectManager != null && wifiDirectManager.isConnected()) {
          boolean queued = wifiDirectManager.sendSignal(body);
          log.log("sendBye: через Wi-Fi Direct, queued=" + queued);
          return;
        } else {
          log.warn("sendBye: targetMode=WIFI_DIRECT, но WD не подключён — fallback на HTTP");
        }
      }

      // HTTP-fallback (и обычный HTTP-режим)
      Intent svc = new Intent(this, ConnectionManager.class);
      svc.setAction(ConnectionManager.ACTION_SEND_SIGNAL);
      svc.putExtra(ConnectionManager.EXTRA_TARGET_IP, callerIp);
      svc.putExtra(ConnectionManager.EXTRA_TARGET_PORT, callerPort);
      svc.putExtra(ConnectionManager.EXTRA_SIGNAL_TYPE, "bye");
      svc.putExtra(ConnectionManager.EXTRA_SIGNAL_BODY, body);
      startForegroundService(svc);
      log.log("sendBye: bye отправлен на " + callerIp + ":" + callerPort);
    } catch (Exception e) {
      log.logError("Ошибка отправки bye", e);
    }
  }

  // ================= Back =================

  /**
   * ★ ИЗМЕНЕНО: на API 33+ при enableOnBackInvokedCallback="true" onBackPressed() НЕ вызывается — система использует OnBackInvokedDispatcher. Регистрируем callback в onCreate (см. ниже), который сводит всё в handleBackPressed(). onBackPressed() оставлен для API < 33.
   */
  @Override
  public void onBackPressed() {
    log.log("onBackPressed → onDecline");
    onDecline();
  }

  /** ★ НОВОЕ: обработчик системного back для API 33+ (через OnBackInvokedDispatcher). */
  private void handleBackPressed() {
    try {
      log.log("handleBackPressed → onDecline");
      onDecline();
    } catch (Exception e) {
      log.logError("handleBackPressed", e);
      finish();
    }
  }

  // ================= Хелперы =================

  /**
   * ★ ИЗМЕНЕНО: читает новые extras.
   */
  private void readFieldsFromIntent(Intent intent) {
    callId = intent.getStringExtra(EXTRA_CALL_ID);
    callerName = intent.getStringExtra(EXTRA_CALLER_NAME);
    callerIp = intent.getStringExtra(EXTRA_CALLER_IP);
    callerPort = intent.getIntExtra(EXTRA_CALLER_PORT, 8081);
    offerJson = intent.getStringExtra(EXTRA_OFFER_JSON);

    // ★ НОВЫЕ
    targetMode = intent.getStringExtra(EXTRA_TARGET_MODE);
    if (targetMode == null || targetMode.isEmpty()) {
      targetMode = "AUTO";
    }
    gatewayIp = intent.getStringExtra(EXTRA_GATEWAY_IP);
    gatewayPort = intent.getIntExtra(EXTRA_GATEWAY_PORT, 8083);
    targetFingerprint = intent.getStringExtra(EXTRA_TARGET_FINGERPRINT);

    log.log("readFieldsFromIntent: callId=" + shortId(callId) + ", callerName=" + callerName
        + ", callerIp=" + callerIp + ", callerPort=" + callerPort + ", targetMode=" + targetMode
        + ", gatewayIp=" + gatewayIp + ", gatewayPort=" + gatewayPort + ", targetFingerprint="
        + targetFingerprint + ", offerJson="
        + (offerJson != null ? offerJson.length() + " симв." : "null"));
  }

  private void dumpExtras(Intent intent, String where) {
    Bundle extras = intent.getExtras();
    if (extras == null) {
      log.log("[" + where + "] Intent extras: null");
      return;
    }
    StringBuilder sb = new StringBuilder();
    for (String key : extras.keySet()) {
      sb.append(key).append("=").append(extras.get(key)).append(", ");
    }
    log.log("[" + where + "] Intent extras: " + sb);
  }

  private String shortId(String id) {
    if (id == null)
      return "?";
    return id.length() > 8 ? id.substring(0, 8) : id;
  }
}