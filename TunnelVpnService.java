package com.my.mealse.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.my.mealse.MainActivity;
import com.my.mealse.R;
import com.my.mealse.util.MealseLogger;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * VPN-сервис Mealse. Поддерживает IPv4 и IPv6 (dual-stack).
 *
 * Два режима работы:
 *  1) CLIENT — подключается к удалённому шлюзу через SOCKS5 (teapod-tun2socks).
 *     Перед запуском сервис делает TCP-probe по доступным адресам (IPv6, IPv4)
 *     и выбирает первый рабочий — это решает проблему, когда один протокол
 *     недоступен у оператора (например, IPv6 ENETUNREACH).
 *  2) SERVER — слушает dual-stack (IPv4 + IPv6), запускает Socks5Server
 *     для обслуживания клиентов, а также legacy TCP-туннель
 *     (10.0.0.0/24 + fd00::/64).
 *
 * ★ РЕВИЗИЯ (умный выбор шлюза):
 *  - В startClientMode() перед запуском teapod вызывается
 *    findWorkingGatewayAddress(), который последовательно пробует
 *    IPv6 и IPv4 и возвращает первый ответивший.
 *  - probeTcp() возвращает boolean и логирует причину неудачи.
 *  - findWorkingGatewayAddress и probeTcp вызываются в фоновом потоке,
 *    чтобы избежать NetworkOnMainThreadException.
 *
 * ★ РЕВИЗИЯ (shutdown):
 *  - Убран мёртвый код (hevTunnelHelper, uploadThread, downloadThread).
 *  - Корректная остановка teapodTunnelHelper в client-режиме.
 *  - Корректная остановка Socks5Server в server-режиме.
 */
public class TunnelVpnService extends VpnService {

  private static final String TAG = "TunnelVpnService";

  // ===== Actions =====
  public static final String ACTION_START = "com.my.mealse.vpn.START";
  public static final String ACTION_STOP = "com.my.mealse.vpn.STOP";
  public static final String ACTION_QUERY_STATUS = "com.my.mealse.vpn.QUERY_STATUS";

  // ===== Extras =====
  public static final String EXTRA_MODE = "mode";
  public static final String EXTRA_SERVER_IP = "server_ip";
  public static final String EXTRA_SERVER_PORT = "server_port";
  public static final String EXTRA_LISTEN_PORT = "listen_port";
  public static final String EXTRA_SERVER_IP_V6 = "server_ip_v6";
  public static final String EXTRA_TRANSPORT_IPV4 = "transport_ipv4";
  public static final String EXTRA_TRANSPORT_IPV6 = "transport_ipv6";
  public static final String EXTRA_GATEWAY_PRIORITY = "gateway_priority";
  public static final String EXTRA_LISTEN_IPV6 = "listen_ipv6";
  public static final String EXTRA_TUN_IPV6 = "tun_ipv6";

  // ===== Значения режима =====
  public static final String MODE_CLIENT = "client";
  public static final String MODE_SERVER = "server";

  // ===== Broadcast для UI =====
  public static final String ACTION_STATUS = "com.my.mealse.vpn.STATUS";
  public static final String EXTRA_RUNNING = "running";
  public static final String EXTRA_MESSAGE = "message";

  // ===== Notification =====
  private static final String CHANNEL_ID = "MealseVpnChannel";
  private static final int NOTIF_ID = 103;

  // ===== Сеть (общие) =====
  private static final int MTU = 1500;
  private static final int SERVER_ACCEPT_TIMEOUT_MS = 2000;
  private static final int HANDSHAKE_TIMEOUT_MS = 5000;
  private static final int MIN_VALID_IPV4_LEN = 20;

  /** ★ Таймаут для TCP-probe при выборе шлюза. */
  private static final int GATEWAY_PROBE_TIMEOUT_MS = 2500;

  // ===== Адреса клиента (IPv4 + IPv6) =====
  private static final String VPN_CLIENT_ADDRESS_V4 = "10.0.0.2";
  private static final int VPN_PREFIX_V4 = 24;
  private static final String VPN_CLIENT_ADDRESS_V6 = "fd00::2";
  private static final int VPN_PREFIX_V6 = 64;

  // ===== Адреса сервера (IPv4 + IPv6) =====
  private static final String VPN_SERVER_ADDRESS_V4 = "10.0.0.1";
  private static final String VPN_SUBNET_V4 = "10.0.0.0";
  private static final int VPN_SERVER_PREFIX_V4 = 24;
  private static final int VPN_CLIENT_START_IP = 2;
  private static final int VPN_CLIENT_MAX_IP = 254;
  private static final String VPN_SERVER_ADDRESS_V6_DEFAULT = "fd00::1";

  // ===== Порты =====
  public static final int DEFAULT_LISTEN_PORT = 8083;
  private static final int SOCKS5_PORT = 1080;

  // ===== DNS (только для клиента) =====
  private static final String DNS_PRIMARY = "1.1.1.1";
  private static final String DNS_SECONDARY = "8.8.8.8";

  // ============================================================
  // Состояние
  // ============================================================

  private MealseLogger log;

  private volatile boolean running = false;
  private String currentMode = null;

  private ParcelFileDescriptor vpnInterface;

  // --- Сервер (legacy TCP-туннель) ---
  private ServerSocket serverSocket;
  private final Map<String, ClientConnection> clients = new ConcurrentHashMap<>();
  private final Set<String> usedIps = ConcurrentHashMap.newKeySet();
  private ExecutorService clientPool;
  private Thread acceptThread;
  private Thread serverVpnPumpThread;
  private Socks5Server socks5Server;

  /** Единый writer в TUN для серверного режима (legacy). */
  private FileOutputStream vpnTunOut;

  /** tun2socks-обёртка (только для клиента). */
  private TeapodTunnelHelper teapodTunnelHelper;

  // ============================================================
  // Вложенный класс: соединение с одним клиентом (legacy TCP)
  // ============================================================

  private static class ClientConnection {
    final Socket socket;
    final String vpnIp;
    final int vpnIpInt;
    final DataInputStream in;
    final DataOutputStream out;
    volatile boolean alive = true;

