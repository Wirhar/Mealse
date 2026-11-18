package com.my.mealse;

import android.os.Bundle;
//noinspection java.unusedImport
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import com.my.mealse.filemanager.server.RemoteFsService;
import com.my.mealse.util.FileAccessManager;

import com.my.mealse.adapter.NodeAdapter;
import com.my.mealse.adapter.NodeAdapter.NodeStatus;
import com.my.mealse.model.TrustedNode;
import com.my.mealse.service.ServerService;
import com.my.mealse.service.TunnelVpnService;
import com.my.mealse.util.BroadcastUtils;
import com.my.mealse.util.CallStateManager;
import com.my.mealse.util.DeviceInfoHelper;
import com.my.mealse.util.MealseLogger;
import com.my.mealse.util.NodeEnricher;
import com.my.mealse.util.NodeManager;
import com.my.mealse.util.NodeStatusChecker;
import com.my.mealse.util.SecureAuthManager;
import com.my.mealse.util.WifiDirectManager;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends BaseActivity {

  private static final String TAG = "MainActivity";

  // ===== Интервалы =====
  private static final long POLL_INTERVAL_MS = 2000;
  private static final long NODE_STATUS_POLL_INTERVAL_MS = 5000;
  private static final long FS_START_TIMEOUT_MS = 2500;

  // ===== Preferences =====
  private static final String PREF_APP = "mealse_app_prefs";
  private static final String KEY_STORAGE_DIALOG_SHOWN = "storage_dialog_shown";

  private static final String PREF_TUNNEL = "mealse_tunnel_prefs";
  private static final String KEY_TUNNEL_MODE = "mode";
  private static final String KEY_TUNNEL_GATEWAY_IP = "gateway_ip";
  private static final String KEY_TUNNEL_GATEWAY_PORT = "gateway_port";
  private static final String KEY_TUNNEL_LISTEN_PORT = "listen_port";

  // ===== Логгер =====
  private MealseLogger log;

  // ===== Views: шапка =====
  private ImageButton btnBrowser;
  private ImageButton btnSettings;

  // ===== Views: статусы =====
  private TextView tvStatusServer, tvStatusFiles, tvStatusCall;
  private TextView tvStatusVpn, tvStatusWifiDirect;

  private ImageButton btnServerToggle, btnAddNode;
  private ImageButton btnFilesToggle, btnFilesManager;
  private ImageButton btnCallAction, btnCheckNode;
  private ImageButton btnVpnToggle, btnVpnSettings;
  private ImageButton btnWifiDirectToggle, btnWifiDirectSettings;

  // ===== Views: список узлов =====
  private ListView nodesListView;

  // ===== Managers =====
  private NodeManager nodeManager;
  private CallStateManager callStateManager;
  private NodeStatusChecker nodeStatusChecker;
  private NodeAdapter nodeAdapter;
  private WifiDirectManager wifiDirectManager;
  private FileAccessManager fileAccessManager;

  // ===== State =====
  private TrustedNode selectedNode = null;
  private String serverState = "stopped";
  private boolean isCalling = false;
  private boolean receiversRegistered = false;
  private boolean storagePermissionDialogShown = false;
  private boolean pingInProgress = false;

  // VPN state
  private boolean vpnRunning = false;
  private String vpnMode = null;

  // Wi-Fi Direct state
  private boolean wifiDirectDiscovering = false;
  private WifiDirectManager.Listener wifiDirectUiListener;

  // Files (RemoteFsService) state
  private boolean fsRunning = false;
  private boolean fsStarting = false;

  // ===== Handlers =====
  private final Handler pollHandler = new Handler(Looper.getMainLooper());
  private final Handler statusPollHandler = new Handler(Looper.getMainLooper());
  private final Handler uiHandler = new Handler(Looper.getMainLooper());

  private final Runnable pollRunnable = new Runnable() {
    @Override
    public void run() {
      try {
        updateCallStatus();
        updateWifiDirectNodeStatus();
      } catch (Exception e) {
        logError("Ошибка polling звонка", e);
      }
      pollHandler.postDelayed(this, POLL_INTERVAL_MS);
    }
  };

  private final Runnable statusPollRunnable = new Runnable() {
    @Override
    public void run() {
      try {
        checkAllNodesStatus();
      } catch (Exception e) {
        logError("Ошибка polling статусов узлов", e);
      }
      statusPollHandler.postDelayed(this, NODE_STATUS_POLL_INTERVAL_MS);
    }
  };

  /** Сброс флага fsStarting, если сервис не подтвердил запуск. */
  private final Runnable fsStartTimeoutRunnable = () -> {
    if (fsStarting) {
      fsStarting = false;
      updateFilesUI();
    }
  };

  // ============================================================
  // Broadcast receivers
  // ============================================================

  private final BroadcastReceiver serverStateReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      try {
        String state = intent.getStringExtra(ServerService.EXTRA_STATE);
        String message = intent.getStringExtra(ServerService.EXTRA_MESSAGE);
        log.log("📩 Server broadcast: state=" + state + ", msg=" + message);
        serverState = state != null ? state : "stopped";
        updateServerUI();
      } catch (Exception e) {
        logError("Ошибка в serverStateReceiver", e);
      }
    }
  };

  private final BroadcastReceiver vpnStateReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      try {
        String action = intent.getAction();
        if (!TunnelVpnService.ACTION_STATUS.equals(action))
          return;

        boolean running = intent.getBooleanExtra(TunnelVpnService.EXTRA_RUNNING, false);
        String message = intent.getStringExtra(TunnelVpnService.EXTRA_MESSAGE);
        String mode = intent.getStringExtra(TunnelVpnService.EXTRA_MODE);

        log.log("📩 VPN broadcast: running=" + running + ", mode=" + mode + ", msg=" + message);

        vpnRunning = running;
        vpnMode = mode;

        try {
          SharedPreferences.Editor ed = getSharedPreferences(PREF_TUNNEL, MODE_PRIVATE).edit();
          if (running && mode != null && !mode.isEmpty()) {
            ed.putString(KEY_TUNNEL_MODE, mode);
          }
          ed.apply();
        } catch (Exception e) {
          log.warn("Не удалось записать VPN mode в prefs: " + e.getMessage());
        }

        updateVpnUI();
      } catch (Exception e) {
        logError("Ошибка в vpnStateReceiver", e);
      }
    }
  };

  private final BroadcastReceiver fsStateReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      try {
        String action = intent.getAction();
        if (!RemoteFsService.ACTION_FS_STATE.equals(action))
          return;

        String state = intent.getStringExtra(RemoteFsService.EXTRA_STATE);
        String message = intent.getStringExtra(RemoteFsService.EXTRA_MESSAGE);

        log.log("📩 FS broadcast: state=" + state + ", msg=" + message);

        if (RemoteFsService.STATE_RUNNING.equals(state)) {
          fsRunning = true;
          fsStarting = false;
          uiHandler.removeCallbacks(fsStartTimeoutRunnable);
        } else if (RemoteFsService.STATE_STOPPED.equals(state)) {
          fsRunning = false;
          fsStarting = false;
          uiHandler.removeCallbacks(fsStartTimeoutRunnable);
        } else if (RemoteFsService.STATE_ERROR.equals(state)) {
          fsRunning = false;
          fsStarting = false;
          uiHandler.removeCallbacks(fsStartTimeoutRunnable);
          Toast
              .makeText(MainActivity.this,
                  "Ошибка сервера файлов: " + (message != null ? message : ""), Toast.LENGTH_LONG)
              .show();
        }

        updateFilesUI();
      } catch (Exception e) {
        logError("Ошибка в fsStateReceiver", e);
      }
    }
  };

  // ============================================================
  // Lifecycle
  // ============================================================

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    log = MealseLogger.get(this, TAG);
    log.log("══ onCreate START ══");
    log.log("SDK=" + Build.VERSION.SDK_INT + ", Log dir=" + MealseLogger.getLogDirPath(this));

    try {
      setContentView(R.layout.activity_main);

      nodeManager = new NodeManager(this);
      fileAccessManager = new FileAccessManager(this);
      callStateManager = new CallStateManager(this);
      wifiDirectManager = ((MealseApp) getApplication()).getWifiDirectManager();

      nodeStatusChecker = new NodeStatusChecker();

      initViews();
      setupClickListeners();
      loadNodeList();
      subscribeWifiDirectUiListener();

      // Сразу запросим статус RemoteFsService (мог быть запущен из BackgroundReceiver)
      queryFsServiceStatus();

      log.log("══ onCreate END ══");

    } catch (Exception e) {
      logError("КРИТИЧЕСКАЯ ОШИБКА в onCreate", e);
      Toast.makeText(this, "Ошибка запуска: " + e.getMessage(), Toast.LENGTH_LONG).show();
      finish();
    }
  }

  private void initViews() {
    log.log("  initViews START");

    btnBrowser = findViewById(R.id.btn_browser);
    btnSettings = findViewById(R.id.btn_settings);

    tvStatusServer = findViewById(R.id.tv_status_server);
    tvStatusFiles = findViewById(R.id.tv_status_files);
    tvStatusCall = findViewById(R.id.tv_status_call);
    tvStatusVpn = findViewById(R.id.tv_status_vpn);
    tvStatusWifiDirect = findViewById(R.id.tv_status_wifi_direct);

    btnServerToggle = findViewById(R.id.btn_server_toggle);
    btnAddNode = findViewById(R.id.btn_add_node);
    btnFilesToggle = findViewById(R.id.btn_files_toggle);
    btnFilesManager = findViewById(R.id.btn_files_manager);
    btnCallAction = findViewById(R.id.btn_call_action);
    btnCheckNode = findViewById(R.id.btn_check_node);
    btnVpnToggle = findViewById(R.id.btn_vpn_toggle);
    btnVpnSettings = findViewById(R.id.btn_vpn_settings);
    btnWifiDirectToggle = findViewById(R.id.btn_wifidirect_toggle);
    btnWifiDirectSettings = findViewById(R.id.btn_wifidirect_settings);

    nodesListView = findViewById(R.id.nodes_list_view);

    checkViewNotNull(btnBrowser, "btn_browser");
    checkViewNotNull(btnSettings, "btn_settings");
    checkViewNotNull(tvStatusServer, "tv_status_server");
    checkViewNotNull(tvStatusFiles, "tv_status_files");
    checkViewNotNull(tvStatusCall, "tv_status_call");
    checkViewNotNull(tvStatusVpn, "tv_status_vpn");
    checkViewNotNull(tvStatusWifiDirect, "tv_status_wifi_direct");
    checkViewNotNull(btnServerToggle, "btn_server_toggle");
    checkViewNotNull(btnAddNode, "btn_add_node");
    checkViewNotNull(btnFilesToggle, "btn_files_toggle");
    checkViewNotNull(btnFilesManager, "btn_files_manager");
    checkViewNotNull(btnCallAction, "btn_call_action");
    checkViewNotNull(btnCheckNode, "btn_check_node");
    checkViewNotNull(btnVpnToggle, "btn_vpn_toggle");
    checkViewNotNull(btnVpnSettings, "btn_vpn_settings");
    checkViewNotNull(btnWifiDirectToggle, "btn_wifidirect_toggle");
    checkViewNotNull(btnWifiDirectSettings, "btn_wifidirect_settings");
    checkViewNotNull(nodesListView, "nodes_list_view");

    log.log("  initViews END: все View найдены");
  }

  private void checkViewNotNull(Object view, String id) {
    if (view == null) {
      String msg = "View не найден: " + id + " — проверь activity_main.xml";
      log.error(msg);
      throw new IllegalStateException(msg);
    }
  }

  private void setupClickListeners() {
    log.log("  setupClickListeners START");

    btnBrowser.setOnClickListener(v -> {
      log.log("Клик: btnBrowser → показать меню");
      showBrowserMenu(v);
    });

    btnSettings.setOnClickListener(v -> {
      log.log("Клик: btnSettings");
      openSettings();
    });

    btnServerToggle.setOnClickListener(v -> {
      log.log("Клик: btnServerToggle");
      onServerToggleClicked();
    });

    btnAddNode.setOnClickListener(v -> {
      log.log("Клик: btnAddNode → показать данные устройства");
      showMyDeviceInfo();
    });

    btnFilesToggle.setOnClickListener(v -> {
      log.log("Клик: btnFilesToggle");
      onFilesToggleClicked();
    });
    btnFilesManager.setOnClickListener(v -> {
      log.log("Клик: btnFilesManager → открываю FileManagerActivity");
      try {
        Intent i = new Intent(this, com.my.mealse.filemanager.ui.FileManagerActivity.class);
        startActivity(i);
      } catch (Exception e) {
        logError("Ошибка открытия FileManagerActivity", e);
        Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show();
      }
    });

    if (tvStatusFiles != null) {
      tvStatusFiles.setOnClickListener(v -> showFileAccessDialog());
    }

    btnCallAction.setOnClickListener(v -> {
      log.log("Клик: btnCallAction");
      onCallClicked();
    });
    btnCheckNode.setOnClickListener(v -> {
      log.log("Клик: btnCheckNode");
      onCheckNodeClicked();
    });

    btnVpnToggle.setOnClickListener(v -> {
      log.log("Клик: btnVpnToggle");
      onVpnToggleClicked();
    });
    btnVpnSettings.setOnClickListener(v -> {
      log.log("Клик: btnVpnSettings");
      openTunnelActivity();
    });

    btnWifiDirectToggle.setOnClickListener(v -> {
      log.log("Клик: btnWifiDirectToggle");
      onWifiDirectToggleClicked();
    });
    btnWifiDirectSettings.setOnClickListener(v -> {
      log.log("Клик: btnWifiDirectSettings");
      openWifiDirectActivity();
    });

    nodesListView.setOnItemClickListener((parent, view, position, id) -> {
      try {
        TrustedNode node = nodeAdapter.getItem(position);
        log.log("Клик по строке узла: " + node.getName() + " (key=" + node.getKey() + ")");
        showNodeActionsDialog(node);
      } catch (Exception e) {
        logError("Ошибка OnItemClick", e);
      }
    });

    nodesListView.setOnItemLongClickListener((parent, view, position, id) -> {
      try {
        TrustedNode node = nodeAdapter.getItem(position);
        log.log("Долгое нажатие на узел: " + node.getName() + " (key=" + node.getKey() + ")");
        showNodeActionsDialog(node);
        return true;
      } catch (Exception e) {
        logError("Ошибка OnItemLongClick", e);
        return false;
      }
    });

    log.log("  setupClickListeners END");
  }

  // ============================================================
  // Меню: Браузер + Сообщения (без дублей браузерных пунктов)
  // ============================================================

  /**
   * Показывает PopupMenu: 1. 🌐 Браузер  2. 💬 Сообщения
   *
   * ★ Убраны пункты «История», «Сохранённые сайты», «Пароли» —
   *   они доступны внутри BrowserActivity (дублировались).
   */
  private void showBrowserMenu(View anchor) {
    try {
      PopupMenu popup = new PopupMenu(this, anchor);
      popup.getMenu().add(0, 1, 0, "🌐 Браузер");
      popup.getMenu().add(0, 2, 1, "💬 Сообщения");

      popup.setOnMenuItemClickListener(item -> {
        switch (item.getItemId()) {
          case 1:
            openBrowser();
            return true;
          case 2:
            openMessenger();
            return true;
          default:
            return false;
        }
      });

      popup.show();
      log.log("showBrowserMenu: PopupMenu показан (Браузер, Сообщения)");
    } catch (Exception e) {
      logError("showBrowserMenu", e);
      toast("Ошибка открытия меню: " + e.getMessage());
    }
  }

  private void openBrowser() {
    log.log("openBrowser → запуск BrowserActivity");
    try {
      Intent i = new Intent(this, com.my.mealse.browser.BrowserActivity.class);
      startActivity(i);
    } catch (Exception e) {
      logError("Ошибка запуска BrowserActivity", e);
      toast("Ошибка: " + e.getMessage());
    }
  }

  /**
   * ★ Открыть мессенджер (список диалогов).
   */
  private void openMessenger() {
    log.log("openMessenger → запуск ChatsListActivity");
    try {
      Intent i = new Intent(this, com.my.mealse.messenger.ui.ChatsListActivity.class);
      startActivity(i);
    } catch (Exception e) {
      logError("Ошибка запуска ChatsListActivity", e);
      toast("Ошибка: " + e.getMessage());
    }
  }

  /** Универсальный тост. */
  private void toast(String message) {
    try {
      Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    } catch (Throwable ignored) {
    }
  }

  // ============================================================
  // onResume / onPause / onDestroy
  // ============================================================

  @Override
  protected void onResume() {
    super.onResume();
    log.log("══ onResume START ══");

    try {
      checkStoragePermission();

      if (!receiversRegistered) {
        log.log("Регистрация receivers");
        IntentFilter serverFilter = new IntentFilter(ServerService.ACTION_SERVER_STATE);
        IntentFilter vpnFilter = new IntentFilter(TunnelVpnService.ACTION_STATUS);
        IntentFilter fsFilter = new IntentFilter(RemoteFsService.ACTION_FS_STATE);

        BroadcastUtils.registerNotExported(this, fsStateReceiver, fsFilter);
        BroadcastUtils.registerNotExported(this, serverStateReceiver, serverFilter);
        BroadcastUtils.registerNotExported(this, vpnStateReceiver, vpnFilter);
        receiversRegistered = true;
        log.log("Receivers зарегистрированы (SDK=" + Build.VERSION.SDK_INT + ")");
      }

      queryFsServiceStatus();

      selectedNode = null;
      if (nodeAdapter != null) {
        nodeAdapter.setSelectedKey(null);
      }

      log.log("Запуск polling статуса звонка");
      pollHandler.post(pollRunnable);

      log.log("loadNodeList");
      loadNodeList();

      log.log("Запуск polling статусов узлов");
      startNodeStatusPolling();

      log.log("refreshServiceStates");
      refreshServiceStates();

      log.log("updateCallStatus");
      updateCallStatus();

      log.log("refreshVpnState");
      refreshVpnState();

      log.log("refreshWifiDirectState");
      refreshWifiDirectState();

      log.log("updateWifiDirectNodeStatus");
      updateWifiDirectNodeStatus();

      log.log("══ onResume END ══");

    } catch (Exception e) {
      logError("Ошибка в onResume", e);
    }
  }

  @Override
  protected void onPause() {
    log.log("onPause START");

    try {
      pollHandler.removeCallbacks(pollRunnable);
      stopNodeStatusPolling();

      if (receiversRegistered) {
        BroadcastUtils.unregisterSafely(this, serverStateReceiver);
        BroadcastUtils.unregisterSafely(this, vpnStateReceiver);
        BroadcastUtils.unregisterSafely(this, fsStateReceiver);
        receiversRegistered = false;
        log.log("Receivers сняты");
      }
    } catch (Exception e) {
      logError("Ошибка в onPause", e);
    }

    log.log("onPause END");
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    log.log("onDestroy START");

    try {
      statusPollHandler.removeCallbacks(statusPollRunnable);
      pollHandler.removeCallbacks(pollRunnable);
      uiHandler.removeCallbacks(fsStartTimeoutRunnable);

      if (nodeStatusChecker != null) {
        nodeStatusChecker.shutdown();
      }

      unsubscribeWifiDirectUiListener();
    } catch (Exception e) {
      logError("Ошибка в onDestroy", e);
    }

    log.log("onDestroy END");
    super.onDestroy();
  }

  // ============================================================
  // Навигация
  // ============================================================

  private void openSettings() {
    try {
      log.log("Открываю SettingsActivity");
      Intent intent = new Intent(this, SettingsActivity.class);
      startActivity(intent);
    } catch (Exception e) {
      logError("Ошибка открытия SettingsActivity", e);
    }
  }

  private void openTunnelActivity() {
    try {
      log.log("Открываю TunnelActivity");
      Intent intent = new Intent(this, TunnelActivity.class);
      startActivity(intent);
    } catch (Exception e) {
      logError("Ошибка открытия TunnelActivity", e);
    }
  }

  private void openWifiDirectActivity() {
    try {
      log.log("Открываю WifiDirectActivity");
      Intent intent = new Intent(this, WifiDirectActivity.class);
      startActivity(intent);
    } catch (Exception e) {
      logError("Ошибка открытия WifiDirectActivity", e);
      Toast.makeText(this, "Не удалось открыть Wi-Fi Direct: " + e.getMessage(), Toast.LENGTH_LONG)
          .show();
    }
  }

  // ============================================================
  // Диалог «Узлы с доступом к файлам»
  // ============================================================

  private void showFileAccessDialog() {
    try {
      if (nodeManager == null || fileAccessManager == null)
        return;

      List<TrustedNode> all = nodeManager.getAllNodes();
      if (all.isEmpty()) {
        new AlertDialog.Builder(this).setTitle("Доступ к файлам")
            .setMessage("В списке нет узлов.\n\nДобавьте узел в настройках или "
                + "подключитесь по Wi-Fi Direct.")
            .setPositiveButton("OK", null).show();
        return;
      }

      List<TrustedNode> granted = new ArrayList<>();
      List<TrustedNode> denied = new ArrayList<>();
      for (TrustedNode n : all) {
        if (n.isFileAccessGranted())
          granted.add(n);
        else
          denied.add(n);
      }
      List<TrustedNode> ordered = new ArrayList<>();
      ordered.addAll(granted);
      ordered.addAll(denied);

      String[] labels = new String[ordered.size()];
      for (int i = 0; i < ordered.size(); i++) {
        TrustedNode n = ordered.get(i);
        String icon = n.isFileAccessGranted() ? "🔓" : "🔒";
        labels[i] = icon + " " + n.getName() + " — " + n.getKey();
      }

      AlertDialog.Builder b = new AlertDialog.Builder(this);
      b.setTitle("Доступ к файлам (" + granted.size() + "/" + ordered.size() + ")");
      b.setItems(labels, (d, w) -> toggleFileAccess(ordered.get(w)));
      b.setNeutralButton("Закрыть", null);
      b.show();
    } catch (Exception e) {
      logError("showFileAccessDialog", e);
    }
  }

  private void toggleFileAccess(TrustedNode node) {
    if (node == null || fileAccessManager == null)
      return;
    boolean currently = node.isFileAccessGranted();

    if (currently) {
      new AlertDialog.Builder(this).setTitle("Отозвать доступ?")
          .setMessage("Узел «" + node.getName() + "» больше не сможет "
              + "просматривать и менять файлы в приложении.")
          .setPositiveButton("Отозвать", (d, w) -> {
            fileAccessManager.revoke(node.getKey());
            Toast.makeText(this, "Доступ отозван", Toast.LENGTH_SHORT).show();
            loadNodeList();
            updateFilesUI();
          }).setNegativeButton("Отмена", null).show();
    } else {
      new AlertDialog.Builder(this).setTitle("Разрешить доступ?")
          .setMessage("Узел «" + node.getName() + "» сможет просматривать "
              + "и менять файлы во внутреннем хранилище приложения.")
          .setPositiveButton("Разрешить", (d, w) -> {
            fileAccessManager.grant(node.getKey());
            Toast.makeText(this, "Доступ разрешён", Toast.LENGTH_SHORT).show();
            loadNodeList();
            updateFilesUI();
          }).setNegativeButton("Отмена", null).show();
    }
  }

  // ============================================================
  // Показ данных устройства
  // ============================================================

  private void showMyDeviceInfo() {
    log.log("showMyDeviceInfo START");
    try {
      String ip = DeviceInfoHelper.getLocalIpAddress(this);
      String deviceId = DeviceInfoHelper.getDeviceId(this);
      String fingerprint = new SecureAuthManager(this).getDeviceFingerprint();
      String name = android.os.Build.MODEL;
      String networkSummary = DeviceInfoHelper.getNetworkInfoSummary(this);

      StringBuilder sb = new StringBuilder();
      sb.append("Имя: ").append(name).append("\n");
      sb.append("IP-адрес: ").append(ip != null ? ip : "—").append("\n");
      sb.append("ID устройства: ").append(deviceId != null ? deviceId : "—").append("\n");
      sb.append("Fingerprint: ").append(fingerprint != null ? fingerprint : "недоступен")
          .append("\n");
      sb.append("\n── Сеть ──\n").append(networkSummary != null ? networkSummary : "(нет данных)");

      sb.append("\n── Разрешённые узлы (доступ к файлам) ──\n");
      try {
        List<TrustedNode> all = nodeManager.getAllNodes();
        int total = 0, allowed = 0;
        for (TrustedNode n : all) {
          total++;
          if (n.isFileAccessGranted()) {
            allowed++;
            sb.append("• ").append(n.getName()).append(" — ").append(n.getKey()).append("\n");
          }
        }
        if (allowed == 0)
          sb.append("(нет)\n");
        sb.append("Итого разрешено: ").append(allowed).append(" из ").append(total).append("\n");
      } catch (Exception e) {
        sb.append("(ошибка чтения)\n");
      }

      sb.append("\n── Сервисы ──\n");
      sb.append("Server (8081): ").append(isServiceRunning(ServerService.class) ? "🟢" : "⚪")
          .append("\n");
      sb.append("Files (8084): ").append(isServiceRunning(RemoteFsService.class) ? "🟢" : "⚪")
          .append("\n");
      sb.append("VPN (8083): ").append(isServiceRunning(TunnelVpnService.class) ? "🟢" : "⚪")
          .append("\n");

      final String info = sb.toString();

      AlertDialog.Builder builder = new AlertDialog.Builder(this);
      builder.setTitle("Данные этого устройства");

      EditText editText = new EditText(this);
      editText.setText(info);
      editText.setSingleLine(false);
      editText.setMinLines(14);
      editText.setGravity(android.view.Gravity.TOP);
      editText.setPadding(16, 16, 16, 16);
      editText.setFocusable(true);
      editText.setFocusableInTouchMode(true);
      editText.setClickable(true);
      editText.setLongClickable(true);
      editText.setTextIsSelectable(true);

      builder.setView(editText);

      builder.setPositiveButton("Копировать", (d, w) -> {
        try {
          android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(
              Context.CLIPBOARD_SERVICE);
          if (clipboard != null) {
            android.content.ClipData clip = android.content.ClipData.newPlainText("Mealse данные",
                info);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "Скопировано в буфер", Toast.LENGTH_SHORT).show();
          }
        } catch (Exception e) {
          logError("Ошибка копирования", e);
        }
        d.dismiss();
      });

      builder.setNeutralButton("Обновить", (d, w) -> {
        d.dismiss();
        showMyDeviceInfo();
      });

      builder.setNegativeButton("Закрыть", (d, w) -> d.dismiss());

      builder.show();

    } catch (Exception e) {
      logError("Ошибка showMyDeviceInfo", e);
    }
  }

  // ============================================================
  // Сервер (ServerService, порт 8081)
  // ============================================================

  private void onServerToggleClicked() {
    try {
      log.log("onServerToggleClicked: текущее состояние=" + serverState);

      if ("running".equals(serverState) || "starting".equals(serverState)) {
        log.log("Останавливаю сервер");
        stopService(new Intent(this, ServerService.class));
        serverState = "stopped";
        updateServerUI();
        Toast.makeText(this, "Сервер остановлен", Toast.LENGTH_SHORT).show();
      } else {
        log.log("Запускаю сервер");
        serverState = "starting";
        updateServerUI();

        Intent intent = new Intent(this, ServerService.class);
        startForegroundService(intent);
        Toast.makeText(this, "Сервер запускается...", Toast.LENGTH_SHORT).show();
      }
    } catch (Exception e) {
      logError("Ошибка onServerToggleClicked", e);
      serverState = "error";
      updateServerUI();
    }
  }

  // ============================================================
  // Файлы (RemoteFsService, порт 8084)
  // ============================================================

  private void onFilesToggleClicked() {
    try {
      log.log("onFilesToggleClicked: fsRunning=" + fsRunning + ", fsStarting=" + fsStarting);

      SharedPreferences prefs = getSharedPreferences("mealse_app_prefs", MODE_PRIVATE);

      if (fsRunning || fsStarting) {
        prefs.edit().putBoolean("fs_user_enabled", false).apply();
        fsStarting = false;
        fsRunning = false;
        uiHandler.removeCallbacks(fsStartTimeoutRunnable);
        updateFilesUI();
        Intent stop = new Intent(this, RemoteFsService.class);
        stop.setAction(RemoteFsService.ACTION_STOP);
        startForegroundService(stop);
        Toast.makeText(this, "Сервер файлов останавливается...", Toast.LENGTH_SHORT).show();
      } else {
        prefs.edit().putBoolean("fs_user_enabled", true).apply();
        fsStarting = true;
        updateFilesUI();
        Intent start = new Intent(this, RemoteFsService.class);
        start.setAction(RemoteFsService.ACTION_START);
        startForegroundService(start);
        Toast.makeText(this, "Сервер файлов запускается...", Toast.LENGTH_SHORT).show();
        uiHandler.postDelayed(fsStartTimeoutRunnable, FS_START_TIMEOUT_MS);
      }
    } catch (Exception e) {
      logError("Ошибка onFilesToggleClicked", e);
      fsStarting = false;
      updateFilesUI();
    }
  }

  private void queryFsServiceStatus() {
    try {
      SharedPreferences prefs = getSharedPreferences("mealse_app_prefs", MODE_PRIVATE);
      boolean userEnabled = prefs.getBoolean("fs_user_enabled", true);
      boolean actualRunning = isServiceRunning(RemoteFsService.class);

      if (!userEnabled) {
        fsRunning = false;
        log.log("queryFsServiceStatus: пользователь остановил — показываем stopped");
      } else {
        fsRunning = actualRunning;
      }

      updateFilesUI();
    } catch (Exception e) {
      logError("queryFsServiceStatus", e);
    }
  }

  // ============================================================
  // Звонок
  // ============================================================

  private void onCallClicked() {
    try {
      log.log("onCallClicked: isCalling=" + isCalling + ", selectedNode="
          + (selectedNode != null ? selectedNode.getName() : "null"));

      if (isCalling) {
        Toast.makeText(this, "Звонок уже активен", Toast.LENGTH_SHORT).show();
        return;
      }

      if (selectedNode == null) {
        Toast.makeText(this, "Выберите узел в списке (нажмите на галочку)", Toast.LENGTH_LONG)
            .show();
        return;
      }

      startCall();
    } catch (Exception e) {
      logError("Ошибка onCallClicked", e);
    }
  }

  private void onCheckNodeClicked() {
    try {
      log.log("onCheckNodeClicked");

      if (selectedNode == null) {
        Toast.makeText(this, "Выберите узел в списке (нажмите на галочку)", Toast.LENGTH_LONG)
            .show();
        return;
      }

      performNodeCheck(selectedNode);
    } catch (Exception e) {
      logError("Ошибка onCheckNodeClicked", e);
    }
  }

  // ============================================================
  // VPN
  // ============================================================

  private void onVpnToggleClicked() {
    try {
      log.log("onVpnToggleClicked: vpnRunning=" + vpnRunning + ", vpnMode=" + vpnMode);

      if (vpnRunning) {
        log.log("Останавливаю VPN");
        Intent intent = new Intent(this, TunnelVpnService.class);
        intent.setAction(TunnelVpnService.ACTION_STOP);
        startForegroundService(intent);
        Toast.makeText(this, "VPN останавливается...", Toast.LENGTH_SHORT).show();
        return;
      }

      SharedPreferences prefs = getSharedPreferences(PREF_TUNNEL, MODE_PRIVATE);
      String mode = prefs.getString(KEY_TUNNEL_MODE, "client");
      String gatewayIp = prefs.getString(KEY_TUNNEL_GATEWAY_IP, "");
      int gatewayPort = prefs.getInt(KEY_TUNNEL_GATEWAY_PORT, 8083);
      int listenPort = prefs.getInt(KEY_TUNNEL_LISTEN_PORT, 8083);

      log.log("VPN prefs: mode=" + mode + ", gatewayIp=" + gatewayIp + ", gatewayPort="
          + gatewayPort + ", listenPort=" + listenPort);

      Intent intent = new Intent(this, TunnelVpnService.class);
      intent.setAction(TunnelVpnService.ACTION_START);

      if (TunnelVpnService.MODE_SERVER.equals(mode)) {
        intent.putExtra(TunnelVpnService.EXTRA_MODE, TunnelVpnService.MODE_SERVER);
        intent.putExtra(TunnelVpnService.EXTRA_LISTEN_PORT, listenPort);
      } else {
        if (gatewayIp == null || gatewayIp.isEmpty()) {
          log.warn("VPN: mode=client, но gatewayIp пустой — открываю настройки");
          Toast.makeText(this, "Настройте шлюз в разделе VPN", Toast.LENGTH_LONG).show();
          openTunnelActivity();
          return;
        }
        intent.putExtra(TunnelVpnService.EXTRA_MODE, TunnelVpnService.MODE_CLIENT);
        intent.putExtra(TunnelVpnService.EXTRA_SERVER_IP, gatewayIp);
        intent.putExtra(TunnelVpnService.EXTRA_SERVER_PORT, gatewayPort);
      }

      startForegroundService(intent);
      Toast.makeText(this, "VPN запускается...", Toast.LENGTH_SHORT).show();

    } catch (Exception e) {
      logError("Ошибка onVpnToggleClicked", e);
      Toast.makeText(this, "Ошибка VPN: " + e.getMessage(), Toast.LENGTH_LONG).show();
    }
  }

  // ============================================================
  // Wi-Fi Direct
  // ============================================================

  private void subscribeWifiDirectUiListener() {
    if (wifiDirectManager == null) {
      log.warn("subscribeWifiDirectUiListener: wifiDirectManager == null");
      return;
    }
    if (wifiDirectUiListener != null) {
      log.log("subscribeWifiDirectUiListener: уже подписан");
      return;
    }

    wifiDirectUiListener = new WifiDirectManager.Listener() {
      @Override
      public void onPeersChanged(List<WifiDirectManager.Peer> peers) {
      }

      @Override
      public void onConnected(WifiDirectManager.Peer remote, boolean isGroupOwner) {
        runOnUiThread(() -> updateWifiDirectUI());
        try {
          com.my.mealse.util.NodeEnricher.enrichFromWifiDirect(getApplicationContext(),
              wifiDirectManager);
        } catch (Exception e) {
          log.warn("enrichFromWifiDirect: " + e.getMessage());
        }
      }

      @Override
      public void onDisconnected() {
        runOnUiThread(() -> updateWifiDirectUI());
      }

      @Override
      public void onSignal(String json) {
      }

      @Override
      public void onError(String message) {
        log.warn("Wi-Fi Direct onError: " + message);
      }

      @Override
      public void onDiscoveryStarted() {
        log.log("Wi-Fi Direct: onDiscoveryStarted");
        runOnUiThread(() -> {
          wifiDirectDiscovering = true;
          updateWifiDirectUI();
        });
      }

      @Override
      public void onDiscoveryStopped() {
        log.log("Wi-Fi Direct: onDiscoveryStopped");
        runOnUiThread(() -> {
          wifiDirectDiscovering = false;
          updateWifiDirectUI();
        });
      }
    };

    wifiDirectManager.addListener(wifiDirectUiListener);
    log.log("subscribeWifiDirectUiListener: подписан, listenerCount="
        + wifiDirectManager.getListenerCount());
  }

  private void unsubscribeWifiDirectUiListener() {
    if (wifiDirectManager == null || wifiDirectUiListener == null)
      return;
    try {
      wifiDirectManager.removeListener(wifiDirectUiListener);
      wifiDirectUiListener = null;
      log.log("unsubscribeWifiDirectUiListener: снят");
    } catch (Exception e) {
      logError("unsubscribeWifiDirectUiListener", e);
    }
  }

  private void onWifiDirectToggleClicked() {
    try {
      log.log("onWifiDirectToggleClicked: discovering=" + wifiDirectDiscovering + ", connected="
          + (wifiDirectManager != null && wifiDirectManager.isConnected()));

      if (wifiDirectManager == null) {
        Toast.makeText(this, "Wi-Fi Direct недоступен", Toast.LENGTH_SHORT).show();
        return;
      }

      if (!wifiDirectManager.isWifiDirectSupported()) {
        Toast.makeText(this, "Wi-Fi Direct не поддерживается устройством", Toast.LENGTH_LONG)
            .show();
        return;
      }

      if (!wifiDirectManager.isOpened()) {
        boolean ok = wifiDirectManager.open();
        if (!ok) {
          Toast.makeText(this, "Не удалось инициализировать Wi-Fi Direct", Toast.LENGTH_LONG)
              .show();
          return;
        }
        subscribeWifiDirectUiListener();
      }

      if (wifiDirectManager.isConnected()) {
        log.log("Wi-Fi Direct: активное соединение — отключаюсь");
        Toast.makeText(this, "Отключаю Wi-Fi Direct...", Toast.LENGTH_SHORT).show();
        disconnectWifiDirect();
        return;
      }

      if (wifiDirectDiscovering) {
        log.log("Wi-Fi Direct: останавливаю discovery");
        wifiDirectManager.stopDiscovery();
        return;
      }

      if (!wifiDirectManager.hasPermissions()) {
        Toast.makeText(this, "Нужны разрешения Wi-Fi Direct. Откройте управление Wi-Fi Direct",
            Toast.LENGTH_LONG).show();
        return;
      }

      log.log("Wi-Fi Direct: запускаю discovery");
      wifiDirectManager.startDiscovery();

    } catch (Exception e) {
      logError("Ошибка onWifiDirectToggleClicked", e);
      Toast.makeText(this, "Ошибка Wi-Fi Direct: " + e.getMessage(), Toast.LENGTH_LONG).show();
    }
  }

  private void disconnectWifiDirect() {
    log.log("disconnectWifiDirect START");

    try {
      unsubscribeWifiDirectUiListener();

      wifiDirectManager.forceRemoveGroup((success, message) -> {
        log.log("forceRemoveGroup: success=" + success + ", msg=" + message);

        try {
          wifiDirectManager.close();
          Thread.sleep(300);
        } catch (Exception ignored) {
        }

        boolean ok = wifiDirectManager.open();
        log.log("disconnectWifiDirect: reopen=" + ok);

        runOnUiThread(() -> {
          wifiDirectDiscovering = false;

          if (ok) {
            subscribeWifiDirectUiListener();
            Toast.makeText(this, "Wi-Fi Direct отключён", Toast.LENGTH_SHORT).show();
          } else {
            Toast.makeText(this, "Ошибка переоткрытия Wi-Fi Direct", Toast.LENGTH_LONG).show();
          }

          updateWifiDirectUI();
        });
      });
    } catch (Exception e) {
      logError("disconnectWifiDirect", e);
      runOnUiThread(() -> {
        updateWifiDirectUI();
        Toast.makeText(this, "Ошибка отключения: " + e.getMessage(), Toast.LENGTH_LONG).show();
      });
    }
  }

  private void refreshWifiDirectState() {
    try {
      if (wifiDirectManager == null) {
        wifiDirectDiscovering = false;
        updateWifiDirectUI();
        return;
      }
      updateWifiDirectUI();
    } catch (Exception e) {
      logError("refreshWifiDirectState", e);
    }
  }

  private void updateWifiDirectUI() {
    try {
      if (tvStatusWifiDirect == null)
        return;

      if (wifiDirectManager == null) {
        tvStatusWifiDirect.setText("🔴 Wi-Fi Direct — недоступен");
        tvStatusWifiDirect.setTextColor(0xFFE53E3E);
        if (btnWifiDirectToggle != null) {
          btnWifiDirectToggle.setEnabled(false);
          btnWifiDirectToggle.setImageResource(R.drawable.ic_wifi_direct);
        }
        return;
      }

      if (!wifiDirectManager.isWifiDirectSupported()) {
        tvStatusWifiDirect.setText("🔴 Wi-Fi Direct — не поддерживается");
        tvStatusWifiDirect.setTextColor(0xFFE53E3E);
        if (btnWifiDirectToggle != null) {
          btnWifiDirectToggle.setEnabled(false);
        }
        return;
      }

      boolean connected = wifiDirectManager.isConnected();
      if (connected) {
        WifiDirectManager.Peer rp = wifiDirectManager.getRemotePeer();
        String name = (rp != null && rp.deviceName != null) ? rp.deviceName : "пир";
        String role = wifiDirectManager.isGroupOwner() ? "GO" : "клиент";
        tvStatusWifiDirect.setText("🟢 Wi-Fi Direct — подключено: " + name + " (" + role + ")");
        tvStatusWifiDirect.setTextColor(0xFF38A169);
        if (btnWifiDirectToggle != null) {
          btnWifiDirectToggle.setEnabled(true);
          btnWifiDirectToggle.setImageResource(R.drawable.ic_stop);
        }
        return;
      }

      if (wifiDirectDiscovering) {
        tvStatusWifiDirect.setText("🟡 Wi-Fi Direct — обнаружение включено");
        tvStatusWifiDirect.setTextColor(0xFFDD6B20);
        if (btnWifiDirectToggle != null) {
          btnWifiDirectToggle.setEnabled(true);
          btnWifiDirectToggle.setImageResource(R.drawable.ic_stop);
        }
      } else {
        tvStatusWifiDirect.setText("⚪ Wi-Fi Direct — обнаружение выключено");
        tvStatusWifiDirect.setTextColor(0xFF718096);
        if (btnWifiDirectToggle != null) {
          btnWifiDirectToggle.setEnabled(true);
          btnWifiDirectToggle.setImageResource(R.drawable.ic_wifi_direct);
        }
      }
    } catch (Exception e) {
      logError("Ошибка updateWifiDirectUI", e);
    }
  }

  private void updateWifiDirectNodeStatus() {
    try {
      if (wifiDirectManager == null || nodeAdapter == null)
        return;

      boolean connected = wifiDirectManager.isConnected();
      WifiDirectManager.Peer remote = wifiDirectManager.getRemotePeer();
      String remoteDeviceId = (remote != null) ? remote.deviceId : null;

      List<TrustedNode> nodes = nodeManager.getAllNodes();
      for (TrustedNode n : nodes) {
        if (n.getMode() != TrustedNode.Mode.WIFI_DIRECT)
          continue;

        boolean matches = connected && remoteDeviceId != null
            && remoteDeviceId.equals(n.getDeviceId());

        NodeStatus status = matches ? NodeStatus.ONLINE : NodeStatus.UNKNOWN;
        nodeAdapter.updateStatus(n.getKey(), status);
      }
    } catch (Exception e) {
      logError("updateWifiDirectNodeStatus", e);
    }
  }

  // ============================================================
  // VPN state
  // ============================================================

  private void refreshVpnState() {
    try {
      boolean running = isServiceRunning(TunnelVpnService.class);
      log.log("refreshVpnState: TunnelVpnService running=" + running);
      vpnRunning = running;

      if (running) {
        String modeFromPrefs = null;
        try {
          SharedPreferences prefs = getSharedPreferences(PREF_TUNNEL, MODE_PRIVATE);
          modeFromPrefs = prefs.getString(KEY_TUNNEL_MODE, null);
        } catch (Exception e) {
          log.warn("refreshVpnState: не удалось прочитать tunnel prefs: " + e.getMessage());
        }

        if (modeFromPrefs != null && !modeFromPrefs.isEmpty()) {
          vpnMode = modeFromPrefs;
        } else {
          vpnMode = "unknown";
        }
      } else {
        vpnMode = null;
      }

      updateVpnUI();
    } catch (Exception e) {
      logError("Ошибка refreshVpnState", e);
    }
  }

  private void updateVpnUI() {
    try {
      if (tvStatusVpn == null)
        return;

      if (vpnRunning) {
        if (TunnelVpnService.MODE_SERVER.equals(vpnMode)) {
          tvStatusVpn.setText("🟢 VPN — сервер (шлюз)");
        } else if (TunnelVpnService.MODE_CLIENT.equals(vpnMode)) {
          tvStatusVpn.setText("🟢 VPN — клиент");
        } else {
          tvStatusVpn.setText("🟢 VPN — активен");
        }
        tvStatusVpn.setTextColor(0xFF38A169);
        if (btnVpnToggle != null) {
          btnVpnToggle.setImageResource(R.drawable.ic_stop);
        }
      } else {
        tvStatusVpn.setText("⚪ VPN — не подключён");
        tvStatusVpn.setTextColor(0xFF718096);
        if (btnVpnToggle != null) {
          btnVpnToggle.setImageResource(R.drawable.ic_play);
        }
      }
    } catch (Exception e) {
      logError("Ошибка updateVpnUI", e);
    }
  }

  // ============================================================
  // Polling статусов узлов
  // ============================================================

  private void startNodeStatusPolling() {
    try {
      statusPollHandler.removeCallbacks(statusPollRunnable);
      statusPollHandler.post(statusPollRunnable);
    } catch (Exception e) {
      logError("Ошибка запуска polling узлов", e);
    }
  }

  private void stopNodeStatusPolling() {
    try {
      statusPollHandler.removeCallbacks(statusPollRunnable);
    } catch (Exception e) {
      logError("Ошибка остановки polling узлов", e);
    }
  }

  private void checkAllNodesStatus() {
    if (nodeAdapter == null || nodeStatusChecker == null)
      return;

    List<TrustedNode> nodes = nodeManager.getAllNodes();
    if (nodes.isEmpty())
      return;

    for (TrustedNode node : nodes) {
      try {
        if (node.getMode() == TrustedNode.Mode.WIFI_DIRECT) {
          continue;
        }

        final TrustedNode nodeRef = node;
        nodeStatusChecker.check(node, (nodeKey, status) -> {
          try {
            if (nodeAdapter != null) {
              nodeAdapter.updateStatus(nodeKey, status);
            }
            if (status == NodeStatus.ONLINE) {
              NodeEnricher.enrich(MainActivity.this, nodeRef);
            }
          } catch (Exception e) {
            logError("Ошибка updateStatus", e);
          }
        });
      } catch (Exception e) {
        logError("Ошибка запуска проверки узла " + node.getName(), e);
      }
    }
  }

  // ============================================================
  // Разрешение хранилища
  // ============================================================

  private void checkStoragePermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
      return;

    boolean hasAccess;
    try {
      hasAccess = Environment.isExternalStorageManager();
    } catch (Exception e) {
      logError("Ошибка проверки isExternalStorageManager", e);
      return;
    }

    if (hasAccess) {
      String currentDir = MealseLogger.getLogDirPath(this);
      if (currentDir.contains("/Android/data/")) {
        MealseLogger.resetAll();
        log = MealseLogger.get(this, TAG);
      }
    } else {
      if (!storagePermissionDialogShown && !getSharedPreferences(PREF_APP, MODE_PRIVATE)
          .getBoolean(KEY_STORAGE_DIALOG_SHOWN, false)) {
        storagePermissionDialogShown = true;
        showStoragePermissionDialog();
      }
    }
  }

  private void showStoragePermissionDialog() {
    try {
      new AlertDialog.Builder(this).setTitle("Разрешение на доступ к файлам")
          .setMessage("Для сохранения логов приложения в папку /MealseLogs/ "
              + "требуется разрешение «Доступ ко всем файлам».\n\n"
              + "Нажмите «Открыть настройки», найдите Mealse в списке " + "и включите разрешение.")
          .setPositiveButton("Открыть настройки", (d, w) -> {
            markDialogShown();
            openStorageSettings();
          }).setNeutralButton("Напомнить позже", (d, w) -> d.dismiss())
          .setNegativeButton("Больше не показывать", (d, w) -> {
            markDialogShown();
            d.dismiss();
          }).setCancelable(false).show();
    } catch (Exception e) {
      logError("Ошибка показа диалога разрешения", e);
    }
  }

  private void markDialogShown() {
    getSharedPreferences(PREF_APP, MODE_PRIVATE).edit().putBoolean(KEY_STORAGE_DIALOG_SHOWN, true)
        .apply();
  }

  private void openStorageSettings() {
    try {
      Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
      intent.setData(Uri.parse("package:" + getPackageName()));
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      startActivity(intent);
    } catch (Exception e) {
      try {
        Intent fallback = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
        fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(fallback);
      } catch (Exception e2) {
        Toast.makeText(this, "Не удалось открыть настройки вручную:\n"
            + "Настройки → Приложения → Mealse → Разрешения", Toast.LENGTH_LONG).show();
      }
    }
  }

  // ============================================================
  // Статусы сервисов
  // ============================================================

  private void refreshServiceStates() {
    boolean serverRunning = isServiceRunning(ServerService.class);
    boolean fsRunningNow = isServiceRunning(RemoteFsService.class);

    serverState = serverRunning ? "running" : "stopped";
    fsRunning = fsRunningNow;
    if (fsRunningNow)
      fsStarting = false;

    updateServerUI();
    updateFilesUI();
  }

  private boolean isServiceRunning(Class<?> serviceClass) {
    try {
      ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
      if (manager == null)
        return false;
      for (ActivityManager.RunningServiceInfo service : manager
          .getRunningServices(Integer.MAX_VALUE)) {
        if (serviceClass.getName().equals(service.service.getClassName())) {
          return true;
        }
      }
      return false;
    } catch (Exception e) {
      logError("Ошибка isServiceRunning", e);
      return false;
    }
  }

  private void updateServerUI() {
    try {
      if (tvStatusServer == null)
        return;

      switch (serverState) {
        case "running":
          tvStatusServer.setText("🟢 Сервер — работает (порт 8081)");
          tvStatusServer.setTextColor(0xFF38A169);
          if (btnServerToggle != null) {
            btnServerToggle.setImageResource(R.drawable.ic_stop);
            btnServerToggle.setEnabled(true);
          }
          break;
        case "starting":
          tvStatusServer.setText("🟡 Сервер — запускается...");
          tvStatusServer.setTextColor(0xFFDD6B20);
          if (btnServerToggle != null)
            btnServerToggle.setEnabled(false);
          break;
        case "error":
          tvStatusServer.setText("🔴 Сервер — ошибка");
          tvStatusServer.setTextColor(0xFFE53E3E);
          if (btnServerToggle != null) {
            btnServerToggle.setImageResource(R.drawable.ic_play);
            btnServerToggle.setEnabled(true);
          }
          break;
        default:
          tvStatusServer.setText("⚪ Сервер — выключен");
          tvStatusServer.setTextColor(0xFF718096);
          if (btnServerToggle != null) {
            btnServerToggle.setImageResource(R.drawable.ic_play);
            btnServerToggle.setEnabled(true);
          }
          break;
      }
    } catch (Exception e) {
      logError("Ошибка updateServerUI", e);
    }
  }

  private void updateFilesUI() {
    try {
      if (tvStatusFiles == null)
        return;

      int granted = 0;
      try {
        if (fileAccessManager != null) {
          granted = fileAccessManager.getGrantedCount();
        }
      } catch (Exception ignored) {
      }

      if (fsStarting) {
        tvStatusFiles.setText("🟡 Файлы — запускается…");
        tvStatusFiles.setTextColor(0xFFDD6B20);
        if (btnFilesToggle != null) {
          btnFilesToggle.setImageResource(R.drawable.ic_stop);
          btnFilesToggle.setEnabled(true);
        }
      } else if (fsRunning) {
        if (granted > 0) {
          tvStatusFiles.setText(
              "🟢 Файлы — сервер (порт " + RemoteFsService.PORT + ", доступ: " + granted + ")");
        } else {
          tvStatusFiles.setText("🟡 Файлы — сервер, нет разрешённых узлов");
        }
        tvStatusFiles.setTextColor(granted > 0 ? 0xFF38A169 : 0xFFDD6B20);
        if (btnFilesToggle != null) {
          btnFilesToggle.setImageResource(R.drawable.ic_stop);
          btnFilesToggle.setEnabled(true);
        }
      } else {
        tvStatusFiles.setText("⚪ Файлы — сервер выключен");
        tvStatusFiles.setTextColor(0xFF718096);
        if (btnFilesToggle != null) {
          btnFilesToggle.setImageResource(R.drawable.ic_play);
          btnFilesToggle.setEnabled(true);
        }
      }
    } catch (Exception e) {
      logError("Ошибка updateFilesUI", e);
    }
  }

  private void updateCallStatus() {
    try {
      boolean active = callStateManager.isCallActive();
      StringBuilder sb = new StringBuilder();

      if (active) {
        String callId = callStateManager.getActiveCallId();
        String shortId = callId != null && callId.length() > 8 ? callId.substring(0, 8) + "..."
            : (callId != null ? callId : "?");
        sb.append("🟢 Звонок — активен (").append(shortId).append(")");
        isCalling = true;
      } else {
        sb.append("⚪ Звонок — не активен");
        isCalling = false;
      }

      if (tvStatusCall != null) {
        tvStatusCall.setText(sb.toString());
        tvStatusCall.setTextColor(active ? 0xFF38A169 : 0xFF718096);
      }
    } catch (Exception e) {
      logError("Ошибка updateCallStatus", e);
    }
  }

  // ============================================================
  // Действия с узлами
  // ============================================================

  private void showNodeActionsDialog(TrustedNode node) {
    try {
      log.log("showNodeActionsDialog: " + node.getName() + " (key=" + node.getKey() + ")");

      boolean granted = fileAccessManager != null && fileAccessManager.isGranted(node.getKey());
      String accessLabel = granted ? "🔓 Отозвать доступ к файлам" : "🔐 Разрешить доступ к файлам";

      String[] options = { "Проверить доступность", "Подключиться (позвонить)", accessLabel,
          "Настроить режим", "Редактировать", "Удалить" };

      new AlertDialog.Builder(this).setTitle("Действия с узлом: " + node.getName())
          .setItems(options, (d, w) -> {
            if (w == 0) {
              log.log("Действие: Проверить");
              selectedNode = node;
              if (nodeAdapter != null)
                nodeAdapter.setSelectedKey(node.getKey());
              performNodeCheck(node);
            } else if (w == 1) {
              log.log("Действие: Позвонить");
              selectedNode = node;
              if (nodeAdapter != null)
                nodeAdapter.setSelectedKey(node.getKey());
              startCall();
            } else if (w == 2) {
              log.log("Действие: доступ к файлам — " + (granted ? "отозвать" : "разрешить"));
              if (fileAccessManager != null) {
                if (granted) {
                  fileAccessManager.revoke(node.getKey());
                  Toast.makeText(this, "Доступ к файлам отозван", Toast.LENGTH_SHORT).show();
                } else {
                  fileAccessManager.grant(node.getKey());
                  Toast.makeText(this, "Доступ к файлам разрешён", Toast.LENGTH_SHORT).show();
                }
                loadNodeList();
                updateFilesUI();
              }
            } else if (w == 3) {
              log.log("Действие: Настроить режим");
              showNodeModeDialog(node);
            } else if (w == 4) {
              log.log("Действие: Редактировать");
              showAddNodeDialog(node);
            } else if (w == 5) {
              log.log("Действие: Удалить");
              showDeleteConfirmation(node);
            }
          }).setNegativeButton("Отмена", (d, w) -> d.dismiss()).show();
    } catch (Exception e) {
      logError("Ошибка showNodeActionsDialog", e);
    }
  }

  private void showNodeModeDialog(TrustedNode node) {
    log.log("showNodeModeDialog START: " + node.getName() + " (key=" + node.getKey() + ")"
        + ", текущий режим=" + node.getMode());

    try {
      View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_gateway, null);
      RadioGroup rgMode = dialogView.findViewById(R.id.rg_node_mode);
      RadioButton rbAuto = dialogView.findViewById(R.id.rb_mode_auto);
      RadioButton rbDirect = dialogView.findViewById(R.id.rb_mode_direct);
      RadioButton rbGateway = dialogView.findViewById(R.id.rb_mode_gateway);
      RadioButton rbWifiDirect = dialogView.findViewById(R.id.rb_mode_wifi_direct);
      LinearLayout gatewayFields = dialogView.findViewById(R.id.gateway_fields);
      EditText editGwIp = dialogView.findViewById(R.id.edit_gateway_ip);
      EditText editGwPort = dialogView.findViewById(R.id.edit_gateway_port);

      if (rgMode == null || rbAuto == null || rbDirect == null || rbGateway == null
          || rbWifiDirect == null || gatewayFields == null || editGwIp == null
          || editGwPort == null) {
        log.error("showNodeModeDialog: не найдены View в dialog_gateway.xml");
        Toast.makeText(this, "Ошибка разметки dialog_gateway.xml", Toast.LENGTH_LONG).show();
        return;
      }

      TrustedNode.Mode currentMode = node.getMode();
      if (currentMode == TrustedNode.Mode.DIRECT) {
        rbDirect.setChecked(true);
      } else if (currentMode == TrustedNode.Mode.GATEWAY) {
        rbGateway.setChecked(true);
      } else if (currentMode == TrustedNode.Mode.WIFI_DIRECT) {
        rbWifiDirect.setChecked(true);
      } else {
        rbAuto.setChecked(true);
      }

      editGwIp.setText(node.getGatewayIp() != null ? node.getGatewayIp() : "");
      editGwPort.setText(String.valueOf(node.getGatewayPort() > 0 ? node.getGatewayPort() : 8083));

      updateGatewayFieldsVisibility(gatewayFields, currentMode);

      rgMode.setOnCheckedChangeListener((group, checkedId) -> {
        TrustedNode.Mode m;
        if (checkedId == R.id.rb_mode_direct) {
          m = TrustedNode.Mode.DIRECT;
        } else if (checkedId == R.id.rb_mode_gateway) {
          m = TrustedNode.Mode.GATEWAY;
        } else if (checkedId == R.id.rb_mode_wifi_direct) {
          m = TrustedNode.Mode.WIFI_DIRECT;
        } else {
          m = TrustedNode.Mode.AUTO;
        }
        log.log("Выбор режима: " + m);
        updateGatewayFieldsVisibility(gatewayFields, m);
      });

      new AlertDialog.Builder(this).setView(dialogView)
          .setTitle("Режим подключения: " + node.getName())
          .setPositiveButton("Сохранить", (d, w) -> {
            try {
              TrustedNode.Mode newMode;
              if (rbGateway.isChecked()) {
                newMode = TrustedNode.Mode.GATEWAY;
              } else if (rbDirect.isChecked()) {
                newMode = TrustedNode.Mode.DIRECT;
              } else if (rbWifiDirect.isChecked()) {
                newMode = TrustedNode.Mode.WIFI_DIRECT;
              } else {
                newMode = TrustedNode.Mode.AUTO;
              }

              String gwIp = editGwIp.getText().toString().trim();
              String gwPortStr = editGwPort.getText().toString().trim();
              int gwPort = 8083;
              try {
                gwPort = TextUtils.isEmpty(gwPortStr) ? 8083 : Integer.parseInt(gwPortStr);
              } catch (NumberFormatException e) {
                Toast.makeText(this, "Неверный порт шлюза", Toast.LENGTH_LONG).show();
                return;
              }

              if (newMode == TrustedNode.Mode.GATEWAY && (gwIp == null || gwIp.isEmpty())) {
                Toast.makeText(this, "Укажите IP шлюза", Toast.LENGTH_LONG).show();
                return;
              }

              boolean ok = nodeManager.updateNodeMode(node.getId(), node.getIpAddress(),
                  node.getPort(), newMode, gwIp, gwPort);

              if (ok) {
                log.log("Режим узла обновлён: key=" + node.getKey() + ", mode=" + newMode);
                loadNodeList();
                Toast.makeText(this, "Режим сохранён: " + newMode, Toast.LENGTH_SHORT).show();
              } else {
                Toast.makeText(this, "Не удалось сохранить режим", Toast.LENGTH_LONG).show();
              }
            } catch (Exception e) {
              logError("Ошибка сохранения режима узла", e);
            }
          }).setNegativeButton("Отмена", (d, w) -> d.dismiss()).show();

    } catch (Exception e) {
      logError("Ошибка showNodeModeDialog", e);
    }
  }

  private void updateGatewayFieldsVisibility(LinearLayout gatewayFields, TrustedNode.Mode mode) {
    if (gatewayFields == null)
      return;
    if (mode == TrustedNode.Mode.GATEWAY) {
      gatewayFields.setVisibility(View.VISIBLE);
    } else {
      gatewayFields.setVisibility(View.GONE);
    }
  }

  private void showAddNodeDialog(TrustedNode existingNode) {
    log.log("showAddNodeDialog: existing=" + (existingNode != null));
    try {
      View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_connect, null);
      EditText editName = dialogView.findViewById(R.id.edit_node_name);
      EditText editIp = dialogView.findViewById(R.id.edit_node_ip);
      EditText editPort = dialogView.findViewById(R.id.edit_node_port);
      EditText editDeviceId = dialogView.findViewById(R.id.edit_device_id);

      if (editName == null || editIp == null || editPort == null || editDeviceId == null) {
        log.error("showAddNodeDialog: не найдены View в dialog_connect.xml");
        Toast.makeText(this, "Ошибка разметки dialog_connect.xml", Toast.LENGTH_LONG).show();
        return;
      }

      if (existingNode != null) {
        editName.setText(existingNode.getName());
        editIp.setText(existingNode.getIpAddress());
        editPort.setText(String.valueOf(existingNode.getPort()));
        editDeviceId.setText(existingNode.getDeviceId());
      } else {
        editPort.setText("8081");
      }

      new AlertDialog.Builder(this).setView(dialogView)
          .setTitle(existingNode == null ? "Добавить устройство" : "Редактировать узел")
          .setPositiveButton(existingNode == null ? "Добавить" : "Сохранить", (d, w) -> {
            saveNodeFromDialog(existingNode, editName, editIp, editPort, editDeviceId);
          }).setNegativeButton("Отмена", (d, w) -> d.dismiss()).show();
    } catch (Exception e) {
      logError("Ошибка showAddNodeDialog", e);
    }
  }

  private void saveNodeFromDialog(TrustedNode existingNode, EditText editName, EditText editIp,
      EditText editPort, EditText editDeviceId) {
    try {
      String name = editName.getText().toString().trim();
      String ip = editIp.getText().toString().trim();
      String deviceIdStr = editDeviceId.getText().toString().trim();
      int port;

      log.log("Ввод узла: name='" + name + "', ip='" + ip + "', port='" + editPort.getText()
          + "', deviceId='" + deviceIdStr + "'");

      try {
        port = Integer.parseInt(editPort.getText().toString().trim());
        if (port < 1024 || port > 65535) {
          throw new NumberFormatException("Порт вне диапазона");
        }
      } catch (NumberFormatException e) {
        Toast.makeText(this, "Порт должен быть от 1024 до 65535", Toast.LENGTH_LONG).show();
        return;
      }

      if (name.isEmpty() || ip.isEmpty() || deviceIdStr.isEmpty()) {
        Toast.makeText(this, "Заполните все поля", Toast.LENGTH_LONG).show();
        return;
      }

      String selfIp = DeviceInfoHelper.getSelfIp(this);
      String selfFp = new SecureAuthManager(this).getDeviceFingerprint();
      String selfDeviceId = DeviceInfoHelper.getDeviceId(this);

      if (selfIp != null && selfIp.equals(ip)) {
        Toast.makeText(this, "Это IP вашего устройства — нельзя добавить как узел",
            Toast.LENGTH_LONG).show();
        return;
      }

      if (selfDeviceId != null && selfDeviceId.equals(deviceIdStr)) {
        Toast.makeText(this, "Это ID вашего устройства — нельзя добавить как узел",
            Toast.LENGTH_LONG).show();
        return;
      }

      if (existingNode != null) {
        log.log("Ветка: редактирование существующего узла id=" + existingNode.getId() + ", key="
            + existingNode.getKey());

        String preservedFp = existingNode.getFingerprint();
        boolean sameOwner = existingNode.getDeviceId() != null
            && existingNode.getDeviceId().equals(deviceIdStr) && existingNode.getIpAddress() != null
            && existingNode.getIpAddress().equals(ip);
        if (!sameOwner) {
          log.log("Редактирование: владелец узла изменился — сбрасываю fingerprint");
          preservedFp = null;
        }

        TrustedNode node = new TrustedNode(existingNode.getId(), name, ip, port, deviceIdStr,
            preservedFp);
        node.setMode(existingNode.getMode());
        node.setGatewayIp(existingNode.getGatewayIp());
        node.setGatewayPort(existingNode.getGatewayPort());

        if (selfFp != null && selfFp.equals(node.getFingerprint())) {
          Toast.makeText(this, "Fingerprint совпадает с вашим устройством — нельзя добавить",
              Toast.LENGTH_LONG).show();
          return;
        }

        boolean saved = nodeManager.saveNode(node, selfFp, selfDeviceId);
        if (!saved) {
          Toast.makeText(this, "Узел не сохранён: похоже, это ваше устройство", Toast.LENGTH_LONG)
              .show();
          return;
        }

        log.log("Узел обновлён: id=" + node.getId() + ", name=" + node.getName() + " ("
            + node.getKey() + ")");

        if (selectedNode != null && selectedNode.getKey().equals(existingNode.getKey())
            && !existingNode.getKey().equals(node.getKey())) {
          selectedNode = null;
          if (nodeAdapter != null)
            nodeAdapter.setSelectedKey(null);
        }

        loadNodeList();
        Toast.makeText(this, "Устройство обновлено", Toast.LENGTH_SHORT).show();
        return;
      }

      log.warn("saveNodeFromDialog: existingNode == null (создание нового) — пропускаем");
      Toast.makeText(this, "Создание нового узла — через Настройки (иконка в шапке)",
          Toast.LENGTH_LONG).show();

    } catch (Exception e) {
      logError("Ошибка сохранения узла", e);
    }
  }

  private void showDeleteConfirmation(TrustedNode node) {
    try {
      new AlertDialog.Builder(this).setTitle("Удалить устройство?")
          .setMessage("Удалить «" + node.getName() + "» (" + node.getKey() + ")?")
          .setPositiveButton("Удалить", (d, w) -> {
            try {
              nodeManager.deleteNode(node.getIpAddress(), node.getPort());
              if (selectedNode != null && selectedNode.getKey().equals(node.getKey())) {
                selectedNode = null;
                if (nodeAdapter != null)
                  nodeAdapter.setSelectedKey(null);
              }
              loadNodeList();
              Toast.makeText(this, "Устройство удалено", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
              logError("Ошибка удаления узла", e);
            }
          }).setNegativeButton("Отмена", (d, w) -> d.dismiss()).show();
    } catch (Exception e) {
      logError("Ошибка showDeleteConfirmation", e);
    }
  }

  private void performNodeCheck(TrustedNode node) {
    if (node == null || pingInProgress)
      return;
    pingInProgress = true;

    final String ip = node.getIpAddress();
    final int port = node.getPort();
    final String name = node.getName();
    final String key = node.getKey();

    AlertDialog progressDialog = new AlertDialog.Builder(this).setTitle("Проверка узла")
        .setMessage("Проверяю:\n" + name + "\n" + key + "...").setCancelable(false).create();
    progressDialog.show();

    new Thread(() -> {
      long startTime = System.currentTimeMillis();
      String result;
      boolean success = false;

      try {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(ip, port), 3000);
        socket.close();
        long elapsed = System.currentTimeMillis() - startTime;
        success = true;
        result = "✅ Узел доступен\n\nИмя: " + name + "\nАдрес: " + key + "\nВремя: " + elapsed
            + " мс";

        try {
          TrustedNode fresh = nodeManager.findByIpPort(ip, port);
          if (fresh != null) {
            fresh.touchLastSeen(fresh.getLastSeenNetwork());
            nodeManager.saveNode(fresh);
            try {
              if (fresh != null) {
                NodeEnricher.enrich(MainActivity.this, fresh);
              }
            } catch (Exception ignored) {
            }
          }
        } catch (Exception ignored) {
        }
      } catch (SocketTimeoutException e) {
        result = "⏱ Таймаут подключения (3 сек)\n\nУзел: " + name + "\nАдрес: " + key;
      } catch (ConnectException e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";
        if (msg.contains("ECONNREFUSED") || msg.contains("Connection refused")) {
          result = "🔴 Узел в сети, но сервер не запущен\n\nУзел: " + name + "\nАдрес: " + key;
        } else {
          result = "🔴 Ошибка подключения\n\nУзел: " + name + "\nОшибка: " + msg;
        }
      } catch (UnknownHostException e) {
        result = "🔴 Неизвестный хост\n\nАдрес: " + ip;
      } catch (Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        result = "🔴 Ошибка\n\nУзел: " + name + "\nОшибка: " + msg;
      }

      final String finalResult = result;
      final boolean finalSuccess = success;

      runOnUiThread(() -> {
        try {
          progressDialog.dismiss();
          new AlertDialog.Builder(MainActivity.this)
              .setTitle(finalSuccess ? "✅ Узел доступен" : "❌ Узел недоступен")
              .setMessage(finalResult).setPositiveButton("OK", null).show();
        } catch (Exception e) {
          logError("Ошибка показа результата ping", e);
        } finally {
          pingInProgress = false;
        }
      });
    }, "NodeCheckThread").start();
  }

  // ============================================================
  // Звонок
  // ============================================================

  private void startCall() {
    try {
      if (selectedNode == null) {
        Toast.makeText(this, "Выберите узел", Toast.LENGTH_SHORT).show();
        return;
      }

      String targetIp = selectedNode.getIpAddress();
      int targetPort = selectedNode.getPort();
      String targetFp = selectedNode.getFingerprint();
      TrustedNode.Mode mode = selectedNode.getMode();

      log.log("startCall: node=" + selectedNode.getName() + ", ip=" + targetIp + ":" + targetPort
          + ", mode=" + mode);

      if (mode == TrustedNode.Mode.WIFI_DIRECT) {
        handleWifiDirectCall();
        return;
      }

      if (targetIp == null || targetIp.isEmpty()) {
        Toast.makeText(this, "У узла не указан IP", Toast.LENGTH_LONG).show();
        return;
      }

      String selfIp = DeviceInfoHelper.getSelfIp(this);
      String selfFp = new SecureAuthManager(this).getDeviceFingerprint();

      if (selfIp != null && selfIp.equals(targetIp)) {
        Toast.makeText(this, "Нельзя позвонить самому себе", Toast.LENGTH_LONG).show();
        return;
      }
      if (selfFp != null && selfFp.equals(targetFp)) {
        Toast.makeText(this, "Нельзя позвонить самому себе", Toast.LENGTH_LONG).show();
        return;
      }

      if (mode == TrustedNode.Mode.GATEWAY) {
        if (selectedNode.getGatewayIp() == null || selectedNode.getGatewayIp().isEmpty()) {
          new AlertDialog.Builder(this).setTitle("Шлюз не указан")
              .setMessage("Узел настроен на работу через шлюз, но IP шлюза не задан.")
              .setPositiveButton("Продолжить", (d, w) -> launchCallActivity())
              .setNegativeButton("Отмена", null).show();
          return;
        }

        if (!vpnRunning || !TunnelVpnService.MODE_CLIENT.equals(vpnMode)) {
          new AlertDialog.Builder(this).setTitle("Нужен VPN")
              .setMessage("Узел работает через шлюз " + selectedNode.getGatewayIp()
                  + ", но VPN не подключён. Подключить?")
              .setPositiveButton("Подключить", (d, w) -> {
                Intent tunnelIntent = new Intent(this, TunnelActivity.class);
                tunnelIntent.putExtra("mode", TunnelVpnService.MODE_CLIENT);
                tunnelIntent.putExtra("gateway_ip", selectedNode.getGatewayIp());
                tunnelIntent.putExtra("gateway_port", selectedNode.getGatewayPort());
                startActivity(tunnelIntent);
              }).setNeutralButton("Продолжить без VPN", (d, w) -> launchCallActivity())
              .setNegativeButton("Отмена", null).show();
          return;
        }
      }

      launchCallActivity();
    } catch (Exception e) {
      logError("Ошибка startCall", e);
      Toast.makeText(this, "Ошибка звонка: " + e.getMessage(), Toast.LENGTH_LONG).show();
    }
  }

  private void handleWifiDirectCall() {
    try {
      WifiDirectManager wdm = ((MealseApp) getApplication()).getWifiDirectManager();

      if (wdm == null || !wdm.isWifiDirectSupported()) {
        Toast.makeText(this, "Wi-Fi Direct недоступен", Toast.LENGTH_LONG).show();
        return;
      }

      if (!wdm.isConnected()) {
        new AlertDialog.Builder(this).setTitle("Wi-Fi Direct не подключён")
            .setMessage("Узел работает через Wi-Fi Direct, но соединение не установлено.\n\n"
                + "Перейти в управление Wi-Fi Direct?")
            .setPositiveButton("Перейти", (d, w) -> openWifiDirectActivity())
            .setNegativeButton("Отмена", null).show();
        return;
      }

      WifiDirectManager.Peer remote = wdm.getRemotePeer();
      String remoteDeviceId = (remote != null) ? remote.deviceId : null;
      String selectedDeviceId = selectedNode.getDeviceId();

      if (remoteDeviceId == null || selectedDeviceId == null
          || !remoteDeviceId.equals(selectedDeviceId)) {
        new AlertDialog.Builder(this).setTitle("Wi-Fi Direct подключён к другому устройству")
            .setMessage("Переподключиться к выбранному узлу?")
            .setPositiveButton("Перейти", (d, w) -> openWifiDirectActivity())
            .setNegativeButton("Отмена", null).show();
        return;
      }

      launchCallActivity();
    } catch (Exception e) {
      logError("handleWifiDirectCall", e);
      Toast.makeText(this, "Ошибка звонка: " + e.getMessage(), Toast.LENGTH_LONG).show();
    }
  }

  private void launchCallActivity() {
    try {
      Intent callIntent = CallActivity.newCallIntent(this, selectedNode);
      startActivity(callIntent);
    } catch (Exception e) {
      logError("Ошибка launchCallActivity", e);
      Toast.makeText(this, "Ошибка запуска звонка: " + e.getMessage(), Toast.LENGTH_LONG).show();
    }
  }

  // ============================================================
  // Список узлов
  // ============================================================

  private void loadNodeList() {
    try {
      List<TrustedNode> all = nodeManager.getAllNodes();

      String selfIp = DeviceInfoHelper.getSelfIp(this);
      String selfFingerprint = new SecureAuthManager(this).getDeviceFingerprint();

      List<TrustedNode> filtered = new ArrayList<>();
      for (TrustedNode n : all) {
        boolean isSelf = selfFingerprint != null && selfFingerprint.equals(n.getFingerprint());
        if (selfIp != null && !selfIp.isEmpty() && selfIp.equals(n.getIpAddress())) {
          isSelf = true;
        }
        if (!isSelf) {
          filtered.add(n);
        }
      }

      if (nodeAdapter == null) {
        nodeAdapter = new NodeAdapter(this, filtered);
        nodeAdapter.setOnNodeSelectListener(node -> {
          log.log("Выбран узел: " + node.getName() + " (key=" + node.getKey() + ")");
          selectedNode = node;
        });
        nodesListView.setAdapter(nodeAdapter);
      } else {
        nodeAdapter.replaceAll(filtered);
      }

      if (selectedNode != null) {
        boolean stillPresent = false;
        for (TrustedNode n : filtered) {
          if (n.getKey().equals(selectedNode.getKey())) {
            stillPresent = true;
            break;
          }
        }
        if (!stillPresent) {
          selectedNode = null;
          if (nodeAdapter != null)
            nodeAdapter.setSelectedKey(null);
        } else {
          if (nodeAdapter != null)
            nodeAdapter.setSelectedKey(selectedNode.getKey());
        }
      }
    } catch (Exception e) {
      logError("Ошибка loadNodeList", e);
    }
  }

  // ============================================================
  // Хелперы
  // ============================================================

  private void logError(String message, Throwable e) {
    if (log != null) {
      log.logError(message, e);
    } else {
      Log.e(TAG, message, e);
    }
  }
}==========================================================
  // Список узлов
  // ============================================================

  private void loadNodeList() {
    try {
      List<TrustedNode> all = nodeManager.getAllNodes();

      String selfIp = DeviceInfoHelper.getSelfIp(this);
      String selfFingerprint = new SecureAuthManager(this).getDeviceFingerprint();

      List<TrustedNode> filtered = new ArrayList<>();
      for (TrustedNode n : all) {
        boolean isSelf = selfFingerprint != null && selfFingerprint.equals(n.getFingerprint());
        if (selfIp != null && !selfIp.isEmpty() && selfIp.equals(n.getIpAddress())) {
          isSelf = true;
        }
        if (!isSelf) {
          filtered.add(n);
        }
      }

      if (nodeAdapter == null) {
        nodeAdapter = new NodeAdapter(this, filtered);
        nodeAdapter.setOnNodeSelectListener(node -> {
          log.log("Выбран узел: " + node.getName() + " (key=" + node.getKey() + ")");
          selectedNode = node;
        });
        nodesListView.setAdapter(nodeAdapter);
      } else {
        nodeAdapter.replaceAll(filtered);
      }

      if (selectedNode != null) {
        boolean stillPresent = false;
        for (TrustedNode n : filtered) {
          if (n.getKey().equals(selectedNode.getKey())) {
            stillPresent = true;
            break;
          }
        }
        if (!stillPresent) {
          selectedNode = null;
          if (nodeAdapter != null)
            nodeAdapter.setSelectedKey(null);
        } else {
          if (nodeAdapter != null)
            nodeAdapter.setSelectedKey(selectedNode.getKey());
        }
      }
    } catch (Exception e) {
      logError("Ошибка loadNodeList", e);
    }
  }

  // ============================================================
  // Хелперы
  // ============================================================

  private void logError(String message, Throwable e) {
    if (log != null) {
      log.logError(message, e);
    } else {
      Log.e(TAG, message, e);
    }
  }
}