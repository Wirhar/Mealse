package com.my.mealse.util;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.my.mealse.filemanager.wifidirect.WifiDirectFrame;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WifiDirectManager — ядро Wi-Fi Direct в Mealse.
 *
 * ★ РЕВИЗИЯ (Шаг 4, мультиплексирование): - Канал теперь поддерживает не только JSON-сигналинг, но и бинарные файловые кадры (см. WifiDirectFrame). - Формат сообщения: [uint32 total_length][uint8 type][payload...]. - Старый формат [uint32 length][JSON UTF-8] тоже принимается: если первый байт payload не 0x01..0x06 — считаем, что это старый JSON-сигнал (обратная совместимость). - Добавлены FileFrameListener, addFileFrameListener, removeFileFrameListener, sendFileFrame, isFileChannelReady.
 *
 * Lifecycle: - open() — инициализация WifiP2pManager и receiver. - close() — removeGroup, закрытие сокетов, снятие receiver.
 */
public class WifiDirectManager {

  private static final String TAG = "WifiDirectManager";

  // ============================================================
  // Константы
  // ============================================================

  public static final int SIGNALING_PORT = 8090;
  private static final int HANDSHAKE_TIMEOUT_MS = 5000;
  private static final int SERVER_ACCEPT_TIMEOUT_MS = 15000;
  private static final int SOCKET_READ_TIMEOUT_MS = 0;
  private static final int MAX_MSG_SIZE = 8 * 1024 * 1024; // 8 МБ — потолок для кадра
  /** ★ НОВОЕ: heartbeat для удержания WD-канала живым. */
  private java.util.concurrent.ScheduledExecutorService heartbeatExecutor;
  private static final long HEARTBEAT_INTERVAL_MS = 10_000L;

  /** ★ НОВОЕ: max попыток переподключения сокет-канала (поверх живой WD-группы). */
  private static final int MAX_SOCKET_RECONNECT_ATTEMPTS = 5;
  /** ★ НОВОЕ: задержки между попытками reconnect сокета. */
  private static final long[] SOCKET_RECONNECT_DELAYS_MS = { 1000L, 2000L, 4000L, 8000L, 15000L };

  private static final int MAX_CHANNEL_REOPEN_ATTEMPTS = 3;
  private static final long[] CHANNEL_REOPEN_DELAYS_MS = { 2000L, 5000L, 10000L };
  private final java.util.concurrent.atomic.AtomicInteger channelReopenAttempts = new java.util.concurrent.atomic.AtomicInteger(
      0);

  private static final String MSG_HELLO = "hello";
  private static final String MSG_HELLO_ACK = "hello_ack";

  /**
   * ★ ИЗМЕНЕНО (правка C.2): очередь ограничена 64 кадрами.
   *
   * БЫЛО: LinkedBlockingQueue<byte[]> без ограничения. Причина проблемы: при перегрузке TCP sendThread блокировался на out.write, очередь пухла (сотни чанков × 683 КБ = сотни МБ) → OOM или «тихая» потеря кадров; следующий sendRawFrame для кадра ≥1 МБ ждал 10 с и возвращал true (фальшивый успех).
   *
   * СТАЛО: при переполнении offer() вернёт false → sendRawFrame вернёт false → SendResult.error → DeliveryScheduler.markAttempt → retry с backoff. Никакого OOM и тихой потери.
   */
  private final java.util.concurrent.BlockingQueue<byte[]> sendQueue = new java.util.concurrent.LinkedBlockingQueue<>(
      64);

  private Thread sendThread;

  // ============================================================
  // Поля: Android WifiP2p
  // ============================================================

  private final Context appContext;
  private final MealseLogger log;
  private final Gson gson = new Gson();
  private final Handler mainHandler = new Handler(Looper.getMainLooper());

  private WifiP2pManager wifiP2pManager;
  private WifiP2pManager.Channel channel;

  private boolean opened = false;
  private boolean receiverRegistered = false;

  // ============================================================
  // Поля: состояние
  // ============================================================

  private final List<Peer> discoveredPeers = Collections.synchronizedList(new ArrayList<>());

  private volatile Peer remotePeer;
  private volatile boolean connected = false;
  private volatile boolean groupOwner = false;
  private volatile String groupOwnerAddress;
  private volatile String lastError;

  /** Список подписчиков на JSON-сигналы. */
  private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

  /** ★ Список подписчиков на файловые кадры. */
  private final CopyOnWriteArrayList<FileFrameListener> fileFrameListeners = new CopyOnWriteArrayList<>();

  /** Подписчик, установленный через deprecated setListener. */
  private volatile Listener lastSetListener = null;

  // ============================================================
  // Поля: сокет-канал
  // ============================================================

  private ServerSocket serverSocket;
  private Socket socket;
  private DataInputStream socketIn;
  private DataOutputStream socketOut;
  private Thread acceptThread;
  private Thread receiveThread;

  private final Object sendLock = new Object();

  private volatile boolean handshakeDone = false;

  private String selfDeviceId;
  private String selfFingerprint;
  private String selfName;
  /** ★ НОВОЕ: счётчик попыток reconnect сокет-канала. */
  private final AtomicInteger socketReconnectAttempts = new AtomicInteger(0);

  /**
   * ★ НОВОЕ (P5): дедуп вызовов scheduleSocketReconnect.
   *
   * Раньше scheduleSocketReconnect() вызывался из receiveLoop.finally И из sendThread.finally → +2 к socketReconnectAttempts за один обрыв. Плюс socketReconnectAttempts не сбрасывался в completeHandshake — 5 попыток исчерпывались за 2-3 реальных обрыва на длинном звонке.
   *
   * Теперь: - socketReconnectScheduled — true, пока reconnect уже запланирован (повторный вызов — no-op); - socketReconnectAttempts сбрасывается в completeHandshake.
   */
  private final java.util.concurrent.atomic.AtomicBoolean socketReconnectScheduled = new java.util.concurrent.atomic.AtomicBoolean(
      false);

  // ============================================================
  // Вложенные классы
  // ============================================================

  public static class Peer {
    public String deviceAddress;
    public String deviceName;
    public String status;
    public String deviceId;
    public String fingerprint;

    /** ★ НОВОЕ: публичный IPv6-адрес пира (2a00:...), если известен. */
    public String ipv6;

    public Peer() {
    }

    public Peer(String deviceAddress, String deviceName, String status) {
      this.deviceAddress = deviceAddress;
      this.deviceName = deviceName;
      this.status = status;
    }

    @Override
    public String toString() {
      return "Peer{" + deviceName + " / " + deviceAddress + ", status=" + status
          + (deviceId != null ? ", deviceId=" + deviceId : "")
          + (ipv6 != null ? ", ipv6=" + ipv6 : "") + "}";
    }
  }