    ClientConnection(Socket socket, String vpnIp, int vpnIpInt,
                     DataInputStream in, DataOutputStream out) {
      this.socket = socket;
      this.vpnIp = vpnIp;
      this.vpnIpInt = vpnIpInt;
      this.in = in;
      this.out = out;
    }

    void close() {
      alive = false;
      try {
        socket.close();
      } catch (IOException ignored) {
      }
    }
  }

  // ============================================================
  // Lifecycle
  // ============================================================

  @Override
  public void onCreate() {
    super.onCreate();

    log = MealseLogger.get(this, TAG);
    log.log("══ onCreate START ══");

    try {
      createNotificationChannel();
      clientPool = Executors.newCachedThreadPool();
      log.log("NotificationChannel создан, clientPool создан");
      log.log("══ onCreate END ══");
    } catch (Exception e) {
      logError("Ошибка onCreate", e);
    }
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    log.log("══ onStartCommand START ══");

    String action = intent != null ? intent.getAction() : null;
    log.log("Action: " + action);

    try {
      startForeground(NOTIF_ID, buildNotification("VPN запускается..."));
      log.log("startForeground OK");
    } catch (Exception e) {
      logError("Ошибка startForeground", e);
    }

    // ===== STOP =====
    if (ACTION_STOP.equals(action)) {
      log.log("Получена команда STOP");
      shutdown("команда STOP");
      return START_NOT_STICKY;
    }

    // ===== QUERY_STATUS =====
    if (ACTION_QUERY_STATUS.equals(action)) {
      log.log("Получен запрос статуса: running=" + running + ", mode=" + currentMode);
      sendStatusBroadcast(running,
          running ? ("VPN активен" + (currentMode != null ? " (" + currentMode + ")" : ""))
              : "VPN не запущен");
      if (!running) {
        try {
          stopForeground(true);
          stopSelf();
        } catch (Exception ignored) {
        }
      }
      return START_NOT_STICKY;
    }

    // ===== START или null action =====
    if (ACTION_START.equals(action) || action == null) {
      String mode = intent != null ? intent.getStringExtra(EXTRA_MODE) : null;
      if (mode == null || mode.isEmpty()) {
        mode = MODE_CLIENT;
      }
      log.log("Mode: " + mode);

      if (running && mode.equals(currentMode)) {
        log.warn("VPN уже запущен в режиме " + mode + " — игнорирую перезапуск");
        sendStatusBroadcast(true, "VPN уже активен в режиме " + mode);
        return START_STICKY;
      }

      if (running) {
        log.warn("VPN уже запущен в режиме " + currentMode
            + " — останавливаем перед перезапуском в " + mode);
        shutdown("смена режима");
        try {
          Thread.sleep(300);
        } catch (InterruptedException ignored) {
        }
      }

      currentMode = mode;

      if (MODE_SERVER.equals(mode)) {
        int listenPort = intent != null
            ? intent.getIntExtra(EXTRA_LISTEN_PORT, DEFAULT_LISTEN_PORT)
            : DEFAULT_LISTEN_PORT;

        String tunIpv6 = intent != null ? intent.getStringExtra(EXTRA_TUN_IPV6) : null;
        if (tunIpv6 == null || tunIpv6.isEmpty()) {
          tunIpv6 = VPN_SERVER_ADDRESS_V6_DEFAULT;
        }
        if (tunIpv6.contains("/")) {
          tunIpv6 = tunIpv6.substring(0, tunIpv6.indexOf('/'));
        }

        boolean listenIpv6 = intent == null || intent.getBooleanExtra(EXTRA_LISTEN_IPV6, true);

        return startServerMode(listenPort, tunIpv6, listenIpv6);
      } else {
        // ===== CLIENT =====
        String ipV4 = intent != null ? intent.getStringExtra(EXTRA_SERVER_IP) : null;
        String ipV6 = intent != null ? intent.getStringExtra(EXTRA_SERVER_IP_V6) : null;
        int port = intent != null
            ? intent.getIntExtra(EXTRA_SERVER_PORT, DEFAULT_LISTEN_PORT)
            : DEFAULT_LISTEN_PORT;

        boolean transportV4 = intent == null || intent.getBooleanExtra(EXTRA_TRANSPORT_IPV4, true);
        boolean transportV6 = intent == null || intent.getBooleanExtra(EXTRA_TRANSPORT_IPV6, true);

        String priority = intent != null ? intent.getStringExtra(EXTRA_GATEWAY_PRIORITY) : null;
        if (priority == null || priority.isEmpty()) {
          priority = "ipv6";
        }

        return startClientMode(ipV4, ipV6, port, transportV4, transportV6, priority);
      }
    }

    log.log("══ onStartCommand END (без действий) ══");
    return START_STICKY;
  }

  @Override
  public void onRevoke() {
    log.warn("VPN отозван системой (onRevoke)");
    log.warn("  running=" + running + ", mode=" + currentMode + ", vpnInterface="
        + (vpnInterface != null));
    sendStatusBroadcast(false, "VPN отозван системой");
    shutdown("onRevoke");
    super.onRevoke();
  }

  @Override
  public void onDestroy() {
    log.log("onDestroy START");
    shutdown("onDestroy");
    log.log("onDestroy END");
    super.onDestroy();
  }

  // ============================================================
  // ★ CLIENT режим (умный выбор шлюза)
  // ============================================================

