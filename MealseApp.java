package com.my.mealse;

import android.app.Application;
import android.content.Intent;
import android.content.IntentFilter;
import android.icu.text.SimpleDateFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import com.google.gson.JsonObject;
import com.my.mealse.messenger.MessengerEvents;
import com.my.mealse.messenger.receiver.MessageReceiver;
import com.my.mealse.messenger.transport.DeliveryScheduler;
import com.my.mealse.service.ServerService;
import com.my.mealse.util.BroadcastUtils;
import com.my.mealse.util.CallStateManager;
import com.my.mealse.util.DeviceInfoHelper;
import com.my.mealse.util.MealseLogger;
import com.my.mealse.util.SecureAuthManager;
import com.my.mealse.util.WifiDirectManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Date;

/**
 * Application-класс Mealse.
 *
 * Отвечает за: - инициализацию логирования - запрос разрешения MANAGE_EXTERNAL_STORAGE - глобальный обработчик непойманных исключений - singleton WifiDirectManager на всё приложение - подписка на входящие сигналы Wi-Fi Direct - ★ Шаг 11: регистрация MessageReceiver + старт DeliveryScheduler - ★ Улучшение №4: file_chunk (WD-файл чанками) → broadcast ACTION_SIGNAL → MessageReceiver.handleFileChunk собирает чанки.
 */
public class MealseApp extends Application {

  private static final String TAG = "MealseApp";
  private static final int REQ_MANAGE_STORAGE = 1001;

  private WifiDirectManager wifiDirectManager;
  private WifiDirectManager.Listener wifiDirectIncomingListener;

  private MessageReceiver messageReceiver;

  @Override
  public void onCreate() {
    super.onCreate();

    MealseLogger.resetAll();

    MealseLogger log = MealseLogger.get(this, "App");
    log.log("════════════════════════════════════════════");
    log.log("Mealse запущено");
    log.log("SDK: " + Build.VERSION.SDK_INT);
    log.log("Устройство: " + Build.MANUFACTURER + " " + Build.MODEL);
    log.log("Log dir: " + MealseLogger.getLogDirPath(this));
    log.log("════════════════════════════════════════════");

    checkStoragePermission(log);
    installCrashHandler();
    MealseLogger.installCrashHandler(this);
    
    try {
      wifiDirectManager = new WifiDirectManager(this);
      log.log("WifiDirectManager создан: supported=" + wifiDirectManager.isWifiDirectSupported());
    } catch (Exception e) {
      log.logError("Ошибка создания WifiDirectManager", e);
      wifiDirectManager = null;
    }

    if (wifiDirectManager != null) {
      subscribeToWifiDirectIncoming(log);
    }

    if (wifiDirectManager != null) {
      try {
        com.my.mealse.filemanager.wifidirect.WifiDirectFileServer.get(getApplicationContext());
        log.log("WifiDirectFileServer запущен");
      } catch (Exception e) {
        log.logError("Ошибка запуска WifiDirectFileServer", e);
      }
    }

    initMessenger(log);
    MealseLogger.startLogcatCapture(this);
  }

  // ============================================================
  // Инициализация мессенджера
  // ============================================================

  private void initMessenger(MealseLogger log) {
    log.log("════ initMessenger START ════");

    // 1. Очистка зависших .part-файлов от предыдущих сессий
    cleanupIncomingParts(log);

    // 2. Регистрация MessageReceiver
    try {
      if (messageReceiver == null) {
        messageReceiver = new MessageReceiver();
      }
      IntentFilter filter = new IntentFilter(ServerService.ACTION_SIGNAL);
      BroadcastUtils.registerNotExported(this, messageReceiver, filter);
      log.log("✓ MessageReceiver зарегистрирован на " + ServerService.ACTION_SIGNAL);
    } catch (Exception e) {
      log.logError("Ошибка регистрации MessageReceiver", e);
      messageReceiver = null;
    }

    // 3. Запуск DeliveryScheduler
    try {
      DeliveryScheduler.get(this).start();
      log.log("✓ DeliveryScheduler запущен");
    } catch (Exception e) {
      log.logError("Ошибка запуска DeliveryScheduler", e);
    }

    try {
      boolean dbReady = com.my.mealse.messenger.data.DatabaseHolder.isInitialized();
      log.log("Messenger DB initialized=" + dbReady);
    } catch (Exception ignored) {
    }

    log.log("════ initMessenger END ════");
  }