  public interface Listener {
    void onPeersChanged(List<Peer> peers);

    void onDiscoveryStarted();

    void onDiscoveryStopped();

    void onConnected(Peer remote, boolean isGroupOwner);

    void onDisconnected();

    void onSignal(String json);

    void onError(String message);
  }

  /**
   * ★ НОВОЕ: слушатель файловых кадров. Вызывается в главном потоке.
   */
  public interface FileFrameListener {
    void onFileFrame(WifiDirectFrame.Frame frame);
  }

  public interface GroupInfoCallback {
    void onResult(boolean groupExists, boolean isGroupOwner, String networkName);
  }

  public interface SimpleCallback {
    void onDone(boolean success, String message);
  }

  // ============================================================
  // Конструктор
  // ============================================================

  public WifiDirectManager(Context context) {
    this.appContext = context.getApplicationContext();
    this.log = MealseLogger.get(this.appContext, TAG);
    log.log("══ WifiDirectManager создан ══");

    try {
      this.selfDeviceId = DeviceInfoHelper.getDeviceId(this.appContext);
    } catch (Exception e) {
      this.selfDeviceId = "unknown_device";
    }
    try {
      SecureAuthManager auth = new SecureAuthManager(this.appContext);
      this.selfFingerprint = auth.getDeviceFingerprint();
    } catch (Exception e) {
      this.selfFingerprint = "";
    }
    this.selfName = Build.MODEL != null ? Build.MODEL : "Android";

    log.log("selfDeviceId=" + selfDeviceId + ", selfFingerprint=" + selfFingerprint + ", selfName="
        + selfName);
  }

  // ============================================================
  // Проверки
  // ============================================================