  private int startClientMode(String ipV4, String ipV6, int port, boolean transportV4,
                              boolean transportV6, String priority) {
    log.log("══ startClientMode START: v4=" + ipV4 + ", v6=" + ipV6 + ", port=" + port
        + ", transport v4=" + transportV4 + " v6=" + transportV6 + ", priority=" + priority
        + " ══");

    // Проверяем, что есть с чем работать
    boolean hasV4 = transportV4 && ipV4 != null && !ipV4.isEmpty();
    boolean hasV6 = transportV6 && ipV6 != null && !ipV6.isEmpty();

    if (!hasV4 && !hasV6) {
      log.error("Не указан ни IPv4, ни IPv6 шлюза — завершаем");
      sendStatusBroadcast(false, "Не указан адрес шлюза (ни IPv4, ни IPv6)");
      stopSelf();
      return START_NOT_STICKY;
    }

    // Запускаем поиск шлюза в фоновом потоке
    final String fIpV4 = ipV4;
    final String fIpV6 = ipV6;
    final int fPort = port;
    final String fPriority = priority;

    new Thread(() -> {
      log.log("Шаг 1: поиск доступного адреса шлюза (TCP-probe) в фоне...");
      String workingGateway = findWorkingGatewayAddress(fIpV4, fIpV6, fPort, fPriority);

      if (workingGateway == null) {
        log.error("Ни один из адресов шлюза не доступен: v4=" + fIpV4 + ", v6=" + fIpV6);
        sendStatusBroadcast(false, "Шлюз недоступен (проверьте IPv4/IPv6 и порт " + fPort + ")");
        stopSelf();
        return;
      }
      log.log("Шаг 1: выбран рабочий шлюз: " + workingGateway);

      // Шаг 2: создаём VPN-интерфейс
      log.log("Шаг 2: establishVpnClient (dual-stack)");
      if (!establishVpnClient()) {
        log.error("establishVpnClient вернул false — завершаем");
        sendStatusBroadcast(false, "Не удалось установить VPN-интерфейс");
        stopSelf();
        return;
      }
      log.log("establishVpnClient OK");

      // Шаг 3: запускаем teapod-tun2socks
      log.log("Шаг 3: запуск teapod-tun2socks: socks5=" + workingGateway + ":" + SOCKS5_PORT);

      teapodTunnelHelper = new TeapodTunnelHelper(getApplicationContext());
      boolean started = teapodTunnelHelper.start(workingGateway, SOCKS5_PORT, vpnInterface);

      if (!started) {
        log.error("teapodTunnelHelper.start вернул false");
        sendStatusBroadcast(false, "Не удалось запустить tun2socks");
        shutdown("teapod start failed");
        stopSelf();
        return;
      }

      running = true;
      log.log("teapod-tun2socks запущен, tunFd=" + started);
      sendStatusBroadcast(true, "VPN активен — выход в интернет через " + workingGateway);

      log.log("══ startClientMode END ══");

    }, "VpnClientSetupThread").start();

    return START_STICKY;
  }

  /**
   * ★ Ищет рабочий адрес шлюза, последовательно пробуя все доступные.
   *
   * Порядок попыток:
   *  1. priority="ipv6" и есть IPv6 → сначала IPv6, потом IPv4.
   *  2. priority="ipv4" и есть IPv4 → сначала IPv4, потом IPv6.
   *  3. priority="auto" → сначала IPv6, потом IPv4.
   *
   * @return адрес (IPv4 или IPv6), который ответил первым, или null.
   */
  private String findWorkingGatewayAddress(String ipV4, String ipV6, int port, String priority) {
    boolean hasV4 = ipV4 != null && !ipV4.isEmpty();
    boolean hasV6 = ipV6 != null && !ipV6.isEmpty();

    java.util.List<String> attempts = new java.util.ArrayList<>();
    boolean v6First = "ipv6".equals(priority) || "auto".equals(priority);
    if (v6First) {
      if (hasV6) attempts.add(ipV6);
      if (hasV4) attempts.add(ipV4);
    } else {
      if (hasV4) attempts.add(ipV4);
      if (hasV6) attempts.add(ipV6);
    }

    log.log("findWorkingGatewayAddress: очередь попыток=" + attempts + ", port=" + port);

    for (String addr : attempts) {
      log.log("findWorkingGatewayAddress: probe " + addr + ":" + port + "...");
      if (probeTcp(addr, port, GATEWAY_PROBE_TIMEOUT_MS)) {
        log.log("findWorkingGatewayAddress: ✓ " + addr + " доступен");
        return addr;
      } else {
        log.warn("findWorkingGatewayAddress: ✗ " + addr + " не доступен");
      }
    }

    log.error("findWorkingGatewayAddress: ни один адрес не доступен");
    return null;
  }

  /**
   * ★ Быстрая TCP-проверка доступности хоста и порта.
   */
  private boolean probeTcp(String host, int port, int timeoutMs) {
    Socket socket = null;
    try {
      socket = new Socket();
      socket.connect(new InetSocketAddress(host, port), timeoutMs);
      return true;
    } catch (Exception e) {
      log.log("probeTcp(" + host + ":" + port + ") — " + e.getClass().getSimpleName() + ": "
          + e.getMessage());
      return false;
    } finally {
      if (socket != null) {
        try {
          socket.close();
        } catch (IOException ignored) {
        }
      }
    }
  }

  private boolean establishVpnClient() {
    log.log("establishVpnClient START (dual-stack + full-tunnel)");
    try {
      Builder builder = new Builder();
      builder.setSession("Mealse VPN (client)");
      builder.setMtu(MTU);

      // ============================================================
      // TUN-адрес клиента
      // ============================================================
      builder.addAddress(VPN_CLIENT_ADDRESS_V4, VPN_PREFIX_V4);
      log.log("IPv4 address: " + VPN_CLIENT_ADDRESS_V4 + "/" + VPN_PREFIX_V4);

      // Весь IPv4-трафик в TUN
      builder.addRoute("0.0.0.0", 0);
      log.log("IPv4 route: 0.0.0.0/0 (весь трафик)");

      // IPv6: адрес + маршрут ::/0
      try {
        builder.addAddress(VPN_CLIENT_ADDRESS_V6, VPN_PREFIX_V6);
        builder.addRoute("::", 0);
        log.log("IPv6 address: " + VPN_CLIENT_ADDRESS_V6 + "/" + VPN_PREFIX_V6);
        log.log("IPv6 route: ::/0 (весь трафик)");
      } catch (Exception e) {
        log.warn("IPv6 не поддержан на этом TUN: " + e.getMessage());
      }

      // DNS
      builder.addDnsServer(DNS_PRIMARY);
      builder.addDnsServer(DNS_SECONDARY);
      log.log("DNS: " + DNS_PRIMARY + ", " + DNS_SECONDARY);

      // Исключаем своё приложение из VPN
      try {
        builder.addDisallowedApplication(getPackageName());
        log.log("Исключено приложение: " + getPackageName());
      } catch (Exception e) {
        log.warn("Не удалось исключить приложение: " + e.getMessage());
      }

      // setBlocking(true) — блокировать трафик, если VPN не работает
      try {
        builder.setBlocking(true);
        log.log("setBlocking(true) — трафик будет блокироваться, если VPN не работает");
      } catch (Exception e) {
        log.warn("setBlocking не поддержан: " + e.getMessage());
      }

      addConfigureIntent(builder);

      log.log("Вызов builder.establish()...");
      vpnInterface = builder.establish();

      if (vpnInterface == null) {
        log.error("establish() вернул null — пользователь не дал согласие?");
        return false;
      }

      log.log("VPN-интерфейс (client, full-tunnel) установлен: fd=" + vpnInterface.getFd());
      return true;

    } catch (Exception e) {
      logError("Ошибка establishVpnClient", e);
      return false;
    }
  }