  /**
   * ★ Улучшение №4: удаляет зависшие .part-файлы в getCacheDir()/incoming/. .part создаётся MessageReceiver.handleFileChunk и удаляется после успешной сборки (moveFromFile) или таймаута. Если приложение упало / процесс убит — .part остаётся. Чистим при старте.
   */
  private void cleanupIncomingParts(MealseLogger log) {
    try {
      File incomingDir = new File(getCacheDir(), "incoming");
      if (!incomingDir.exists())
        return;
      File[] files = incomingDir.listFiles();
      if (files == null)
        return;
      int deleted = 0;
      for (File f : files) {
        if (f.isFile() && f.getName().endsWith(".part")) {
          if (f.delete())
            deleted++;
        }
      }
      if (deleted > 0) {
        log.log("cleanupIncomingParts: удалено " + deleted + " .part-файлов");
      }
    } catch (Exception e) {
      log.warn("cleanupIncomingParts: " + e.getMessage());
    }
  }

  // ============================================================
  // Wi-Fi Direct listener
  // ============================================================

  private void subscribeToWifiDirectIncoming(MealseLogger log) {
    log.log("════ subscribeToWifiDirectIncoming START ════");

    wifiDirectIncomingListener = new WifiDirectManager.Listener() {
      @Override
      public void onPeersChanged(List<WifiDirectManager.Peer> peers) {
      }

      @Override
      public void onDiscoveryStarted() {
      }

      @Override
      public void onDiscoveryStopped() {
      }

      @Override
      public void onConnected(WifiDirectManager.Peer remote, boolean isGroupOwner) {
      }

      @Override
      public void onDisconnected() {
      }

      @Override
      public void onSignal(String json) {
        MealseLogger l = MealseLogger.get(getApplicationContext(), "MealseApp");
        l.log("📩 MealseApp.wifiDirectIncomingListener.onSignal: "
            + (json != null ? json.length() + " симв." : "null"));

        try {
          com.my.mealse.util.NodeEnricher.enrichFromWifiDirect(getApplicationContext(),
              wifiDirectManager);
        } catch (Exception e) {
          l.logError("enrichFromWifiDirect", e);
        }

        handleWifiDirectSignal(json);
      }

      @Override
      public void onError(String message) {
        MealseLogger l = MealseLogger.get(getApplicationContext(), "MealseApp");
        l.warn("MealseApp.wifiDirectIncomingListener.onError: " + message);
      }
    };

    wifiDirectManager.addListener(wifiDirectIncomingListener);
    log.log("subscribeToWifiDirectIncoming: подписка выполнена, listenerCount="
        + wifiDirectManager.getListenerCount());
    log.log("════ subscribeToWifiDirectIncoming END ════");
  }

