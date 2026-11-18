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

  // ================= Фабричный метод =================

  /**
   * ★ РАСШИРЕННЫЙ: полная сигнатура с режимом и fingerprint.
   */
  public static Intent newIntent(Context context, String callId, String callerName,
                                 String callerIp, int callerPort,
                                 String offerJson, String warning,
                                 String targetMode, String gatewayIp,
                                 int gatewayPort, String targetFingerprint) {
    Intent i = new Intent(context, IncomingCallActivity.class);
    i.putExtra(EXTRA_CALL_ID, callId);
    i.putExtra(EXTRA_CALLER_NAME, callerName);
    i.putExtra(EXTRA_CALLER_IP, callerIp);
    i.putExtra(EXTRA_CALLER_PORT, callerPort);
    i.putExtra(EXTRA_OFFER_JSON, offerJson);
    i.putExtra(EXTRA_WARNING, warning);
    // ★ НОВЫЕ extras
    i.putExtra(EXTRA_TARGET_MODE, targetMode);
    i.putExtra(EXTRA_GATEWAY_IP, gatewayIp);
    i.putExtra(EXTRA_GATEWAY_PORT, gatewayPort);
    i.putExtra(EXTRA_TARGET_FINGERPRINT, targetFingerprint);
    // Только NEW_TASK — без CLEAR_TOP, чтобы не переиспользовать старый экземпляр
    // с устаревшими extras (иначе callerIp может оказаться от предыдущего звонка).
    i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    return i;
  }

  /**
   * ★ ОБРАТНАЯ СОВМЕСТИМОСТЬ: старая сигнатура без режима.
   * Использует дефолтные AUTO, null, 8083, null.
   */
  @Deprecated
  public static Intent newIntent(Context context, String callId, String callerName,
                                 String callerIp, int callerPort,
                                 String offerJson, String warning) {
    return newIntent(context, callId, callerName, callerIp, callerPort,
        offerJson, warning, "AUTO", null, 8083, null);
  }

  // ================= Lifecycle =================

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    log = MealseLogger.get(this, TAG);
    log.log("══ onCreate START ══");

    // Показ поверх lock screen
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      setShowWhenLocked(true);
      setTurnScreenOn(true);
      log.log("setShowWhenLocked(true), setTurnScreenOn(true) (API >= 27)");
    } else {
      getWindow().addFlags(
          WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
              | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
              | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
      log.log("FLAG_SHOW_WHEN_LOCKED | TURN_SCREEN_ON | KEEP_SCREEN_ON (API < 27)");
    }

    Intent intent = getIntent();
    log.log("Intent action=" + intent.getAction());
    dumpExtras(intent, "onCreate");

    readFieldsFromIntent(intent);

    if (callerIp == null || callerIp.isEmpty()) {
      log.error("КРИТИЧНО: callerIp пустой! В CallActivity уйдёт target_ip=null и звонок не отправится.");
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

    // ★ ИЗМЕНЕНО: показываем режим в строке адреса, если он GATEWAY
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
  }

  /**
   * ВАЖНО: если в манифесте у IncomingCallActivity стоит
   * android:launchMode="singleTask" или "singleTop", Android не будет
   * создавать новый экземпляр при повторном вызове — вместо onCreate
   * вызовется onNewIntent. Без этого метода новый callerIp/offerJson
   * будут проигнорированы, и звонок уйдёт не туда.
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

      if (tvName != null) tvName.setText(callerName != null ? callerName : "Неизвестный");

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

  private void onAccept() {
    log.log("══ onAccept START ══");
    log.log("Принимаем звонок " + shortId(callId)
        + " от " + callerIp + ":" + callerPort
        + ", mode=" + targetMode
        + ", gatewayIp=" + gatewayIp
        + ":" + gatewayPort
        + ", targetFingerprint=" + targetFingerprint);

    if (callerIp == null || callerIp.isEmpty()) {
      log.error("callerIp пустой — не можем запустить CallActivity!");
      finish();
      return;
    }

    new IncomingCallNotifier(this).cancel();

    // ★ РАСШИРЕНО: передаём режим и параметры шлюза
    Intent callIntent = new Intent(this, CallActivity.class);
    callIntent.putExtra("call_id", callId);
    callIntent.putExtra("target_ip", callerIp);
    callIntent.putExtra("target_port", callerPort);
    callIntent.putExtra("incoming_offer_json", offerJson);
    callIntent.putExtra("is_incoming", true);
    // ★ НОВЫЕ extras
    callIntent.putExtra("target_mode", targetMode);
    callIntent.putExtra("target_gateway_ip", gatewayIp);
    callIntent.putExtra("target_gateway_port", gatewayPort);
    callIntent.putExtra("target_fingerprint", targetFingerprint);
    callIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_HISTORY);

    log.log("Запуск CallActivity: target_ip=" + callerIp
        + ", target_port=" + callerPort
        + ", call_id=" + shortId(callId)
        + ", is_incoming=true"
        + ", target_mode=" + targetMode
        + ", target_fingerprint=" + targetFingerprint
        + ", offerJson=" + (offerJson != null ? offerJson.length() + " симв." : "null"));

    startActivity(callIntent);
    finish();
    log.log("══ onAccept END ══");
  }

  private void onDecline() {
    log.log("══ onDecline START ══");
    log.log("Отклоняем звонок " + shortId(callId)
        + " от " + callerIp + ":" + callerPort);
    sendBye("declined");
    new IncomingCallNotifier(this).cancel();
    finish();
    log.log("══ onDecline END ══");
  }

  // ================= Отправка bye =================

  private void sendBye(String reason) {
    try {
      log.log("sendBye: callId=" + shortId(callId)
          + ", reason=" + reason
          + ", targetIp=" + callerIp + ":" + callerPort);

      if (callerIp == null || callerIp.isEmpty()) {
        log.error("sendBye: callerIp пустой — bye не отправить");
        return;
      }

      SecureAuthManager auth = new SecureAuthManager(this);
      String fp = auth.getDeviceFingerprint();
      if (fp == null) fp = "";

      JsonObject payload = new JsonObject();
      payload.addProperty("reason", reason);

      JsonObject obj = new JsonObject();
      obj.addProperty("type", "bye");
      obj.addProperty("from_device_id", DeviceInfoHelper.getDeviceId(this));
      obj.addProperty("from_fingerprint", fp);
      obj.addProperty("call_id", callId);
      obj.addProperty("timestamp", System.currentTimeMillis() / 1000);
      obj.add("payload", payload);

      log.log("sendBye: body=" + obj);

      Intent svc = new Intent(this, ConnectionManager.class);
      svc.setAction(ConnectionManager.ACTION_SEND_SIGNAL);
      svc.putExtra(ConnectionManager.EXTRA_TARGET_IP, callerIp);
      svc.putExtra(ConnectionManager.EXTRA_TARGET_PORT, callerPort);
      svc.putExtra(ConnectionManager.EXTRA_SIGNAL_TYPE, "bye");
      svc.putExtra(ConnectionManager.EXTRA_SIGNAL_BODY, obj.toString());
      startForegroundService(svc);
      log.log("sendBye: bye отправлен на " + callerIp + ":" + callerPort);
    } catch (Exception e) {
      log.logError("Ошибка отправки bye", e);
    }
  }

  // ================= Back =================

  @Override
  public void onBackPressed() {
    log.log("onBackPressed → трактуем как «отклонить»");
    onDecline();
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

    log.log("readFieldsFromIntent: callId=" + shortId(callId)
        + ", callerName=" + callerName
        + ", callerIp=" + callerIp
        + ", callerPort=" + callerPort
        + ", targetMode=" + targetMode
        + ", gatewayIp=" + gatewayIp
        + ", gatewayPort=" + gatewayPort
        + ", targetFingerprint=" + targetFingerprint
        + ", offerJson=" + (offerJson != null ? offerJson.length() + " симв." : "null"));
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
    if (id == null) return "?";
    return id.length() > 8 ? id.substring(0, 8) : id;
  }
}