  // ============================================================
  // ★ SERVER режим (dual-stack)
  // ============================================================

  private int startServerMode(int listenPort, String tunIpv6, boolean listenIpv6) {
    log.log("══ startServerMode START: listenPort=" + listenPort + ", tunIpv6=" + tunIpv6
        + ", listenIpv6=" + listenIpv6 + " ══");

    log.log("Шаг 1: establishVpnServer (dual-stack)");
    if (!establishVpnServer(tunIpv6)) {
      log.error("establishVpnServer вернул false — завершаем");
      sendStatusBroadcast(false, "Не удалось установить VPN-интерфейс (server)");
      stopSelf();
      return START_NOT_STICKY;
    }
    log.log("establishVpnServer OK");

    try {
      vpnTunOut = new FileOutputStream(vpnInterface.getFileDescriptor());
      log.log("vpnTunOut открыт (единый writer в TUN)");
    } catch (Exception e) {
      logError("Не удалось открыть единый writer в TUN", e);
      shutdown("vpnTunOut open failed");
      stopSelf();
      return START_NOT_STICKY;
    }

    log.log("Шаг 2: startAcceptLoop");
    startAcceptLoop(listenPort, listenIpv6);

    log.log("Шаг 3: startServerVpnPump");
    startServerVpnPump();

    // SOCKS5-сервер для выхода в интернет
    try {
      socks5Server = new Socks5Server(getApplicationContext(), SOCKS5_PORT);
      socks5Server.start();
      log.log("SOCKS5-сервер запущен на порту " + SOCKS5_PORT);
    } catch (Exception e) {
      logError("Не удалось запустить SOCKS5-сервер", e);
    }

    log.log("══ startServerMode END ══");
    return START_STICKY;
  }

  private boolean establishVpnServer(String tunIpv6) {
    log.log("establishVpnServer START (dual-stack)");
    try {
      Builder builder = new Builder();
      builder.setSession("Mealse VPN (server)");
      builder.setMtu(MTU);

      builder.addAddress(VPN_SERVER_ADDRESS_V4, VPN_SERVER_PREFIX_V4);
      builder.addRoute(VPN_SUBNET_V4, VPN_SERVER_PREFIX_V4);
      log.log("IPv4: " + VPN_SERVER_ADDRESS_V4 + "/" + VPN_SERVER_PREFIX_V4 + ", route "
          + VPN_SUBNET_V4 + "/" + VPN_SERVER_PREFIX_V4);

      try {
        builder.addAddress(tunIpv6, VPN_PREFIX_V6);
        builder.addRoute("::", 0);
        log.log("IPv6: " + tunIpv6 + "/" + VPN_PREFIX_V6 + ", route ::/0");
      } catch (Exception e) {
        log.warn("IPv6 не поддержан на этом TUN: " + e.getMessage());
      }

      try {
        builder.addDisallowedApplication(getPackageName());
        log.log("Исключено приложение: " + getPackageName());
      } catch (Exception e) {
        log.warn("Не удалось исключить приложение: " + e.getMessage());
      }

      addConfigureIntent(builder);

      log.log("Вызов builder.establish()...");
      vpnInterface = builder.establish();

      if (vpnInterface == null) {
        log.error("establish() вернул null — пользователь не дал согласие?");
        return false;
      }

      log.log("VPN-интерфейс (server, dual-stack) установлен: fd=" + vpnInterface.getFd());
      return true;

    } catch (Exception e) {
      logError("Ошибка establishVpnServer", e);
      return false;
    }
  }

  /**
   * Слушаем dual-stack:
   *  - listenIpv6=true → bind на "::" (принимает и v4-mapped, и v6).
   *  - listenIpv6=false → bind на "0.0.0.0" (только IPv4).
   */
  private void startAcceptLoop(int listenPort, boolean listenIpv6) {
    log.log("startAcceptLoop START: port=" + listenPort + ", listenIpv6=" + listenIpv6);

    final boolean bindV6 = listenIpv6;

    acceptThread = new Thread(() -> {
      try {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.setSoTimeout(SERVER_ACCEPT_TIMEOUT_MS);

        if (bindV6) {
          serverSocket.bind(new InetSocketAddress("::", listenPort));
          log.log("✓ ServerSocket слушает [::]:" + listenPort + " (dual-stack: IPv4 + IPv6)");
        } else {
          serverSocket.bind(new InetSocketAddress("0.0.0.0", listenPort));
          log.log("✓ ServerSocket слушает 0.0.0.0:" + listenPort + " (только IPv4)");
        }

        sendStatusBroadcast(true,
            "Шлюз активен — слушаю порт " + listenPort + (bindV6 ? " (v4+v6)" : " (v4)"));
        running = true;

        while (running) {
          try {
            Socket client = serverSocket.accept();
            String clientAddr = client.getInetAddress().getHostAddress() + ":" + client.getPort();
            log.log("📥 accept: клиент " + clientAddr);
            clientPool.submit(() -> handleClient(client));
          } catch (SocketTimeoutException e) {
            // периодический таймаут
          } catch (IOException e) {
            if (running) {
              logError("Ошибка accept", e);
            }
          }
        }
        log.log("Цикл accept завершён (running=false)");

      } catch (IOException e) {
        logError("Не удалось запустить ServerSocket на порту " + listenPort, e);
        sendStatusBroadcast(false, "Не удалось запустить сервер: " + e.getMessage());
        stopSelf();
      }
    }, "VpnAcceptThread");
    acceptThread.start();
  }