  /**
   * Обработка входящего сигнала Wi-Fi Direct.
   *
   * ★ ИСПРАВЛЕНО (шаг 17.1): - hello / hello_ack — игнор. - file_chunk — ТОЛЬКО прямой вызов MessageReceiver (broadcast упадёт с TransactionTooLargeException: JSON-чанк 512 КБ ≈ 700К символов не влезает в Binder ~1 МБ). - message — ТОЛЬКО прямой вызов MessageReceiver (убираем двойной вызов ack DELIVERED/READ; broadcast + direct давали дубли). - offer — IncomingCallActivity (только если звонок не активен). - candidate / answer / bye — broadcast ACTION_SIGNAL для CallActivity.signalReceiver (если звонок активен); иначе игнор.
   */
  private void handleWifiDirectSignal(String json) {
    MealseLogger log = MealseLogger.get(this, "MealseApp");

    log.log("════ handleWifiDirectSignal START ════");
    log.log("  json.length=" + (json != null ? json.length() : -1));
    log.log("  json (первые 300): "
        + (json != null && json.length() > 300 ? json.substring(0, 300) + "..." : json));

    try {
      JsonObject obj = new com.google.gson.Gson().fromJson(json, JsonObject.class);
      if (obj == null) {
        log.warn("handleWifiDirectSignal: obj == null — выходим");
        return;
      }
      if (!obj.has("type")) {
        log.warn("handleWifiDirectSignal: нет поля type — выходим");
        return;
      }

      String type = obj.get("type").getAsString();
      log.log("handleWifiDirectSignal: type=" + type);

      // ============================================================
      // hello / hello_ack — служебные
      // ============================================================
      if ("hello".equals(type) || "hello_ack".equals(type)) {
        log.log("handleWifiDirectSignal: служебный " + type + " — игнорирую");
        return;
      }

      // ============================================================
      // message — ТОЛЬКО прямой вызов (без broadcast)
      // ============================================================
      if ("message".equals(type)) {
        log.log("handleWifiDirectSignal: message → direct (без broadcast)");
        try {
          Intent directIntent = new Intent(ServerService.ACTION_SIGNAL);
          directIntent.putExtra(ServerService.EXTRA_SIGNAL_JSON, json);
          new com.my.mealse.messenger.receiver.MessageReceiver().onReceive(this, directIntent);
          log.log("✓ handleWifiDirectSignal: MessageReceiver.onReceive (message) выполнен");
        } catch (Exception e) {
          log.logError("handleWifiDirectSignal: прямой вызов MessageReceiver (message)", e);
        }

        // retryFor для отложенных исходящих, адресованных этому пиру
        try {
          WifiDirectManager.Peer remote = wifiDirectManager != null
              ? wifiDirectManager.getRemotePeer()
              : null;
          if (remote != null && remote.deviceAddress != null) {
            String nodeKey = remote.deviceAddress + ":0";
            DeliveryScheduler.get(this).retryFor(nodeKey);
          }
        } catch (Exception e) {
          log.logError("handleWifiDirectSignal: retryFor", e);
        }
        return;
      }

      // ============================================================
      // file_chunk — ТОЛЬКО прямой вызов (broadcast > Binder лимита)
      // ============================================================
      if ("file_chunk".equals(type)) {
        log.log("handleWifiDirectSignal: file_chunk → direct (без broadcast)");
        try {
          Intent directIntent = new Intent(ServerService.ACTION_SIGNAL);
          directIntent.putExtra(ServerService.EXTRA_SIGNAL_JSON, json);
          new com.my.mealse.messenger.receiver.MessageReceiver().onReceive(this, directIntent);
          log.log("✓ handleWifiDirectSignal: MessageReceiver.onReceive (file_chunk) выполнен");
        } catch (Exception e) {
          log.logError("handleWifiDirectSignal: прямой вызов MessageReceiver (file_chunk)", e);
        }
        return;
      }

      // ============================================================
      // candidate / answer / bye — broadcast для CallActivity.signalReceiver
      // ============================================================
      if ("candidate".equals(type) || "answer".equals(type) || "bye".equals(type)) {
        CallStateManager csm = new CallStateManager(this);
        if (!csm.isCallActive()) {
          log.log("handleWifiDirectSignal: " + type + " — нет активного звонка, игнорирую");
          return;
        }
        log.log("handleWifiDirectSignal: " + type
            + " → broadcast ACTION_SIGNAL для CallActivity.signalReceiver");
        try {
          Intent bc = new Intent(ServerService.ACTION_SIGNAL);
          bc.putExtra(ServerService.EXTRA_SIGNAL_JSON, json);
          bc.setPackage(getPackageName());
          sendBroadcast(bc);
          log.log("✓ handleWifiDirectSignal: broadcast ACTION_SIGNAL (" + type + ") отправлен");
        } catch (Exception e) {
          log.logError("handleWifiDirectSignal: broadcast " + type, e);
        }
        return;
      }

      // ============================================================
      // offer — только сейчас идёт логика IncomingCallActivity
      // ============================================================
      if (!"offer".equals(type)) {
        log.warn("handleWifiDirectSignal: неизвестный type=" + type + " — игнор");
        return;
      }

      log.log("handleWifiDirectSignal: ✓ получен offer — обрабатываю");

      CallStateManager csm = new CallStateManager(this);
      boolean callActive = csm.isCallActive();
      String activeCallId = csm.getActiveCallId();
      log.log("  CallStateManager: isCallActive=" + callActive + ", activeCallId="
          + shortId(activeCallId));

      if (callActive) {
        boolean callActivityAlive = isCallActivityAlive();
        log.log("  CallStateManager: callActivityAlive=" + callActivityAlive);

        if (!callActivityAlive) {
          log.warn("handleWifiDirectSignal: флаг звонка висит, но CallActivity мёртв — сбрасываю");
          csm.clearCallActive();
        } else {
          log.warn("handleWifiDirectSignal: ❌ звонок уже активен (" + shortId(activeCallId)
              + ") — игнорирую offer");
          return;
        }
      }

      String callId = obj.has("call_id") && !obj.get("call_id").isJsonNull()
          ? obj.get("call_id").getAsString()
          : null;
      String fromDeviceId = obj.has("from_device_id") && !obj.get("from_device_id").isJsonNull()
          ? obj.get("from_device_id").getAsString()
          : null;
      String fromFingerprint = obj.has("from_fingerprint")
          && !obj.get("from_fingerprint").isJsonNull() ? obj.get("from_fingerprint").getAsString()
              : null;

      log.log("  callId=" + shortId(callId));
      log.log("  fromDeviceId=" + fromDeviceId);
      log.log("  fromFingerprint=" + fromFingerprint);

      if (callId == null || callId.isEmpty()) {
        log.warn("handleWifiDirectSignal: offer без call_id — игнорирую");
        return;
      }

      WifiDirectManager.Peer remote = wifiDirectManager != null ? wifiDirectManager.getRemotePeer()
          : null;
      log.log("  remotePeer=" + remote);

      String callerName = (remote != null && remote.deviceName != null) ? remote.deviceName
          : (fromDeviceId != null
              ? "Устройство " + fromDeviceId.substring(0, Math.min(6, fromDeviceId.length()))
              : "Неизвестный");

      String callerIp = (remote != null && remote.deviceAddress != null) ? remote.deviceAddress
          : "0.0.0.0";
      int callerPort = 0;

      log.log("  callerName=" + callerName);
      log.log("  callerIp=" + callerIp);
      log.log("  callerPort=" + callerPort);

      log.log("handleWifiDirectSignal: → IncomingCallActivity.newIntent(...)");

      Intent incoming = IncomingCallActivity.newIntent(this, callId, callerName, callerIp,
          callerPort, json, // offer_json
          null, // warning
          "WIFI_DIRECT", // target_mode
          null, // gatewayIp
          0, // gatewayPort
          fromFingerprint); // targetFingerprint

      log.log("  Intent создан: action=" + incoming.getAction() + ", call_id="
          + shortId(incoming.getStringExtra("call_id")) + ", caller_ip="
          + incoming.getStringExtra("caller_ip") + ", target_mode="
          + incoming.getStringExtra("target_mode"));

      log.log("handleWifiDirectSignal: → startActivity(incoming)");

      try {
        startActivity(incoming);
        log.log("✓ handleWifiDirectSignal: startActivity выполнен успешно");
      } catch (Exception e) {
        log.logError("❌ handleWifiDirectSignal: startActivity FAILED", e);
      }

    } catch (Exception e) {
      log.logError("handleWifiDirectSignal: критическая ошибка", e);
    }

    log.log("════ handleWifiDirectSignal END ════");
  }

