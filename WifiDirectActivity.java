package com.my.mealse;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.my.mealse.adapter.WifiDirectPeerAdapter;
import com.my.mealse.model.TrustedNode;
import com.my.mealse.util.MealseLogger;
import com.my.mealse.util.NodeManager;
import com.my.mealse.util.WifiDirectManager;
import com.my.mealse.util.WifiDirectManager.Peer;
import com.my.mealse.BaseActivity;

import java.util.List;

/**
 * WifiDirectActivity — управление Wi-Fi Direct.
 *
 * ★ РЕВИЗИЯ (выделено из TunnelActivity): - Только Wi-Fi Direct: discovery, список пиров, connect, disconnect. - VPN-режимы (client/server) больше здесь НЕ настраиваются — они в TunnelActivity. - В onResume — reconcileWifiDirectState() синхронизирует внутренние флаги WifiDirectManager с реальным состоянием системы (фикс «залипшего»).
 */
public class WifiDirectActivity extends Activity {

  private static final String TAG = "WifiDirectActivity";
  private static final int REQ_WIFI_DIRECT_PERMISSIONS = 1002;

  // ===== Views =====
  private TextView tvStatus;
  private Button btnDiscover;
  private Button btnDisconnect;
  private FrameLayout listWrapper;
  private ListView listWifiPeers;

  // ===== Wi-Fi Direct =====
  private WifiDirectManager wifiDirectManager;
  private WifiDirectPeerAdapter wifiPeerAdapter;
  private WifiDirectManager.Listener listener;

  private boolean discovering = false;

  // ===== Managers =====
  private MealseLogger log;
  private NodeManager nodeManager;

  // ============================================================
  // Lifecycle
  // ============================================================

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    log = MealseLogger.get(this, TAG);
    log.log("══ onCreate START ══");