  /**
   * handleClient (legacy TCP-туннель сервера). Handshake + reader от клиента.
   */
  private void handleClient(Socket client) {
    String clientAddr = "unknown";
    ClientConnection conn = null;

    try {
      client.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
      client.setTcpNoDelay(true);
      client.setKeepAlive(true);
      clientAddr = client.getInetAddress().getHostAddress() + ":" + client.getPort();
      log.log("══ handleClient START: " + clientAddr + " ══");

      DataInputStream in = new DataInputStream(client.getInputStream());
      DataOutputStream out = new DataOutputStream(client.getOutputStream());

      int requestedIpInt = in.readInt();
      log.log("Handshake: requestedVpnIpV4=" + requestedIpInt);

      String assignedIp = allocateClientIp();
      if (assignedIp == null) {
        log.error("Нет свободных IP в подсети — отказываю клиенту");
        out.writeInt(0);
        out.flush();
        client.close();
        return;
      }

      int assignedIpInt = ipToInt(assignedIp);
      log.log("Handshake: assignedVpnIpV4=" + assignedIp + " (" + assignedIpInt + ")");

      out.writeInt(assignedIpInt);
      out.flush();

      client.setSoTimeout(0);

      conn = new ClientConnection(client, assignedIp, assignedIpInt, in, out);
      clients.put(assignedIp, conn);
      log.log("Клиент зарегистрирован: " + clientAddr + " → VPN-IP " + assignedIp
          + ", всего клиентов: " + clients.size());

      sendStatusBroadcast(true, "Клиентов подключено: " + clients.size());

      pumpClientToServer(conn);

    } catch (SocketTimeoutException e) {
      logError("handleClient: таймаут handshake с " + clientAddr, e);
    } catch (IOException e) {
      if (running)
        logError("handleClient: IOException с " + clientAddr, e);
    } catch (Exception e) {
      logError("handleClient: неожиданная ошибка с " + clientAddr, e);
    } finally {
      if (conn != null) {
        releaseClientIp(conn.vpnIp);
        clients.remove(conn.vpnIp);
        conn.close();
        log.log("Клиент отключён: " + clientAddr + " (VPN-IP " + conn.vpnIp
            + "), осталось клиентов: " + clients.size());
        if (running) {
          sendStatusBroadcast(true, "Клиентов подключено: " + clients.size());
        }
      }
      log.log("══ handleClient END: " + clientAddr + " ══");
    }
  }

  private void pumpClientToServer(ClientConnection conn) {
    log.log("pumpClientToServer START: " + conn.vpnIp);

    if (vpnTunOut == null) {
      log.error("pumpClientToServer: vpnTunOut == null");
      return;
    }

    long totalBytes = 0;
    int packetCount = 0;
    int v4Count = 0;
    int v6Count = 0;

    while (running && conn.alive) {
      int length;
      try {
        length = conn.in.readUnsignedShort();
      } catch (IOException e) {
        if (running && conn.alive)
          log.log("pumpClientToServer (" + conn.vpnIp + "): read length — " + e.getMessage());
        break;
      }

      if (length < MIN_VALID_IPV4_LEN || length > MTU) {
        log.warn("pumpClientToServer (" + conn.vpnIp + "): некорректная length=" + length
            + " — обрыв протокола");
        break;
      }

      byte[] packet = new byte[length];
      try {
        conn.in.readFully(packet);
      } catch (IOException e) {
        if (running && conn.alive)
          logError("pumpClientToServer (" + conn.vpnIp + "): read packet", e);
        break;
      }

      if (length >= 1) {
        int version = (packet[0] >> 4) & 0x0F;
        if (version == 4) v4Count++;
        else if (version == 6) v6Count++;
      }

      try {
        synchronized (vpnTunOut) {
          vpnTunOut.write(packet, 0, length);
          vpnTunOut.flush();
        }
        totalBytes += length;
        packetCount++;
        if (packetCount % 100 == 0) {
          log.log("pumpClientToServer (" + conn.vpnIp + "): " + packetCount + " пакетов, "
              + totalBytes + " байт (v4=" + v4Count + ", v6=" + v6Count + ")");
        }
      } catch (IOException e) {
        if (running && conn.alive)
          logError("pumpClientToServer (" + conn.vpnIp + "): write to TUN", e);
        break;
      }
    }

    log.log("pumpClientToServer END (" + conn.vpnIp + "): пакетов=" + packetCount + ", байт="
        + totalBytes + " (v4=" + v4Count + ", v6=" + v6Count + ")");
    conn.alive = false;
  }