  private boolean isCallActivityAlive() {
    try {
      CallStateManager csm = new CallStateManager(this);
      String callId = csm.getActiveCallId();
      if (callId == null)
        return false;
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private String shortId(String id) {
    if (id == null)
      return "?";
    return id.length() > 8 ? id.substring(0, 8) : id;
  }

  public WifiDirectManager getWifiDirectManager() {
    return wifiDirectManager;
  }

  private void checkStoragePermission(MealseLogger log) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      log.log("SDK < 30: MANAGE_EXTERNAL_STORAGE не требуется");
      return;
    }

    try {
      boolean hasAccess = Environment.isExternalStorageManager();
      log.log("MANAGE_EXTERNAL_STORAGE: " + (hasAccess ? "✅ разрешено" : "❌ НЕ разрешено"));

      if (!hasAccess) {
        log.warn("Открываю системные настройки для разрешения доступа ко всем файлам");
        openManageStorageSettings(log);
      } else {
        log.log("Логи будут писаться в: " + MealseLogger.getLogDirPath(this));
      }
    } catch (Exception e) {
      log.logError("Ошибка проверки MANAGE_EXTERNAL_STORAGE", e);
    }
  }

  private void openManageStorageSettings(MealseLogger log) {
    try {
      Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
      intent.setData(Uri.parse("package:" + getPackageName()));
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      startActivity(intent);
      log.log("Открыты настройки для " + getPackageName());
    } catch (Exception e) {
      log.logError("ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION не сработал", e);
      try {
        Intent fallback = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
        fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(fallback);
        log.log("Открыт общий экран MANAGE_ALL_FILES_ACCESS_PERMISSION");
      } catch (Exception e2) {
        log.logError("И fallback не сработал", e2);
      }
    }
  }