    try {
      setContentView(R.layout.activity_wifi_direct);

      // findViewById
      tvStatus = findViewById(R.id.tv_wifi_direct_status);
      btnDiscover = findViewById(R.id.btn_wifi_direct_discover);
      btnDisconnect = findViewById(R.id.btn_wifi_direct_disconnect);
      listWrapper = findViewById(R.id.wifi_peers_list_wrapper);
      listWifiPeers = findViewById(R.id.list_wifi_peers);

      checkViewNotNull(tvStatus, "tv_wifi_direct_status");
      checkViewNotNull(btnDiscover, "btn_wifi_direct_discover");
      checkViewNotNull(btnDisconnect, "btn_wifi_direct_disconnect");
      checkViewNotNull(listWrapper, "wifi_peers_list_wrapper");
      checkViewNotNull(listWifiPeers, "list_wifi_peers");

      nodeManager = new NodeManager(this);
      wifiDirectManager = ((MealseApp) getApplication()).getWifiDirectManager();

      if (wifiDirectManager == null || !wifiDirectManager.isWifiDirectSupported()) {
        log.warn("Wi-Fi Direct недоступен на этом устройстве");
        tvStatus.setText("Wi-Fi Direct недоступен на этом устройстве");
        tvStatus.setTextColor(0xFFE53E3E);
        btnDiscover.setEnabled(false);
        btnDisconnect.setEnabled(false);
        return;
      }

      // Адаптер
      wifiPeerAdapter = new WifiDirectPeerAdapter(this);
      listWifiPeers.setAdapter(wifiPeerAdapter);

      // Клик по пиру — выбрать его
      listWifiPeers.setOnItemClickListener((parent, view, position, id) -> {
        Peer peer = wifiPeerAdapter.getItem(position);
        log.log("Клик по пиру: " + peer);
        onPeerSelected(peer);
      });

      // ★ Критично для скролла внутри ScrollView-обёртки (если будет).
      listWifiPeers.setOnTouchListener((v, event) -> {
        switch (event.getActionMasked()) {
          case MotionEvent.ACTION_DOWN:
          case MotionEvent.ACTION_MOVE:
            v.getParent().requestDisallowInterceptTouchEvent(true);
            break;
          case MotionEvent.ACTION_UP:
          case MotionEvent.ACTION_CANCEL:
            v.getParent().requestDisallowInterceptTouchEvent(false);
            break;
        }
        return false;
      });

      // Кнопки
      btnDiscover.setOnClickListener(v -> {
        log.log("Клик: btnDiscover");
        onDiscoverClicked();
      });

      btnDisconnect.setOnClickListener(v -> {
        log.log("Клик: btnDisconnect");
        onDisconnectClicked();
      });

      // Открываем менеджер и подписываемся
      if (!wifiDirectManager.isOpened()) {
        boolean ok = wifiDirectManager.open();
        if (!ok) {
          tvStatus.setText("Ошибка инициализации Wi-Fi Direct");
          tvStatus.setTextColor(0xFFE53E3E);
          return;
        }
      }

      subscribeListener();
      requestPermissionsIfNeeded();

      log.log("══ onCreate END ══");

    } catch (Exception e) {
      logError("КРИТИЧЕСКАЯ ОШИБКА в onCreate", e);
      Toast.makeText(this, "Ошибка запуска: " + e.getMessage(), Toast.LENGTH_LONG).show();
      finish();
    }
  }

  private void checkViewNotNull(Object view, String id) {
    if (view == null) {
      String msg = "View не найден: " + id + " — проверь activity_wifi_direct.xml";
      log.error(msg);
      throw new IllegalStateException(msg);
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    log.log("══ onResume START ══");

    try {
      if (wifiDirectManager != null && wifiDirectManager.isWifiDirectSupported()
          && wifiDirectManager.isOpened()) {
        reconcileWifiDirectState();
      }
      log.log("══ onResume END ══");
    } catch (Exception e) {
      logError("Ошибка в onResume", e);
    }
  }

  @Override
  protected void onDestroy() {
    log.log("onDestroy START");

    try {
      unsubscribeListener();
    } catch (Exception e) {
      logError("Ошибка в onDestroy", e);
    }

    log.log("onDestroy END");
    super.onDestroy();
  }

  // ============================================================
  // Listener
  // ============================================================

  private void subscribeListener() {
    if (wifiDirectManager == null || listener != null) {
      log.log("subscribeListener: уже подписан или manager == null");
      return;
    }

    listener = new WifiDirectManager.Listener() {
      @Override
      public void onPeersChanged(List<Peer> peers) {
        log.log("onPeersChanged: " + (peers != null ? peers.size() : 0));
        runOnUiThread(() -> {
          if (wifiPeerAdapter != null) {
            wifiPeerAdapter.replaceAll(peers);
          }
        });
      }

      @Override
      public void onDiscoveryStarted() {
        log.log("onDiscoveryStarted");
        runOnUiThread(() -> {
          discovering = true;
          tvStatus.setText("Поиск устройств...");
          tvStatus.setTextColor(0xFFDD6B20);
        });
      }

      @Override
      public void onDiscoveryStopped() {
        log.log("onDiscoveryStopped");
        runOnUiThread(() -> {
          discovering = false;
          if (wifiDirectManager.isConnected()) {
            Peer rp = wifiDirectManager.getRemotePeer();
            String name = (rp != null && rp.deviceName != null) ? rp.deviceName : "пир";
            String role = wifiDirectManager.isGroupOwner() ? "Group Owner" : "клиент";
            tvStatus.setText("🟢 Подключено: " + name + " (" + role + ")");
            tvStatus.setTextColor(0xFF38A169);
          } else {
            tvStatus.setText("Поиск остановлен");
            tvStatus.setTextColor(0xFF718096);
          }
        });
      }

      @Override
      public void onConnected(Peer remote, boolean isGroupOwner) {
        log.log("onConnected: " + remote + ", isGroupOwner=" + isGroupOwner);
        runOnUiThread(() -> {
          if (remote == null)
            return;
          String role = isGroupOwner ? "Group Owner" : "клиент";
          String name = remote.deviceName != null ? remote.deviceName : "пир";
          tvStatus.setText("🟢 Подключено: " + name + " (" + role + ")");
          tvStatus.setTextColor(0xFF38A169);
          btnDisconnect.setEnabled(true);
          saveWifiDirectNode(remote, isGroupOwner);
        });
      }

      @Override
      public void onDisconnected() {
        log.log("onDisconnected");
        runOnUiThread(() -> {
          tvStatus.setText("Не подключено");
          tvStatus.setTextColor(0xFF4A5568);
          btnDisconnect.setEnabled(false);
        });
      }

      @Override
      public void onSignal(String json) {
        // WifiDirectActivity сигналы не обрабатывает — это работа CallActivity/MealseApp.
      }

      @Override
      public void onError(String message) {
        log.error("onError: " + message);
        runOnUiThread(() -> {
          tvStatus.setText("⚠ " + message);
          tvStatus.setTextColor(0xFFE53E3E);
        });
      }
    };

    wifiDirectManager.addListener(listener);
    log.log("subscribeListener: подписан, listenerCount=" + wifiDirectManager.getListenerCount());
  }

  private void unsubscribeListener() {
    if (wifiDirectManager == null || listener == null)
      return;
    try {
      wifiDirectManager.removeListener(listener);
      listener = null;
      log.log("unsubscribeListener: снят, осталось=" + wifiDirectManager.getListenerCount());
    } catch (Exception e) {
      logError("unsubscribeListener", e);
    }
  }

  // ============================================================
  // Кнопки
  // ============================================================

  private void onDiscoverClicked() {
    if (wifiDirectManager == null)
      return;

    if (!wifiDirectManager.isOpened()) {
      boolean ok = wifiDirectManager.open();
      if (!ok) {
        Toast.makeText(this, "Wi-Fi Direct не инициализирован", Toast.LENGTH_SHORT).show();
        return;
      }
      subscribeListener();
    }

    if (!wifiDirectManager.hasPermissions()) {
      requestPermissionsIfNeeded();
      return;
    }

    log.log("onDiscoverClicked: startDiscovery");
    wifiDirectManager.startDiscovery();
  }

  private void onDisconnectClicked() {
    if (wifiDirectManager == null)
      return;
    log.log("onDisconnectClicked: forceRemoveGroup + close + open");

    try {
      // Снимаем listener на время close/open
      unsubscribeListener();

      wifiDirectManager.forceRemoveGroup((success, message) -> {
        log.log("forceRemoveGroup: success=" + success + ", msg=" + message);

        try {
          wifiDirectManager.close();
          Thread.sleep(300);
        } catch (Exception ignored) {
        }

        boolean ok = wifiDirectManager.open();
        log.log("reopen=" + ok);

        runOnUiThread(() -> {
          discovering = false;
          if (wifiPeerAdapter != null)
            wifiPeerAdapter.replaceAll(null);
          if (ok) {
            subscribeListener();
            tvStatus.setText("Отключено");
            tvStatus.setTextColor(0xFF4A5568);
            btnDisconnect.setEnabled(false);
          } else {
            tvStatus.setText("Ошибка переоткрытия Wi-Fi Direct");
            tvStatus.setTextColor(0xFFE53E3E);
          }
        });
      });
    } catch (Exception e) {
      logError("onDisconnectClicked", e);
    }
  }

  // ============================================================
  // Выбор пира
  // ============================================================

  private void onPeerSelected(Peer peer) {
    if (peer == null || wifiDirectManager == null)
      return;

    if (wifiDirectManager.isConnected()) {
      Toast.makeText(this, "Уже подключено к другому устройству", Toast.LENGTH_SHORT).show();
      return;
    }

    new AlertDialog.Builder(this)
        .setTitle("Подключиться к "
            + (peer.deviceName != null ? peer.deviceName : peer.deviceAddress) + "?")
        .setMessage("Выберите роль этого устройства в группе.\n\n"
            + "• Group Owner — этот девайс будет «хостом» (создаёт группу).\n"
            + "• Клиент — этот девайс подключится к уже созданной группе.\n\n"
            + "Обычно: тот, кто звонит — клиент, тот, кому звонят — Group Owner.")
        .setPositiveButton("Я Group Owner", (d, w) -> {
          log.log("onPeerSelected: connect as GO");
          wifiDirectManager.connect(peer, true);
          tvStatus.setText("Подключение к " + peer.deviceName + " (GO)...");
          tvStatus.setTextColor(0xFFDD6B20);
        }).setNeutralButton("Я клиент", (d, w) -> {
          log.log("onPeerSelected: connect as client");
          wifiDirectManager.connect(peer, false);
          tvStatus.setText("Подключение к " + peer.deviceName + " (клиент)...");
          tvStatus.setTextColor(0xFFDD6B20);
        }).setNegativeButton("Отмена", null).show();
  }

  // ============================================================
  // Синхронизация состояния с реальным
  // ============================================================

  /**
   * ★ Сверяет внутренние флаги WifiDirectManager с реальным состоянием системы. Фикс «залипшего» Wi-Fi Direct после перезапуска приложения.
   */
  private void reconcileWifiDirectState() {
    log.log("reconcileWifiDirectState START");

    wifiDirectManager.requestRealGroupInfo((groupExists, isGroupOwner, networkName) -> {
      runOnUiThread(() -> {
        try {
          boolean localConnected = wifiDirectManager.isConnected();
          log.log("reconcile: groupExists=" + groupExists + ", localConnected=" + localConnected
              + ", networkName=" + networkName);

          if (groupExists && !localConnected) {
            log.warn("reconcile: ЗАЛИПШАЯ ГРУППА — force reset");
            forceResetWifiDirect();
            return;
          }

          if (localConnected && !groupExists) {
            log.warn("reconcile: локально connected, группы нет — сбрасываю UI");
            tvStatus.setText("Не подключено");
            tvStatus.setTextColor(0xFF4A5568);
            btnDisconnect.setEnabled(false);
            return;
          }

          if (groupExists && localConnected) {
            Peer rp = wifiDirectManager.getRemotePeer();
            boolean go = wifiDirectManager.isGroupOwner();
            String role = go ? "Group Owner" : "клиент";
            String name = (rp != null && rp.deviceName != null) ? rp.deviceName : "пир";
            tvStatus.setText("🟢 Подключено: " + name + " (" + role + ")");
            tvStatus.setTextColor(0xFF38A169);
            btnDisconnect.setEnabled(true);
            return;
          }

          log.log("reconcile: чисто");
          tvStatus.setText("Не подключено");
          tvStatus.setTextColor(0xFF4A5568);
          btnDisconnect.setEnabled(false);

        } catch (Exception e) {
          logError("reconcile runOnUiThread", e);
        }
      });
    });
  }

  private void forceResetWifiDirect() {
    log.log("forceResetWifiDirect START");

    try {
      unsubscribeListener();

      wifiDirectManager.forceRemoveGroup((success, message) -> {
        log.log("forceRemoveGroup: success=" + success + ", msg=" + message);

        try {
          wifiDirectManager.close();
          Thread.sleep(300);
        } catch (Exception ignored) {
        }

        boolean ok = wifiDirectManager.open();
        log.log("reopen=" + ok);

        runOnUiThread(() -> {
          discovering = false;
          if (wifiPeerAdapter != null)
            wifiPeerAdapter.replaceAll(null);
          if (ok) {
            subscribeListener();
          }
          tvStatus.setText("Не подключено");
          tvStatus.setTextColor(0xFF4A5568);
          btnDisconnect.setEnabled(false);
        });
      });
    } catch (Exception e) {
      logError("forceResetWifiDirect", e);
    }
  }

  // ============================================================
  // Сохранение узла
  // ============================================================

  private void saveWifiDirectNode(Peer peer, boolean isGroupOwner) {
    if (peer == null)
      return;
    if (TextUtils.isEmpty(peer.deviceAddress)) {
      log.warn("saveWifiDirectNode: deviceAddress пустой");
      return;
    }
    if (nodeManager == null)
      return;

    try {
      String mac = peer.deviceAddress;
      String name = peer.deviceName != null ? peer.deviceName : "Wi-Fi Direct пир";
      String deviceId = peer.deviceId != null ? peer.deviceId : "";
      String fingerprint = peer.fingerprint != null ? peer.fingerprint : "";
      String ipv6 = peer.ipv6 != null ? peer.ipv6 : ""; // ★ НОВОЕ

      TrustedNode existing = nodeManager.findByIpPort(mac, 0);
      String id;
      if (existing != null) {
        id = existing.getId();
      } else {
        id = "wifidirect_" + Long.toHexString(System.currentTimeMillis());
      }

      TrustedNode node = new TrustedNode(id, name, mac, 0, deviceId, fingerprint);
      node.setMode(TrustedNode.Mode.WIFI_DIRECT);
      node.setGatewayIp(null);
      node.setGatewayPort(0);
      if (!ipv6.isEmpty()) {
        node.setIpv6Address(ipv6); // ★ НОВОЕ
      }

      boolean saved = nodeManager.saveNode(node);
      log.log("saveWifiDirectNode: " + (saved ? "OK" : "FAIL") + ", key=" + node.getKey()
          + (ipv6.isEmpty() ? "" : ", ipv6=" + ipv6));

    } catch (Exception e) {
      logError("saveWifiDirectNode", e);
    }
  }

  // ============================================================
  // Разрешения
  // ============================================================

  private void requestPermissionsIfNeeded() {
    if (wifiDirectManager != null && wifiDirectManager.hasPermissions()) {
      log.log("Разрешения уже есть");
      return;
    }

    String[] perms;
    if (Build.VERSION.SDK_INT >= 33) {
      perms = new String[] { "android.permission.NEARBY_WIFI_DEVICES" };
    } else {
      perms = new String[] { Manifest.permission.ACCESS_FINE_LOCATION };
    }

    log.log("Запрашиваю разрешения: " + perms[0]);
    requestPermissions(perms, REQ_WIFI_DIRECT_PERMISSIONS);
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions,
      int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    log.log("onRequestPermissionsResult: requestCode=" + requestCode);

    if (requestCode == REQ_WIFI_DIRECT_PERMISSIONS) {
      boolean granted = grantResults.length > 0
          && grantResults[0] == PackageManager.PERMISSION_GRANTED;
      if (granted) {
        log.log("Разрешения выданы");
        tvStatus.setText("Готово. Нажмите «Найти»");
        tvStatus.setTextColor(0xFF4A5568);
      } else {
        log.warn("Разрешения отклонены");
        tvStatus.setText("⚠ Разрешения отклонены — поиск невозможен");
        tvStatus.setTextColor(0xFFE53E3E);
      }
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