  /**
   * Один поток на весь сервер: читает из TUN, по dst-IP находит клиента, пишет ему в сокет.
   * Поддерживает IPv4 (dst offset 16). IPv6-пакеты дропаются (legacy).
   */
  private void startServerVpnPump() {
    log.log("startServerVpnPump START");

    serverVpnPumpThread = new Thread(() -> {
      if (vpnInterface == null) {
        log.error("startServerVpnPump: vpnInterface == null");
        return;
      }

      byte[] buffer = new byte[MTU];
      long totalBytes = 0;
      int packetCount = 0;
      int droppedCount = 0;
      int v4Count = 0;
      int v6Count = 0;

      try (FileInputStream vpnIn = new FileInputStream(vpnInterface.getFileDescriptor())) {
        log.log("startServerVpnPump: поток открыт");

        while (running) {
          int read;
          try {
            read = vpnIn.read(buffer);
          } catch (IOException e) {
            if (running)
              logError("startServerVpnPump: ошибка чтения из TUN", e);
            break;
          }
          if (read <= 0) {
            if (read < 0) {
              log.log("startServerVpnPump: EOF из TUN");
              break;
            }
            continue;
          }

          if (read < MIN_VALID_IPV4_LEN) {
            log.warn("startServerVpnPump: слишком короткий пакет (" + read + " байт)");
            continue;
          }

          int version = (buffer[0] >> 4) & 0x0F;

          String dstIp;
          if (version == 4) {
            v4Count++;
            int dstInt = ((buffer[16] & 0xFF) << 24) | ((buffer[17] & 0xFF) << 16)
                | ((buffer[18] & 0xFF) << 8) | (buffer[19] & 0xFF);
            dstIp = intToIp(dstInt);
          } else if (version == 6) {
            v6Count++;
            if (droppedCount++ % 100 == 0) {
              log.log("startServerVpnPump: пропущен IPv6-пакет (dropped=" + droppedCount
                  + ") — IPv6 пока не форвардится");
            }
            continue;
          } else {
            log.log("startServerVpnPump: не-IP пакет (version=" + version + "), пропускаю");
            continue;
          }

          ClientConnection target = clients.get(dstIp);

          if (target == null || !target.alive) {
            droppedCount++;
            if (droppedCount % 100 == 0) {
              log.log("startServerVpnPump: пакет для " + dstIp + " не найден (dropped="
                  + droppedCount + ")");
            }
            continue;
          }

          try {
            synchronized (target.out) {
              target.out.writeShort(read);
              target.out.write(buffer, 0, read);
              target.out.flush();
            }
            totalBytes += read;
            packetCount++;
            if (packetCount % 100 == 0) {
              log.log("startServerVpnPump: " + packetCount + " пакетов, " + totalBytes
                  + " байт (v4=" + v4Count + ", v6=" + v6Count + ")");
            }
          } catch (IOException e) {
            logError("startServerVpnPump: ошибка записи клиенту " + dstIp, e);
            target.close();
          }
        }
      } catch (IOException e) {
        if (running)
          logError("startServerVpnPump завершён с ошибкой", e);
      } finally {
        log.log("startServerVpnPump END: пакетов=" + packetCount + ", байт=" + totalBytes
            + ", dropped=" + droppedCount + " (v4=" + v4Count + ", v6=" + v6Count + ")");
      }
    }, "VpnServerVpnPump");
    serverVpnPumpThread.start();
  }

  // ============================================================
  // Аллокация IP
  // ============================================================

  private synchronized String allocateClientIp() {
    for (int i = VPN_CLIENT_START_IP; i <= VPN_CLIENT_MAX_IP; i++) {
      String ip = "10.0.0." + i;
      if (usedIps.add(ip)) {
        log.log("allocateClientIp: выделен " + ip);
        return ip;
      }
    }
    log.warn("allocateClientIp: нет свободных IP в диапазоне");
    return null;
  }

  private synchronized void releaseClientIp(String ip) {
    if (ip != null && usedIps.remove(ip)) {
      log.log("releaseClientIp: освобождён " + ip);
    }
  }

  // ============================================================
  // Утилиты IP <-> int
  // ============================================================

  private static int ipToInt(String ip) {
    try {
      String[] parts = ip.split("\\.");
      if (parts.length != 4) return 0;
      int a = Integer.parseInt(parts[0]);
      int b = Integer.parseInt(parts[1]);
      int c = Integer.parseInt(parts[2]);
      int d = Integer.parseInt(parts[3]);
      return ((a & 0xFF) << 24) | ((b & 0xFF) << 16) | ((c & 0xFF) << 8) | (d & 0xFF);
    } catch (Exception e) {
      return 0;
    }
  }

  private static String intToIp(int ipInt) {
    return ((ipInt >> 24) & 0xFF) + "." + ((ipInt >> 16) & 0xFF) + "."
        + ((ipInt >> 8) & 0xFF) + "." + (ipInt & 0xFF);
  }

  // ============================================================
  // Общие хелперы
  // ============================================================