  public boolean isWifiDirectSupported() {
    try {
      return appContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT);
    } catch (Exception e) {
      log.logError("isWifiDirectSupported", e);
      return false;
    }
  }

  public boolean hasPermissions() {
    try {
      if (Build.VERSION.SDK_INT >= 33) {
        return appContext.checkSelfPermission(
            "android.permission.NEARBY_WIFI_DEVICES") == PackageManager.PERMISSION_GRANTED;
      } else {
        return appContext.checkSelfPermission(
            Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
      }
    } catch (Exception e) {
      log.logError("hasPermissions", e);
      return false;
    }
  }

  // ============================================================
  // Lifecycle
  // ============================================================

  /**
   * ★ ИЗМЕНЕНО: open() теперь обёртка над openInternal(boolean).
   *
   * removeExistingGroup=true — для явного open() (пользователь/старт приложения). removeExistingGroup=false — для автоматического reopen после onChannelDisconnected (не убиваем живую группу, которую пытаемся восстановить).
   */
  public boolean open() {
    return openInternal(true);
  }

  /**
   * ★ НОВОЕ: внутренний open с флагом removeExistingGroup.
   *
   * @param removeExistingGroup true — вызвать removeGroup (явный open), false — не трогать существующую группу (auto-reopen).
   */
  private boolean openInternal(boolean removeExistingGroup) {
    if (opened) {
      log.log("open: уже открыт");
      return true;
    }

    log.log("══ open START (removeGroup=" + removeExistingGroup + ") ══");

    if (!isWifiDirectSupported()) {
      lastError = "Wi-Fi Direct не поддерживается этим устройством";
      log.error(lastError);
      notifyError(lastError);
      return false;
    }

    try {
      wifiP2pManager = (WifiP2pManager) appContext.getSystemService(Context.WIFI_P2P_SERVICE);
      if (wifiP2pManager == null) {
        lastError = "WifiP2pManager недоступен";
        log.error(lastError);
        notifyError(lastError);
        return false;
      }

      channel = wifiP2pManager.initialize(appContext, Looper.getMainLooper(), () -> {
        log.warn("WifiP2pManager: onChannelDisconnected — канал потерян");
        lastError = "Канал WifiP2p отключён системой";

        boolean wasConnected = connected;
        connected = false;
        handshakeDone = false;
        opened = false;
        stopHeartbeat();

        try {
          closeSockets();
        } catch (Exception ignored) {
        }

        if (wasConnected) {
          notifyDisconnected();
        }
        notifyError(lastError);

        scheduleChannelReopen();
      });

      if (channel == null) {
        lastError = "Не удалось создать WifiP2pChannel";
        log.error(lastError);
        notifyError(lastError);
        return false;
      }

      registerReceiver();
      opened = true;

      if (removeExistingGroup) {
        try {
          wifiP2pManager.removeGroup(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
              log.log("open: removeGroup OK");
            }

            @Override
            public void onFailure(int reason) {
              log.log("open: removeGroup fail reason=" + reason);
            }
          });
        } catch (Exception e) {
          log.logError("open: removeGroup", e);
        }
      } else {
        log.log("open: removeGroup пропущен (auto-reopen, сохраняем группу)");
      }

      log.log("open OK");
      return true;

    } catch (Exception e) {
      lastError = "Ошибка open: " + e.getMessage();
      log.logError("open FAILED", e);
      notifyError(lastError);
      return false;
    }
  }

  /**
   * ★ НОВОЕ: автоматическое восстановление Wi-Fi P2P channel после onChannelDisconnected. Android может уничтожить channel при простое (нет трафика), смене Wi-Fi сети, энергосбережении.
   *
   * Пытаемся открыть заново 3 раза с задержками 2с → 5с → 10с. Если все 3 неудачи — сообщаем error, дальше только ручной open().
   */
  /**
   * ★ ИЗМЕНЕНО: после успешного reopen не удаляем группу и просим connection info, чтобы поверх живой группы переподнять сокеты.
   */
  private void scheduleChannelReopen() {
    if (channelReopenAttempts.get() >= MAX_CHANNEL_REOPEN_ATTEMPTS) {
      log.warn("scheduleChannelReopen: исчерпаны попытки (" + channelReopenAttempts.get()
          + ") — сдаёмся");
      return;
    }

    int attempt = channelReopenAttempts.incrementAndGet();
    long delayMs = CHANNEL_REOPEN_DELAYS_MS[Math.min(attempt - 1,
        CHANNEL_REOPEN_DELAYS_MS.length - 1)];
    log.log("scheduleChannelReopen: попытка " + attempt + "/" + MAX_CHANNEL_REOPEN_ATTEMPTS
        + " через " + delayMs + "мс");

    mainHandler.postDelayed(() -> {
      log.log("scheduleChannelReopen: выполняю попытку " + attempt);
      opened = false;
      boolean ok = openInternal(false); // ★ не убиваем группу
      if (ok) {
        log.log("scheduleChannelReopen: попытка " + attempt + " успешна");
        channelReopenAttempts.set(0);
        socketReconnectAttempts.set(0);
        // ★ Группа могла уцелеть — переподнимаем сокеты поверх неё
        requestConnectionInfo();
      } else {
        log.warn("scheduleChannelReopen: попытка " + attempt + " неудачна");
        scheduleChannelReopen();
      }
    }, delayMs);
  }

  public synchronized void close() {
    log.log("══ close START ══");

    try {
      stopDiscovery();
    } catch (Exception ignored) {
    }

    // ★ Явно останавливаем heartbeat до закрытия сокетов.
    stopHeartbeat();

    try {
      closeSockets();
    } catch (Exception e) {
      log.logError("close: closeSockets", e);
    }

    try {
      if (wifiP2pManager != null && channel != null) {
        wifiP2pManager.removeGroup(channel, new WifiP2pManager.ActionListener() {
          @Override
          public void onSuccess() {
            log.log("removeGroup OK");
          }

          @Override
          public void onFailure(int reason) {
            log.warn("removeGroup fail reason=" + reason);
          }
        });
      }
    } catch (Exception e) {
      log.logError("close: removeGroup", e);
    }

    try {
      unregisterReceiver();
    } catch (Exception e) {
      log.logError("close: unregisterReceiver", e);
    }

    try {
      if (channel != null) {
        channel.close();
        log.log("channel closed");
      }
    } catch (Exception e) {
      log.logError("close: channel.close", e);
    }

    channel = null;
    wifiP2pManager = null;
    opened = false;
    connected = false;
    groupOwner = false;
    groupOwnerAddress = null;
    remotePeer = null;
    handshakeDone = false;

    synchronized (discoveredPeers) {
      discoveredPeers.clear();
    }

    log.log("══ close END ══");
  }

  // ============================================================
  // Реальная проверка состояния группы
  // ============================================================

  public void requestRealGroupInfo(GroupInfoCallback callback) {
    if (!opened || wifiP2pManager == null || channel == null) {
      log.warn("requestRealGroupInfo: manager не открыт");
      if (callback != null)
        callback.onResult(false, false, null);
      return;
    }
    try {
      wifiP2pManager.requestGroupInfo(channel, group -> {
        if (group == null) {
          if (callback != null)
            callback.onResult(false, false, null);
        } else {
          if (callback != null) {
            callback.onResult(true, group.isGroupOwner(), group.getNetworkName());
          }
        }
      });
    } catch (Exception e) {
      log.logError("requestRealGroupInfo", e);
      if (callback != null)
        callback.onResult(false, false, null);
    }
  }

  public void forceRemoveGroup(SimpleCallback callback) {
    if (wifiP2pManager == null || channel == null) {
      if (callback != null)
        callback.onDone(false, "manager не открыт");
      return;
    }
    try {
      wifiP2pManager.removeGroup(channel, new WifiP2pManager.ActionListener() {
        @Override
        public void onSuccess() {
          if (callback != null)
            callback.onDone(true, "группа удалена");
        }

        @Override
        public void onFailure(int reason) {
          if (callback != null)
            callback.onDone(false, "reason=" + reason);
        }
      });
    } catch (Exception e) {
      log.logError("forceRemoveGroup", e);
      if (callback != null)
        callback.onDone(false, e.getMessage());
    }
  }

  // ============================================================
  // BroadcastReceiver
  // ============================================================

  private final BroadcastReceiver p2pReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      if (intent == null)
        return;
      String action = intent.getAction();
      if (action == null)
        return;

      try {
        switch (action) {
          case WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION: {
            int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
            boolean enabled = (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED);
            log.log("P2P_STATE_CHANGED: enabled=" + enabled);
            if (!enabled) {
              lastError = "Wi-Fi Direct выключен системой";
              notifyError(lastError);
            }
            break;
          }
          case WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION:
            requestPeers();
            break;
          case WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION:
            requestConnectionInfo();
            break;
          case WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION:
            break;
        }
      } catch (Exception e) {
        log.logError("p2pReceiver error", e);
      }
    }
  };

  private void registerReceiver() {
    if (receiverRegistered)
      return;

    IntentFilter filter = new IntentFilter();
    filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
    filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
    filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
    filter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      appContext.registerReceiver(p2pReceiver, filter, Context.RECEIVER_EXPORTED);
    } else {
      appContext.registerReceiver(p2pReceiver, filter);
    }
    receiverRegistered = true;
  }

  private void unregisterReceiver() {
    if (!receiverRegistered)
      return;
    try {
      appContext.unregisterReceiver(p2pReceiver);
    } catch (IllegalArgumentException ignored) {
    } catch (Exception e) {
      log.logError("unregisterReceiver", e);
    }
    receiverRegistered = false;
  }

  // ============================================================
  // Discovery
  // ============================================================

  public void startDiscovery() {
    if (!opened) {
      lastError = "WifiDirectManager не открыт";
      notifyError(lastError);
      return;
    }
    if (!hasPermissions()) {
      lastError = "Нет разрешения для Wi-Fi Direct discovery";
      notifyError(lastError);
      return;
    }

    try {
      wifiP2pManager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
        @Override
        public void onSuccess() {
          notifyDiscoveryStarted();
        }

        @Override
        public void onFailure(int reason) {
          lastError = "discoverPeers fail reason=" + reason;
          notifyError(lastError);
        }
      });
    } catch (Exception e) {
      log.logError("startDiscovery", e);
      lastError = "Ошибка startDiscovery: " + e.getMessage();
      notifyError(lastError);
    }
  }

  public void stopDiscovery() {
    if (!opened || wifiP2pManager == null || channel == null)
      return;
    try {
      wifiP2pManager.stopPeerDiscovery(channel, new WifiP2pManager.ActionListener() {
        @Override
        public void onSuccess() {
          notifyDiscoveryStopped();
        }

        @Override
        public void onFailure(int reason) {
          log.warn("stopPeerDiscovery fail reason=" + reason);
        }
      });
    } catch (Exception e) {
      log.logError("stopDiscovery", e);
    }
  }

  private void requestPeers() {
    if (wifiP2pManager == null || channel == null)
      return;
    try {
      wifiP2pManager.requestPeers(channel, this::onPeersAvailable);
    } catch (Exception e) {
      log.logError("requestPeers", e);
    }
  }

  private void onPeersAvailable(WifiP2pDeviceList deviceList) {
    if (deviceList == null)
      return;

    List<Peer> peers = new ArrayList<>();
    for (WifiP2pDevice d : deviceList.getDeviceList()) {
      peers.add(new Peer(d.deviceAddress, d.deviceName, statusToString(d.status)));
    }

    synchronized (discoveredPeers) {
      discoveredPeers.clear();
      discoveredPeers.addAll(peers);
    }
    notifyPeersChanged(peers);
  }

  private static String statusToString(int status) {
    switch (status) {
      case WifiP2pDevice.AVAILABLE:
        return "available";
      case WifiP2pDevice.INVITED:
        return "invited";
      case WifiP2pDevice.CONNECTED:
        return "connected";
      case WifiP2pDevice.FAILED:
        return "failed";
      case WifiP2pDevice.UNAVAILABLE:
        return "unavailable";
      default:
        return "unknown";
    }
  }

  public List<Peer> getDiscoveredPeers() {
    synchronized (discoveredPeers) {
      return new ArrayList<>(discoveredPeers);
    }
  }

  // ============================================================
  // Connect
  // ============================================================

  public void connect(Peer peer, boolean asGroupOwner) {
    if (!opened) {
      lastError = "WifiDirectManager не открыт";
      notifyError(lastError);
      return;
    }
    if (peer == null || peer.deviceAddress == null) {
      lastError = "connect: peer == null";
      notifyError(lastError);
      return;
    }

    try {
      WifiP2pConfig config = new WifiP2pConfig();
      config.deviceAddress = peer.deviceAddress;
      config.wps.setup = android.net.wifi.WpsInfo.PBC;
      config.groupOwnerIntent = asGroupOwner ? 15 : 0;

      wifiP2pManager.connect(channel, config, new WifiP2pManager.ActionListener() {
        @Override
        public void onSuccess() {
          remotePeer = peer;
        }

        @Override
        public void onFailure(int reason) {
          lastError = "connect fail reason=" + reason;
          notifyError(lastError);
        }
      });
    } catch (Exception e) {
      log.logError("connect", e);
      lastError = "Ошибка connect: " + e.getMessage();
      notifyError(lastError);
    }
  }

  private void requestConnectionInfo() {
    if (wifiP2pManager == null || channel == null)
      return;
    try {
      wifiP2pManager.requestConnectionInfo(channel, this::onConnectionInfoAvailable);
    } catch (Exception e) {
      log.logError("requestConnectionInfo", e);
    }
  }

  /**
   * ★ ИЗМЕНЕНО: если канал уже жив (connected && handshakeDone && socketOut), повторный CONNECTION_CHANGED не трогаем — иначе можно пересоздать только что установленное соединение.
   */
  /**
   * ★ ИЗМЕНЕНО (P5): при !groupFormed — сброс socketReconnectScheduled (разрешаем новый цикл reconnect, когда группа появится снова).
   */
  private void onConnectionInfoAvailable(WifiP2pInfo info) {
    if (info == null)
      return;

    if (!info.groupFormed) {
      boolean wasConnected = connected;
      connected = false;
      groupOwner = false;
      groupOwnerAddress = null;
      socketReconnectAttempts.set(0);
      socketReconnectScheduled.set(false); // ★ P5
      closeSockets();
      if (wasConnected)
        notifyDisconnected();
      return;
    }

    groupOwner = info.isGroupOwner;
    groupOwnerAddress = info.groupOwnerAddress != null ? info.groupOwnerAddress.getHostAddress()
        : null;

    // ★ Канал уже жив — повторный CONNECTION_CHANGED не трогаем
    if (connected && handshakeDone && socketOut != null) {
      log.log("onConnectionInfoAvailable: канал уже жив — пропуск");
      return;
    }

    if (groupOwner) {
      startSignalingServer();
    } else {
      if (groupOwnerAddress == null) {
        lastError = "groupOwnerAddress == null у клиента";
        notifyError(lastError);
        return;
      }
      connectToSignalingServer();
    }
  }

  // ============================================================
  // Сокет-канал
  // ============================================================

  /**
   * ★ ИЗМЕНЕНО: цикл accept вместо одного accept + фикс NPE буферов (ставим на incoming, а не на null-поле socket).
   *
   * После обрыва соединения клиент может переподключиться — accept-loop примет его, не пересоздавая группу.
   */
  private void startSignalingServer() {
    if (acceptThread != null && acceptThread.isAlive())
      return;

    acceptThread = new Thread(() -> {
      try {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.setSoTimeout(SERVER_ACCEPT_TIMEOUT_MS);
        serverSocket.bind(new InetSocketAddress(SIGNALING_PORT));
        log.log("startSignalingServer: слушаю порт " + SIGNALING_PORT);

        // ★ Цикл: после обрыва принимаем повторное подключение клиента
        while (opened && !Thread.currentThread().isInterrupted()) {
          Socket incoming;
          try {
            incoming = serverSocket.accept();
          } catch (java.net.SocketTimeoutException ste) {
            continue; // таймаут accept — просто повторяем
          }
          if (connected && handshakeDone) {
            // Уже есть живое соединение — лишнее закрываем
            try {
              incoming.close();
            } catch (IOException ignored) {
            }
            continue;
          }
          incoming.setSoTimeout(SOCKET_READ_TIMEOUT_MS);
          incoming.setTcpNoDelay(true);
          // ★ ФИКС: буферы на incoming, а не на null-поле socket
          try {
            incoming.setSendBufferSize(512 * 1024);
            incoming.setReceiveBufferSize(512 * 1024);
          } catch (Exception e) {
            log.warn("setBuffers: " + e.getMessage());
          }

          synchronized (WifiDirectManager.this) {
            socket = incoming;
            socketIn = new DataInputStream(incoming.getInputStream());
            socketOut = new DataOutputStream(incoming.getOutputStream());
            connected = true;
          }
          sendHello();
          startReceiveLoop();

          // Ждём, пока это соединение живо, затем снова accept
          while (connected && opened && !Thread.currentThread().isInterrupted()) {
            try {
              Thread.sleep(500);
            } catch (InterruptedException ie) {
              Thread.currentThread().interrupt();
              break;
            }
          }
        }
      } catch (IOException e) {
        if (opened && !Thread.currentThread().isInterrupted()) {
          log.logError("startSignalingServer: IOException", e);
          lastError = "ServerSocket: " + e.getMessage();
          notifyError(lastError);
        }
      } catch (Exception e) {
        log.logError("startSignalingServer: Exception", e);
      } finally {
        try {
          if (serverSocket != null && !serverSocket.isClosed())
            serverSocket.close();
        } catch (IOException ignored) {
        }
        serverSocket = null;
        log.log("startSignalingServer: accept-loop завершён");
      }
    }, "WifiDirectAcceptThread");
    acceptThread.start();
  }

  /**
   * ★ ИЗМЕНЕНО: буферы на локальном s (а не на null-поле socket), при ошибке — scheduleSocketReconnect() с backoff.
   */
  private void connectToSignalingServer() {
    if (socket != null && socket.isConnected() && !socket.isClosed() && connected)
      return;

    new Thread(() -> {
      try {
        Socket s = new Socket();
        s.setTcpNoDelay(true);
        s.connect(new InetSocketAddress(groupOwnerAddress, SIGNALING_PORT), HANDSHAKE_TIMEOUT_MS);
        s.setSoTimeout(SOCKET_READ_TIMEOUT_MS);
        // ★ ФИКС: буферы на s, до synchronized
        try {
          s.setSendBufferSize(512 * 1024);
          s.setReceiveBufferSize(512 * 1024);
        } catch (Exception e) {
          log.warn("setBuffers: " + e.getMessage());
        }

        synchronized (WifiDirectManager.this) {
          socket = s;
          socketIn = new DataInputStream(s.getInputStream());
          socketOut = new DataOutputStream(s.getOutputStream());
          connected = true;
        }
        sendHello();
        startReceiveLoop();
      } catch (IOException e) {
        log.logError("connectToSignalingServer: IOException", e);
        lastError = "Подключение к GO: " + e.getMessage();
        notifyError(lastError);
        // ★ НОВОЕ: автоматический reconnect с backoff
        scheduleSocketReconnect();
      }
    }, "WifiDirectConnectThread").start();
  }

  private void startReceiveLoop() {
    receiveThread = new Thread(this::receiveLoop, "WifiDirectReceiveThread");
    receiveThread.start();
  }

  /**
   * ★ ИЗМЕНЕНО: читаем новый формат [uint32 len][uint8 type][payload], с fallback на старый формат [uint32 len][JSON UTF-8] — если первый байт payload не 0x01..0x06.
   */
  /**
   * ★ ИЗМЕНЕНО: в finally — scheduleSocketReconnect() (переподнять сокет поверх живой группы).
   */
  private void receiveLoop() {
    log.log("receiveLoop START");

    try {
      while (connected && !Thread.currentThread().isInterrupted()) {
        int frameLength;
        try {
          frameLength = socketIn.readInt();
        } catch (IOException e) {
          if (connected)
            log.log("receiveLoop: readInt — " + e.getMessage());
          break;
        }

        if (frameLength <= 0 || frameLength > MAX_MSG_SIZE) {
          log.warn("receiveLoop: неверная frameLength=" + frameLength);
          break;
        }

        byte[] frameBytes = new byte[frameLength];
        try {
          socketIn.readFully(frameBytes);
        } catch (IOException e) {
          if (connected)
            log.log("receiveLoop: readFully — " + e.getMessage());
          break;
        }

        int firstByte = frameBytes[0] & 0xFF;
        if (firstByte >= 0x01 && firstByte <= 0x06) {
          handleNewFormatFrame(frameBytes);
        } else {
          String json = new String(frameBytes, StandardCharsets.UTF_8);
          handleIncoming(json);
        }
      }
    } catch (Exception e) {
      log.logError("receiveLoop: ошибка", e);
    } finally {
      boolean wasConnected;
      synchronized (this) {
        wasConnected = connected;
      }
      try {
        closeSockets();
      } catch (Exception ignored) {
      }
      if (wasConnected) {
        log.warn("receiveLoop: соединение потеряно → notifyDisconnected + reconnect");
        notifyDisconnected();
      }
      // ★ НОВОЕ: автоматический reconnect сокета
      scheduleSocketReconnect();
      log.log("receiveLoop END");
    }
  }

  /**
   * ★ НОВОЕ: переподключение сокет-канала поверх существующей WD-группы.
   *
   * Вызывается из receiveLoop.finally и connectToSignalingServer при ошибке. До 5 попыток с задержками 1с → 2с → 4с → 8с → 15с.
   *
   * В отличие от scheduleChannelReopen (переоткрывает WifiP2pManager), этот метод НЕ трогает group — только переподнимает TCP-сокет.
   */
  /**
   * ★ ИЗМЕНЕНО (P5): дедуп через socketReconnectScheduled.
   *
   * Раньше: receiveLoop.finally и sendThread.finally вызывали этот метод одновременно → +2 к socketReconnectAttempts за один обрыв → бюджет (5 попыток) исчерпывался за 2-3 реальных обрыва.
   *
   * Теперь: только первый вызов планирует reconnect; повторные — no-op, пока запланированный не выполнится (или не будет отменён при connected=true).
   */
  private void scheduleSocketReconnect() {
    if (!opened || connected)
      return;

    // ★ P5: дедуп — если reconnect уже запланирован, не плодим попытки
    if (!socketReconnectScheduled.compareAndSet(false, true)) {
      log.log("scheduleSocketReconnect: уже запланирован — пропуск");
      return;
    }

    int attempt = socketReconnectAttempts.incrementAndGet();
    if (attempt > MAX_SOCKET_RECONNECT_ATTEMPTS) {
      log.warn("scheduleSocketReconnect: исчерпаны попытки");
      socketReconnectScheduled.set(false);
      return;
    }
    long delay = SOCKET_RECONNECT_DELAYS_MS[Math.min(attempt - 1,
        SOCKET_RECONNECT_DELAYS_MS.length - 1)];
    log.log("scheduleSocketReconnect: попытка " + attempt + " через " + delay + "мс");
    mainHandler.postDelayed(() -> {
      socketReconnectScheduled.set(false); // ★ P5: разрешаем следующий цикл
      if (!opened || connected)
        return;
      if (groupOwner) {
        startSignalingServer(); // accept-loop примет клиента
      } else if (groupOwnerAddress != null) {
        connectToSignalingServer();
      } else {
        requestConnectionInfo(); // узнать GO заново
      }
    }, delay);
  }

  /**
   * Разбирает новый формат: [uint8 type][payload].
   */
  private void handleNewFormatFrame(byte[] frameBytes) {
    WifiDirectFrame.Frame frame = WifiDirectFrame.parse(frameBytes);
    if (frame == null) {
      log.warn("handleNewFormatFrame: parse вернул null");
      return;
    }

    switch (frame.type) {
      case WifiDirectFrame.TYPE_JSON_SIGNAL:
        // JSON-сигнал (hello, hello_ack, offer, answer, candidate, bye)
        handleIncoming(WifiDirectFrame.bodyToJson(frame.body));
        break;

      case WifiDirectFrame.TYPE_FS_REQUEST:
      case WifiDirectFrame.TYPE_FS_RESPONSE:
      case WifiDirectFrame.TYPE_FS_CHUNK:
      case WifiDirectFrame.TYPE_FS_ERROR:
      case WifiDirectFrame.TYPE_FS_CANCEL:
        // Файловые кадры — рассылаем файловым подписчикам
        dispatchFileFrame(frame);
        break;

      default:
        log.warn("handleNewFormatFrame: неизвестный type=0x" + Integer.toHexString(frame.type));
    }
  }

  private void handleIncoming(String json) {
    try {
      JsonObject obj = gson.fromJson(json, JsonObject.class);
      if (obj == null || !obj.has("type")) {
        log.warn("handleIncoming: нет поля type");
        return;
      }

      String type = obj.get("type").getAsString();

      if (MSG_HELLO.equals(type)) {
        handleHello(obj);
        return;
      }
      if (MSG_HELLO_ACK.equals(type)) {
        handleHelloAck(obj);
        return;
      }

      notifySignal(json);
    } catch (Exception e) {
      log.logError("handleIncoming", e);
    }
  }

  // ============================================================
  // Handshake
  // ============================================================

  private void sendHello() {
    try {
      JsonObject obj = new JsonObject();
      obj.addProperty("type", MSG_HELLO);
      obj.addProperty("device_id", selfDeviceId);
      obj.addProperty("fingerprint", selfFingerprint);
      obj.addProperty("name", selfName);
      obj.addProperty("is_group_owner", groupOwner);

      // ★ НОВОЕ: сообщаем свой публичный IPv6 (если есть) — чтобы пир
      // мог использовать его для прямого транспорта без HTTP-запроса.
      try {
        String selfV6 = DeviceInfoHelper.getIpv6Address(appContext);
        if (selfV6 != null && !selfV6.isEmpty()) {
          obj.addProperty("ipv6", selfV6);
          log.log("sendHello: отдаём свой ipv6=" + selfV6);
        }
      } catch (Exception e) {
        log.warn("sendHello: getIpv6Address: " + e.getMessage());
      }

      sendRawJson(obj.toString());
    } catch (Exception e) {
      log.logError("sendHello", e);
    }
  }

  private void handleHello(JsonObject obj) {
    try {
      // ★ НОВОЕ: heartbeat — не handshake, не отвечаем, не завершаем handshake
      if (obj.has("heartbeat") && obj.get("heartbeat").getAsBoolean()) {
        log.log("handleHello: heartbeat получен — продлеваю канал");
        return;
      }
      String deviceId = obj.has("device_id") ? obj.get("device_id").getAsString() : null;
      String fp = obj.has("fingerprint") ? obj.get("fingerprint").getAsString() : null;
      String name = obj.has("name") ? obj.get("name").getAsString() : "Unknown";

      // ★ НОВОЕ: читаем IPv6 пира
      String peerV6 = null;
      if (obj.has("ipv6") && !obj.get("ipv6").isJsonNull()) {
        try {
          peerV6 = obj.get("ipv6").getAsString();
        } catch (Exception ignored) {
        }
      }

      if (remotePeer == null)
        remotePeer = new Peer(null, name, "connected");
      remotePeer.deviceId = deviceId;
      remotePeer.fingerprint = fp;
      remotePeer.deviceName = name;
      if (peerV6 != null && !peerV6.isEmpty()) {
        remotePeer.ipv6 = peerV6;
        log.log("handleHello: получен ipv6 пира=" + peerV6);
      }

      JsonObject ack = new JsonObject();
      ack.addProperty("type", MSG_HELLO_ACK);
      ack.addProperty("device_id", selfDeviceId);
      ack.addProperty("fingerprint", selfFingerprint);
      ack.addProperty("name", selfName);
      ack.addProperty("is_group_owner", groupOwner);

      // ★ НОВОЕ: отвечаем своим IPv6
      try {
        String selfV6 = DeviceInfoHelper.getIpv6Address(appContext);
        if (selfV6 != null && !selfV6.isEmpty()) {
          ack.addProperty("ipv6", selfV6);
          log.log("handleHello: отдаём свой ipv6=" + selfV6);
        }
      } catch (Exception e) {
        log.warn("handleHello: getIpv6Address: " + e.getMessage());
      }

      sendRawJson(ack.toString());

      completeHandshake();
    } catch (Exception e) {
      log.logError("handleHello", e);
    }
  }

  private void handleHelloAck(JsonObject obj) {
    try {
      if (obj.has("heartbeat") && obj.get("heartbeat").getAsBoolean()) {
        log.log("handleHello: heartbeat получен — продлеваю канал");
        return;
      }
      String deviceId = obj.has("device_id") ? obj.get("device_id").getAsString() : null;
      String fp = obj.has("fingerprint") ? obj.get("fingerprint").getAsString() : null;
      String name = obj.has("name") ? obj.get("name").getAsString() : "Unknown";

      // ★ НОВОЕ: читаем IPv6 пира
      String peerV6 = null;
      if (obj.has("ipv6") && !obj.get("ipv6").isJsonNull()) {
        try {
          peerV6 = obj.get("ipv6").getAsString();
        } catch (Exception ignored) {
        }
      }

      if (remotePeer == null)
        remotePeer = new Peer(null, name, "connected");
      remotePeer.deviceId = deviceId;
      remotePeer.fingerprint = fp;
      remotePeer.deviceName = name;
      if (peerV6 != null && !peerV6.isEmpty()) {
        remotePeer.ipv6 = peerV6;
        log.log("handleHelloAck: получен ipv6 пира=" + peerV6);
      }

      completeHandshake();
    } catch (Exception e) {
      log.logError("handleHelloAck", e);
    }
  }

  /**
   * ★ ИЗМЕНЕНО (P5): сброс socketReconnectAttempts и socketReconnectScheduled после успешного handshake. Иначе бюджет reconnect исчерпывался за 2-3 реальных обрыва на длинном звонке (счётчик не сбрасывался).
   */
  private void completeHandshake() {
    if (handshakeDone)
      return;
    handshakeDone = true;

    // ★ P5: успешный handshake — сбрасываем бюджет reconnect
    socketReconnectAttempts.set(0);
    socketReconnectScheduled.set(false);

    // ★ КРИТИЧНО: без heartbeat TCP-сокет простаивает → Android
    // закрывает Wi-Fi P2P channel через ~30-60 секунд простоя →
    // receiveLoop получает RST → соединение рвётся.
    // Это и был обрыв WD через ~30 секунд после последней активности.
    startHeartbeat();

    final Peer rp = remotePeer;
    final boolean go = groupOwner;
    mainHandler.post(() -> notifyConnectedInternal(rp, go));
    log.log("completeHandshake: OK, heartbeat запущен, socketReconnectAttempts=0");
  }

  // ============================================================
  // Отправка сигналов (JSON) — оборачивается в TYPE_JSON_SIGNAL
  // ============================================================

  /**
   * ★ ИЗМЕНЕНО: возвращает true если кадр реально поставлен в очередь на отправку. false — если нет соединения или очередь переполнена. Это позволяет WifiDirectMessageSender корректно сообщить об ошибке и не помечать сообщение как SENT.
   */
  public boolean sendSignal(String json) {
    if (!connected || socketOut == null) {
      log.warn("sendSignal: нет соединения — кадр не поставлен в очередь (length="
          + (json != null ? json.length() : -1) + ")");
      return false;
    }
    return sendRawJson(json);
  }

  private boolean sendRawJson(String json) {
    byte[] frame = WifiDirectFrame.packJson(WifiDirectFrame.TYPE_JSON_SIGNAL, json);
    return sendRawFrame(frame);
  }

  /**
   * ★ ИЗМЕНЕНО: возвращает boolean — успешно ли поставлено в очередь.
   */
  public boolean sendFileFrame(byte[] frame) {
    if (!connected || socketOut == null) {
      log.warn("sendFileFrame: нет соединения — кадр не поставлен (len="
          + (frame != null ? frame.length : 0) + ")");
      return false;
    }
    return sendRawFrame(frame);
  }

  /**
   * ★ ИЗМЕНЕНО (правка C.1): для кадров ≥1 МБ, если очередь не опустела за 10 с, возвращаем false (а не true).
   *
   * БЫЛО: return true даже если очередь не опустела → WifiDirectMessageSender ставил фальшивый SENT → DeliveryScheduler не делал retry → сообщение «терялось». Для файлов-чанков (683 КБ base64) это не срабатывало, но при перегрузке TCP-буфера sendThread блокировался на out.write, очередь росла, и следующий чанк мог «провалиться» так же.
   *
   * СТАЛО: false → SendResult.error → markAttempt → retry с backoff.
   */
  private boolean sendRawFrame(byte[] frame) {
    if (frame == null)
      return false;
    if (!connected || socketOut == null) {
      log.warn(
          "sendRawFrame: нет соединения — кадр не ставим в очередь (len=" + frame.length + ")");
      return false;
    }
    startSendThreadIfNeeded();
    boolean offered = sendQueue.offer(frame);
    if (!offered) {
      log.warn("sendRawFrame: очередь переполнена — кадр потерян (len=" + frame.length + ")");
      return false;
    }

    if (frame.length >= 1024 * 1024) {
      long deadline = System.currentTimeMillis() + 10_000;
      while (!sendQueue.isEmpty() && connected && System.currentTimeMillis() < deadline) {
        try {
          Thread.sleep(20);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          return false;
        }
      }
      if (!sendQueue.isEmpty()) {
        log.warn("sendRawFrame: очередь не опустела за 10с (len=" + sendQueue.size() + ", frame="
            + frame.length + "B) — возвращаем false для retry");
        return false;
      }
    }
    return true;
  }

  /**
   * ★ ИЗМЕНЕНО (правка C.3): при IOException sendThread не рвёт соединение сразу, а даёт 2 попытки с паузой 500 мс.
   *
   * Причина: TCP-буфер мог временно переполниться (sendThread писал чанк 683 КБ, приёмник не успевал читать) → IOException. Раньше: break → closeSockets() → notifyDisconnected() → обрыв WD.
   *
   * Теперь: 2 подряд IOException — тогда реальный обрыв. Один — повтор.
   */
  /**
   * ★ ИЗМЕНЕНО: sendThread пишет под sendLock (единый писатель).
   *
   * ★ ИСПРАВЛЕНО описание: heartbeat УЖЕ писал под synchronized (sendLock), а sendThread — БЕЗ него. Интерливинг кадров вызывал именно sendThread. Теперь heartbeat идёт через очередь (startHeartbeat → sendRawJson), sendThread — единственный, кто пишет в socketOut, и делает это под lock.
   */
  private void startSendThreadIfNeeded() {
    if (sendThread != null && sendThread.isAlive())
      return;

    sendThread = new Thread(() -> {
      int consecutiveIoErrors = 0;
      while (connected || !sendQueue.isEmpty()) {
        try {
          byte[] frame = sendQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
          if (frame == null)
            continue;
          // ★ Единый lock: запись под sendLock, как и heartbeat (через очередь)
          synchronized (sendLock) {
            DataOutputStream out = this.socketOut;
            if (out == null) {
              log.warn("sendThread: socketOut == null — выходим");
              break;
            }
            out.write(frame);
            out.flush();
          }
          consecutiveIoErrors = 0;
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          break;
        } catch (IOException e) {
          consecutiveIoErrors++;
          log.warn("sendThread: IOException #" + consecutiveIoErrors + " — " + e.getMessage());
          if (consecutiveIoErrors >= 2) {
            log.logError("sendThread: 2 подряд IOException — реальный обрыв", e);
            break;
          }
          try {
            Thread.sleep(500);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            break;
          }
        }
      }

      int dropped = sendQueue.size();
      if (dropped > 0) {
        sendQueue.clear();
        log.warn("sendThread END: очищено " + dropped + " неотправленных кадров");
      }
      log.log("sendThread END");

      boolean wasConnected;
      synchronized (WifiDirectManager.this) {
        wasConnected = connected;
      }
      if (wasConnected) {
        try {
          closeSockets();
        } catch (Exception ignored) {
        }
        notifyDisconnected();
        scheduleSocketReconnect(); // ★ НОВОЕ
      }
    }, "WifiDirectSendThread");
    sendThread.start();
  }

  // ============================================================
  // FileFrameListener API
  // ============================================================

  public void addFileFrameListener(FileFrameListener l) {
    if (l == null)
      return;
    if (fileFrameListeners.addIfAbsent(l)) {
      log.log("addFileFrameListener: listeners=" + fileFrameListeners.size());
    }
  }

  public void removeFileFrameListener(FileFrameListener l) {
    if (l == null)
      return;
    if (fileFrameListeners.remove(l)) {
      log.log("removeFileFrameListener: listeners=" + fileFrameListeners.size());
    }
  }

  private void dispatchFileFrame(WifiDirectFrame.Frame frame) {
    mainHandler.post(() -> {
      for (FileFrameListener l : fileFrameListeners) {
        try {
          l.onFileFrame(frame);
        } catch (Exception e) {
          log.logError("dispatchFileFrame: " + l.getClass().getSimpleName(), e);
        }
      }
    });
  }

  /**
   * Готов ли файловый канал (соединение установлено и handshake прошёл).
   */
  public boolean isFileChannelReady() {
    return isConnected() && socketOut != null;
  }

  // ============================================================
  // Закрытие сокетов
  // ============================================================

  /**
   * ★ ИЗМЕНЕНО: останавливает sendThread и чистит очередь. Иначе после closeSockets sendThread мог продолжать писать в мёртвый socketOut (IOException), а кадры в sendQueue копились до OOM.
   */
  private synchronized void closeSockets() {
    connected = false;
    handshakeDone = false;

    stopHeartbeat();

    if (acceptThread != null) {
      acceptThread.interrupt();
      acceptThread = null;
    }
    if (receiveThread != null) {
      receiveThread.interrupt();
      receiveThread = null;
    }
    if (sendThread != null) { // ★ НОВОЕ
      sendThread.interrupt();
      sendThread = null;
    }
    sendQueue.clear(); // ★ НОВОЕ

    try {
      if (socketIn != null) {
        socketIn.close();
        socketIn = null;
      }
    } catch (IOException ignored) {
    }
    try {
      if (socketOut != null) {
        socketOut.close();
        socketOut = null;
      }
    } catch (IOException ignored) {
    }
    try {
      if (socket != null && !socket.isClosed())
        socket.close();
    } catch (IOException ignored) {
    }
    socket = null;

    try {
      if (serverSocket != null && !serverSocket.isClosed())
        serverSocket.close();
    } catch (IOException ignored) {
    }
    serverSocket = null;
  }

  // ============================================================
  // Публичные геттеры состояния
  // ============================================================

  public boolean isConnected() {
    // ★ ИСПРАВЛЕНО: проверять socketOut != null — иначе после closeSockets
    // (receiveLoop END) isConnected() возвращает true, но отправка падает.
    return connected && handshakeDone && socketOut != null;
  }

  public boolean isGroupOwner() {
    return groupOwner;
  }

  public String getGroupOwnerAddress() {
    return groupOwnerAddress;
  }

  public Peer getRemotePeer() {
    return remotePeer;
  }

  public String getLastError() {
    return lastError;
  }

  public boolean isOpened() {
    return opened;
  }

  // ============================================================
  // Listener (JSON-сигналы)
  // ============================================================

  public void addListener(Listener l) {
    if (l == null)
      return;
    if (listeners.addIfAbsent(l)) {
      if (connected && handshakeDone) {
        final Peer rp = remotePeer;
        final boolean go = groupOwner;
        mainHandler.post(() -> l.onConnected(rp, go));
      }
    }
  }

  public void removeListener(Listener l) {
    if (l == null)
      return;
    listeners.remove(l);
  }

  public void clearListeners() {
    listeners.clear();
    lastSetListener = null;
  }

  public int getListenerCount() {
    return listeners.size();
  }

  @Deprecated
  public void setListener(Listener l) {
    if (lastSetListener != null) {
      listeners.remove(lastSetListener);
      lastSetListener = null;
    }
    if (l != null) {
      addListener(l);
      lastSetListener = l;
    }
  }

  private void notifyPeersChanged(List<Peer> peers) {
    mainHandler.post(() -> {
      for (Listener l : listeners) {
        try {
          l.onPeersChanged(peers);
        } catch (Exception e) {
          log.logError("notifyPeersChanged", e);
        }
      }
    });
  }

  private void notifyDiscoveryStarted() {
    mainHandler.post(() -> {
      for (Listener l : listeners) {
        try {
          l.onDiscoveryStarted();
        } catch (Exception e) {
          log.logError("notifyDiscoveryStarted", e);
        }
      }
    });
  }

  private void notifyDiscoveryStopped() {
    mainHandler.post(() -> {
      for (Listener l : listeners) {
        try {
          l.onDiscoveryStopped();
        } catch (Exception e) {
          log.logError("notifyDiscoveryStopped", e);
        }
      }
    });
  }

  private void notifyDisconnected() {
    mainHandler.post(() -> {
      for (Listener l : listeners) {
        try {
          l.onDisconnected();
        } catch (Exception e) {
          log.logError("notifyDisconnected", e);
        }
      }
    });
  }

  private void notifySignal(String json) {
    final List<Listener> snapshot = new ArrayList<>(listeners);
    final int count = snapshot.size();
    if (count == 0) {
      log.warn("notifySignal: НЕТ ПОДПИСЧИКОВ");
      return;
    }
    mainHandler.post(() -> {
      for (Listener l : snapshot) {
        try {
          l.onSignal(json);
        } catch (Exception e) {
          log.logError("notifySignal", e);
        }
      }
    });
  }

  private void notifyError(String message) {
    mainHandler.post(() -> {
      for (Listener l : listeners) {
        try {
          l.onError(message);
        } catch (Exception e) {
          log.logError("notifyError", e);
        }
      }
    });
  }

  private void notifyConnectedInternal(Peer remote, boolean isGroupOwner) {
    for (Listener l : listeners) {
      try {
        l.onConnected(remote, isGroupOwner);
      } catch (Exception e) {
        log.logError("notifyConnected", e);
      }
    }
  }

  /**
   * ★ ИЗМЕНЕНО: heartbeat идёт через общую очередь sendQueue, а не прямой записью в socketOut. Единственный писатель в сокет — sendThread (под sendLock). Это устраняет интерливинг кадров.
   *
   * ★ ИСПРАВЛЕНО описание (было перепутано): в старом коде heartbeat уже писал под synchronized (sendLock), а sendThread — без него. Интерливинг вызывал именно sendThread.
   */
  private void startHeartbeat() {
    if (heartbeatExecutor != null) {
      log.log("startHeartbeat: уже запущен");
      return;
    }
    log.log("startHeartbeat: запуск, интервал=" + HEARTBEAT_INTERVAL_MS + "мс");
    heartbeatExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "WifiDirectHeartbeat");
      t.setDaemon(true);
      return t;
    });
    heartbeatExecutor.scheduleWithFixedDelay(() -> {
      if (!connected || socketOut == null)
        return;
      try {
        JsonObject hb = new JsonObject();
        hb.addProperty("type", "hello_ack");
        hb.addProperty("heartbeat", true);
        // ★ Через очередь: единственный писатель — sendThread
        sendRawJson(hb.toString());
      } catch (Exception e) {
        log.warn("heartbeat: " + e.getMessage());
      }
    }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
  }

  private void stopHeartbeat() {
    if (heartbeatExecutor != null) {
      log.log("stopHeartbeat: остановка");
      heartbeatExecutor.shutdownNow();
      heartbeatExecutor = null;
    }
  }
}