  private void installCrashHandler() {
    final Thread.UncaughtExceptionHandler defaultHandler = Thread
        .getDefaultUncaughtExceptionHandler();

    Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
      // ★ ИСПРАВЛЕНО: crash-лог пишем напрямую в файл, не через MealseLogger.get(),
      // чтобы не зависеть от instances / closeAll / resolveLogDir-кэша.
      // Fallback-цепочка: публичная папка → app-specific external → internal.
      try {
        writeCrashLogDirect(thread, throwable);
      } catch (Throwable ignored) {
      }

      // Дублируем в logcat — на случай, если файл не создался.
      try {
        Log.e("MealseCrash", "💥 Непойманное исключение в потоке '" + thread.getName() + "'",
            throwable);
      } catch (Throwable ignored) {
      }

      // Пытаемся штатно закрыть WifiDirectManager (не критично, если упадёт).
      try {
        if (wifiDirectManager != null) {
          wifiDirectManager.close();
        }
      } catch (Exception ignored) {
      }

      // Закрываем writer'ы MealseLogger (crash-лог уже записан напрямую).
      try {
        MealseLogger.closeAll();
      } catch (Exception ignored) {
      }

      if (defaultHandler != null) {
        defaultHandler.uncaughtException(thread, throwable);
      }
    });
  }

  /**
   * ★ НОВОЕ: пишет crash-лог напрямую в файл, минуя MealseLogger.
   *
   * Цепочка директорий: 1. /storage/emulated/0/MealseLogs/crash.log (если есть MANAGE_EXTERNAL_STORAGE) 2. getExternalFilesDir()/MealseLogs/crash.log 3. getFilesDir()/MealseLogs/crash.log
   *
   * Формат: заголовок с timestamp + поток + stacktrace (PrintWriter на StringWriter, затем write в FileWriter с append=true + flush + fsync через FileOutputStream.getFD().sync()).
   *
   * Никаких исключений наружу — всё глотается, потому что мы уже в обработчике краха.
   */
  private void writeCrashLogDirect(Thread thread, Throwable throwable) {
    File logFile = null;
    try {
      logFile = resolveCrashLogFile();
    } catch (Throwable ignored) {
    }
    if (logFile == null) {
      return;
    }

    // Формируем текст crash-лога
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    try {
      pw.println();
      pw.println("════════════════════════════════════════════");
      pw.println("💥 CRASH " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
          .format(new Date()));
      pw.println("Thread: " + thread.getName() + " (id=" + thread.getId() + ")");
      pw.println(
          "Device: " + Build.MANUFACTURER + " " + Build.MODEL + ", SDK=" + Build.VERSION.SDK_INT);
      pw.println("App: " + getPackageName());
      pw.println("────────────────────────────────────────────");
      if (throwable != null) {
        throwable.printStackTrace(pw);
      } else {
        pw.println("throwable == null");
      }
      pw.println("════════════════════════════════════════════");
      pw.flush();
    } catch (Throwable ignored) {
    }

    String text = sw.toString();

    // Пишем с append=true и fsync, чтобы данные точно попали на диск
    // до того, как defaultHandler убьёт процесс.
    FileOutputStream fos = null;
    try {
      fos = new FileOutputStream(logFile, true);
      fos.write(text.getBytes(StandardCharsets.UTF_8));
      fos.flush();
      try {
        fos.getFD().sync();
      } catch (Throwable ignored) {
      }
    } catch (Throwable ignored) {
    } finally {
      if (fos != null) {
        try {
          fos.close();
        } catch (Throwable ignored) {
        }
      }
    }
  }

  /**
   * ★ НОВОЕ: выбирает файл для crash-лога по цепочке директорий. Возвращает null, если ни одна директория не доступна для записи.
   */
  private File resolveCrashLogFile() {
    String fileName = "crash.log";

    // 1. Публичная папка (нужен MANAGE_EXTERNAL_STORAGE)
    try {
      File root = Environment.getExternalStorageDirectory();
      if (root != null) {
        File dir = new File(root, "MealseLogs");
        if (dir.exists() || dir.mkdirs()) {
          File f = new File(dir, fileName);
          if (canWriteCrash(f)) {
            return f;
          }
        }
      }
    } catch (Throwable ignored) {
    }

    // 2. App-specific external
    try {
      File ext = getExternalFilesDir(null);
      if (ext != null) {
        File dir = new File(ext, "MealseLogs");
        if (dir.exists() || dir.mkdirs()) {
          File f = new File(dir, fileName);
          if (canWriteCrash(f)) {
            return f;
          }
        }
      }
    } catch (Throwable ignored) {
    }

    // 3. Internal
    try {
      File dir = new File(getFilesDir(), "MealseLogs");
      if (dir.exists() || dir.mkdirs()) {
        File f = new File(dir, fileName);
        if (canWriteCrash(f)) {
          return f;
        }
      }
    } catch (Throwable ignored) {
    }

    return null;
  }

  /**
   * ★ НОВОЕ: проверка, что в файл можно писать (открыть на append).
   */
  private boolean canWriteCrash(File f) {
    if (f == null) {
      return false;
    }
    FileOutputStream test = null;
    try {
      test = new FileOutputStream(f, true);
      return true;
    } catch (Throwable t) {
      return false;
    } finally {
      if (test != null) {
        try {
          test.close();
        } catch (Throwable ignored) {
        }
      }
    }
  }

  @Override
  public void onTerminate() {
    try {
      if (messageReceiver != null) {
        // BroadcastUtils.unregisterSafely(this, messageReceiver);
        messageReceiver = null;
      }
    } catch (Exception ignored) {
    }
    try {
      DeliveryScheduler.get(this).stop();
    } catch (Exception ignored) {
    }
    try {
      com.my.mealse.messenger.data.DatabaseHolder.close();
    } catch (Exception ignored) {
    }

    try {
      if (wifiDirectManager != null) {
        if (wifiDirectIncomingListener != null) {
          wifiDirectManager.removeListener(wifiDirectIncomingListener);
          wifiDirectIncomingListener = null;
        }
        wifiDirectManager.close();
        wifiDirectManager = null;
      }
    } catch (Exception ignored) {
    }

    MealseLogger.closeAll();
    super.onTerminate();
  }
}