  private void addConfigureIntent(Builder builder) {
    Intent notifyIntent = new Intent(this, MainActivity.class);
    notifyIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      piFlags |= PendingIntent.FLAG_IMMUTABLE;
    }
    PendingIntent pi = PendingIntent.getActivity(this, 0, notifyIntent, piFlags);
    builder.setConfigureIntent(pi);
    log.log("ConfigureIntent установлен");
  }

  // ============================================================
  // Shutdown
  // ============================================================

  private synchronized void shutdown(String reason) {
    if (!running && vpnInterface == null && serverSocket == null && socks5Server == null) {
      log.log("shutdown(" + reason + "): уже остановлено");
      return;
    }

    log.log("shutdown START: reason=" + reason);
    running = false;

    // Останавливаем teapod-tun2socks (только в client-режиме)
    if (teapodTunnelHelper != null) {
      try {
        teapodTunnelHelper.stop();
        log.log("teapod-tun2socks остановлен");
      } catch (Exception e) {
        log.warn("Ошибка остановки teapod: " + e.getMessage());
      }
      teapodTunnelHelper = null;
    }

    // Останавливаем SOCKS5 (только в server-режиме)
    if (socks5Server != null) {
      try {
        socks5Server.stop();
        log.log("SOCKS5-сервер остановлен");
      } catch (Exception e) {
        log.warn("Ошибка остановки SOCKS5: " + e.getMessage());
      }
      socks5Server = null;
    }

    if (acceptThread != null) {
      try {
        acceptThread.join(2000);
        log.log("acceptThread завершён");
      } catch (InterruptedException e) {
        log.warn("acceptThread join прерван: " + e.getMessage());
      }
      acceptThread = null;
    }

    if (serverSocket != null) {
      try {
        serverSocket.close();
        log.log("serverSocket закрыт");
      } catch (IOException e) {
        log.warn("Ошибка закрытия serverSocket: " + e.getMessage());
      }
      serverSocket = null;
    }

    for (ClientConnection conn : clients.values()) {
      try {
        conn.close();
      } catch (Exception e) {
        log.warn("Ошибка закрытия клиента " + conn.vpnIp + ": " + e.getMessage());
      }
    }
    clients.clear();
    usedIps.clear();

    if (vpnTunOut != null) {
      try {
        vpnTunOut.close();
        log.log("vpnTunOut закрыт");
      } catch (IOException e) {
        log.warn("Ошибка закрытия vpnTunOut: " + e.getMessage());
      }
      vpnTunOut = null;
    }

    if (vpnInterface != null) {
      try {
        vpnInterface.close();
        log.log("vpnInterface закрыт");
      } catch (IOException e) {
        log.warn("Ошибка закрытия vpnInterface: " + e.getMessage());
      }
      vpnInterface = null;
    }

    serverVpnPumpThread = null;

    if (clientPool != null) {
      try {
        clientPool.shutdownNow();
        log.log("clientPool остановлен");
      } catch (Exception e) {
        log.warn("Ошибка остановки clientPool: " + e.getMessage());
      }
    }

    try {
      stopForeground(true);
      log.log("stopForeground OK");
    } catch (Exception e) {
      log.warn("Ошибка stopForeground: " + e.getMessage());
    }

    try {
      stopSelf();
      log.log("stopSelf OK");
    } catch (Exception e) {
      log.warn("Ошибка stopSelf: " + e.getMessage());
    }

    sendStatusBroadcast(false, "VPN остановлен");
    log.log("shutdown END: reason=" + reason + ", mode=" + currentMode);
    currentMode = null;
  }

  // ============================================================
  // Broadcast
  // ============================================================

  private void sendStatusBroadcast(boolean runningState, String message) {
    try {
      Intent i = new Intent(ACTION_STATUS);
      i.putExtra(EXTRA_RUNNING, runningState);
      i.putExtra(EXTRA_MESSAGE, message);
      i.putExtra(EXTRA_MODE, currentMode);
      i.setPackage(getPackageName());
      sendBroadcast(i);

      if (log != null) {
        log.log("📡 Broadcast: running=" + runningState
            + ", msg=" + message + ", mode=" + currentMode);
      }
    } catch (Exception e) {
      logError("Ошибка sendStatusBroadcast", e);
    }
  }

  // ============================================================
  // Notification
  // ============================================================

  private void createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Mealse VPN",
          NotificationManager.IMPORTANCE_LOW);
      channel.setDescription("VPN-шлюз Mealse");
      NotificationManager nm = getSystemService(NotificationManager.class);
      if (nm != null)
        nm.createNotificationChannel(channel);
    }
  }

  private Notification buildNotification(String text) {
    return new Notification.Builder(this, CHANNEL_ID).setContentTitle("Mealse VPN")
        .setContentText(text).setSmallIcon(R.drawable.ic_vpn).setOngoing(true).build();
  }

  // ============================================================
  // Хелперы логирования
  // ============================================================

  private void logError(String message, Throwable e) {
    if (log != null) {
      log.logError(message, e);
    } else {
      Log.e(TAG, message, e);
    }
  }
}rface: " + e.getMessage());
      }
      vpnInterface = null;
    }

    serverVpnPumpThread = null;

    if (clientPool != null) {
      try {
        clientPool.shutdownNow();
        log.log("clientPool остановлен");
      } catch (Exception e) {
        log.warn("Ошибка остановки clientPool: " + e.getMessage());
      }
    }

    try {
      stopForeground(true);
      log.log("stopForeground OK");
    } catch (Exception e) {
      log.warn("Ошибка stopForeground: " + e.getMessage());
    }

    try {
      stopSelf();
      log.log("stopSelf OK");
    } catch (Exception e) {
      log.warn("Ошибка stopSelf: " + e.getMessage());
    }

    sendStatusBroadcast(false, "VPN остановлен");
    log.log("shutdown END: reason=" + reason + ", mode=" + currentMode);
    currentMode = null;
  }

  // ============================================================
  // Broadcast
  // ============================================================

  private void sendStatusBroadcast(boolean runningState, String message) {
    try {
      Intent i = new Intent(ACTION_STATUS);
      i.putExtra(EXTRA_RUNNING, runningState);
      i.putExtra(EXTRA_MESSAGE, message);
      i.putExtra(EXTRA_MODE, currentMode);
      i.setPackage(getPackageName());
      sendBroadcast(i);

      if (log != null) {
        log.log(
            "📡 Broadcast: running=" + runningState + ", msg=" + message + ", mode=" + currentMode);
      }
    } catch (Exception e) {
      logError("Ошибка sendStatusBroadcast", e);
    }
  }

  // ============================================================
  // Notification
  // ============================================================

  private void createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Mealse VPN",
          NotificationManager.IMPORTANCE_LOW);
      channel.setDescription("VPN-шлюз Mealse");
      NotificationManager nm = getSystemService(NotificationManager.class);
      if (nm != null)
        nm.createNotificationChannel(channel);
    }
  }

  private Notification buildNotification(String text) {
    return new Notification.Builder(this, CHANNEL_ID).setContentTitle("Mealse VPN")
        .setContentText(text).setSmallIcon(R.drawable.ic_vpn).setOngoing(true).build();
  }

  // ============================================================
  // Хелперы логирования
  // ============================================================

  private void logError(String message, Throwable e) {
    if (log != null) {
      log.logError(message, e);
    } else {
      Log.e(TAG, message, e);
    }
  }
}    }
  }
}ump завершён с ошибкой", e);
      } finally {
        log.log("startServerVpnPump END: пакетов=" + packetCount + ", байт=" + totalBytes
            + ", dropped=" + droppedCount + " (v4=" + v4Count + ", v6=" + v6Count + ")");
      }
    }, "VpnServerVpnPump");
    serverVpnPumpThread.start();
  }

  // ============================================================
  // Аллокация IP
  // ============================================================

  private synchronized String allocateClientIp() {
    for (int i = VPN_CLIENT_START_IP; i <= VPN_CLIENT_MAX_IP; i++) {
      String ip = "10.0.0." + i;
      if (usedIps.add(ip)) {
        log.log("allocateClientIp: выделен " + ip);
        return ip;
      }
    }
    log.warn("allocateClientIp: нет свободных IP в диапазоне");
    return null;
  }

  private synchronized void releaseClientIp(String ip) {
    if (ip != null && usedIps.remove(ip)) {
      log.log("releaseClientIp: освобождён " + ip);
    }
  }

  // ============================================================
  // Утилиты IP <-> int
  // ============================================================

  private static int ipToInt(String ip) {
    try {
      String[] parts = ip.split("\\.");
      if (parts.length != 4)
        return 0;
      int a = Integer.parseInt(parts[0]);
      int b = Integer.parseInt(parts[1]);
      int c = Integer.parseInt(parts[2]);
      int d = Integer.parseInt(parts[3]);
      return ((a & 0xFF) << 24) | ((b & 0xFF) << 16) | ((c & 0xFF) << 8) | (d & 0xFF);
    } catch (Exception e) {
      return 0;
    }
  }

  private static String intToIp(int ipInt) {
    return ((ipInt >> 24) & 0xFF) + "." + ((ipInt >> 16) & 0xFF) + "." + ((ipInt >> 8) & 0xFF) + "."
        + (ipInt & 0xFF);
  }

  // ============================================================
  // Общие хелперы
  // ============================================================

  private void addConfigureIntent(Builder builder) {
    Intent notifyIntent = new Intent(this, MainActivity.class);
    notifyIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      piFlags |= PendingIntent.FLAG_IMMUTABLE;
    }
    PendingIntent pi = PendingIntent.getActivity(this, 0, notifyIntent, piFlags);
    builder.setConfigureIntent(pi);
    log.log("ConfigureIntent установлен");
  }

  // ============================================================
  // Shutdown
  // ============================================================

  private synchronized void shutdown(String reason) {
    if (!running && vpnInterface == null && tunnelSocket == null && serverSocket == null
        && socks5Server == null) {
      log.log("shutdown(" + reason + "): уже остановлено");
      return;
    }

    log.log("shutdown START: reason=" + reason);
    running = false;

    // ★ Останавливаем hev-socks5-tunnel (tun2socks)
    if (hevTunnelHelper != null) {
      try {
        hevTunnelHelper.stop();
        log.log("hev-socks5-tunnel остановлен");
      } catch (Exception e) {
        log.warn("Ошибка остановки hev: " + e.getMessage());
      }
      hevTunnelHelper = null;
    }

    // ★ НОВОЕ: останавливаем SOCKS5
    if (socks5Server != null) {
      try {
        socks5Server.stop();
        log.log("SOCKS5-сервер остановлен");
      } catch (Exception e) {
        log.warn("Ошибка остановки SOCKS5: " + e.getMessage());
      }
      socks5Server = null;
    }

    if (tunnelSocket != null) {
      try {
        tunnelSocket.close();
        log.log("tunnelSocket закрыт");
      } catch (IOException e) {
        log.warn("Ошибка закрытия tunnelSocket: " + e.getMessage());
      }
      tunnelSocket = null;
    }

    if (acceptThread != null) {
      try {
        acceptThread.join(2000);
        log.log("acceptThread завершён");
      } catch (InterruptedException e) {
        log.warn("acceptThread join прерван: " + e.getMessage());
      }
      acceptThread = null;
    }

    if (serverSocket != null) {
      try {
        serverSocket.close();
        log.log("serverSocket закрыт");
      } catch (IOException e) {
        log.warn("Ошибка закрытия serverSocket: " + e.getMessage());
      }
      serverSocket = null;
    }

    for (ClientConnection conn : clients.values()) {
      try {
        conn.close();
      } catch (Exception e) {
        log.warn("Ошибка закрытия клиента " + conn.vpnIp + ": " + e.getMessage());
      }
    }
    clients.clear();
    usedIps.clear();

    if (vpnTunOut != null) {
      try {
        vpnTunOut.close();
        log.log("vpnTunOut закрыт");
      } catch (IOException e) {
        log.warn("Ошибка закрытия vpnTunOut: " + e.getMessage());
      }
      vpnTunOut = null;
    }

    if (vpnInterface != null) {
      try {
        vpnInterface.close();
        log.log("vpnInterface закрыт");
      } catch (IOException e) {
        log.warn("Ошибка закрытия vpnInterface: " + e.getMessage());
      }
      vpnInterface = null;
    }

    uploadThread = null;
    downloadThread = null;
    serverVpnPumpThread = null;

    if (clientPool != null) {
      try {
        clientPool.shutdownNow();
        log.log("clientPool остановлен");
      } catch (Exception e) {
        log.warn("Ошибка остановки clientPool: " + e.getMessage());
      }
    }

    try {
      stopForeground(true);
      log.log("stopForeground OK");
    } catch (Exception e) {
      log.warn("Ошибка stopForeground: " + e.getMessage());
    }

    try {
      stopSelf();
      log.log("stopSelf OK");
    } catch (Exception e) {
      log.warn("Ошибка stopSelf: " + e.getMessage());
    }

    sendStatusBroadcast(false, "VPN остановлен");
    log.log("shutdown END: reason=" + reason + ", mode=" + currentMode);
    currentMode = null;
  }

  // ============================================================
  // Broadcast
  // ============================================================

  private void sendStatusBroadcast(boolean runningState, String message) {
    try {
      Intent i = new Intent(ACTION_STATUS);
      i.putExtra(EXTRA_RUNNING, runningState);
      i.putExtra(EXTRA_MESSAGE, message);
      i.putExtra(EXTRA_MODE, currentMode);
      i.setPackage(getPackageName());
      sendBroadcast(i);

      if (log != null) {
        log.log(
            "📡 Broadcast: running=" + runningState + ", msg=" + message + ", mode=" + currentMode);
      }
    } catch (Exception e) {
      logError("Ошибка sendStatusBroadcast", e);
    }
  }

  // ============================================================
  // Notification
  // ============================================================

  private void createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Mealse VPN",
          NotificationManager.IMPORTANCE_LOW);
      channel.setDescription("VPN-шлюз Mealse");
      NotificationManager nm = getSystemService(NotificationManager.class);
      if (nm != null)
        nm.createNotificationChannel(channel);
    }
  }

  private Notification buildNotification(String text) {
    return new Notification.Builder(this, CHANNEL_ID).setContentTitle("Mealse VPN")
        .setContentText(text).setSmallIcon(R.drawable.ic_vpn).setOngoing(true).build();
  }

  // ============================================================
  // Хелперы логирования
  // ============================================================

  private void logError(String message, Throwable e) {
    if (log != null) {
      log.logError(message, e);
    } else {
      Log.e(TAG, message, e);
    }
  }
}