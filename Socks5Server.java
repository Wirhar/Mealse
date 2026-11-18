package com.my.mealse.service;

import android.content.Context;

import com.my.mealse.util.MealseLogger;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ★ SOCKS5-сервер (RFC 1928) для выхода в интернет.
 *
 * Принимает SOCKS5-соединения от клиента (устройство B через
 * teapod-tun2socks), устанавливает соединения от своего имени
 * (через SIM-карту устройства A) и ретранслирует данные.
 *
 * Поддерживает:
 *  - метод аутентификации: NO_AUTH (0x00)
 *  - команды: CONNECT (0x01) и UDP ASSOCIATE (0x03)
 *  - типы адресов: IPv4, IPv6, DOMAINNAME
 *
 * ★ UDP ASSOCIATE:
 *  - При получении команды 0x03 сервер создаёт отдельный UDP-сокет,
 *    привязанный к [::]:0 (эфирный порт), и сообщает клиенту его адрес.
 *  - Клиент отправляет датаграммы в формате [RSV][FRAG][ATYP][DST.ADDR][DST.PORT][DATA].
 *  - Сервер распаковывает датаграмму, пересылает payload на целевой адрес
 *    через тот же UDP-сокет и получает ответ.
 *  - Ответ упаковывается обратно в SOCKS5-заголовок и отправляется клиенту.
 *
 * ★ Приоритет IPv4, но с fallback на IPv6:
 *  - Для ATYP_IPV4 → только IPv4.
 *  - Для ATYP_DOMAIN → сначала ищем IPv4 среди всех A/AAAA; если IPv4 нет — берём IPv6.
 *  - Для ATYP_IPV6 → сначала пробуем подключиться к IPv6 напрямую
 *    (у оператора может быть рабочий IPv6-маршрут);
 *    если ENETUNREACH — пробуем reverse DNS → IPv4 (как fallback).
 *
 * ★ Логирование relay:
 *  - В relay() логируются имена направлений, прогресс и итоговые байты.
 *  - В UdpSession добавлены счётчики пакетов/байт в обоих направлениях.
 *
 * ★ РЕВИЗИЯ (v4, исправление IPv6-connect + гонки tcpWatchLoop):
 *  1. tcpWatchLoop(): перед setSoTimeout() проверяем isClosed();
 *     отдельно ловим SocketException (гонка close/setSoTimeout) без стектрейса.
 *  2. handleConnect(): для ATYP_IPV6 сначала пробуем прямое подключение
 *     к IPv6; при ENETUNREACH/NoRouteToHost — fallback на reverse DNS → IPv4.
 *  3. resolveTargetAddress(): возвращает InetAddress (v4 или v6),
 *     а не только IPv4. Для доменов приоритет IPv4 (совместимость с прошлым
 *     поведением), но при отсутствии IPv4 берётся IPv6.
 *  4. UdpSession.tcpWatchLoop(): аналогично п.1 — без ложных стектрейсов.
 *  5. handleClient(): target передаётся в handleConnect и закрывается
 *     в finally handleConnect (не в handleClient) — чтобы не было двойного закрытия.
 */
public class Socks5Server {

  private static final String TAG = "Socks5Server";

  // SOCKS5 протокол
  private static final int SOCKS_VERSION = 5;
  private static final int METHOD_NO_AUTH = 0x00;
  private static final int CMD_CONNECT = 0x01;
  private static final int CMD_UDP_ASSOCIATE = 0x03;
  private static final int ATYP_IPV4 = 0x01;
  private static final int ATYP_DOMAIN = 0x03;
  private static final int ATYP_IPV6 = 0x04;
  private static final int REPLY_SUCCEEDED = 0x00;
  private static final int REPLY_GENERAL_FAILURE = 0x01;
  private static final int REPLY_NETWORK_UNREACHABLE = 0x03;
  private static final int REPLY_HOST_UNREACHABLE = 0x04;
  private static final int REPLY_CONNECTION_REFUSED = 0x05;
  private static final int REPLY_COMMAND_NOT_SUPPORTED = 0x07;
  private static final int REPLY_ADDRESS_TYPE_NOT_SUPPORTED = 0x08;

  /** Таймаут подключения к целевому хосту (TCP). */
  private static final int TARGET_CONNECT_TIMEOUT_MS = 10_000;

  /** Максимальный размер UDP-датаграммы. */
  private static final int UDP_BUFFER_SIZE = 65536;

  private final Context appContext;
  private final MealseLogger log;
  private final int listenPort;

  private volatile boolean running = false;
  private ServerSocket serverSocket;
  private ExecutorService executor;
  private Thread acceptThread;

  private final AtomicInteger activeConnections = new AtomicInteger(0);

  /** Реестр активных UDP-сессий: TCP-канал → UDP-сокет. */
  private final ConcurrentHashMap<Socket, UdpSession> udpSessions = new ConcurrentHashMap<>();

  public Socks5Server(Context context, int listenPort) {
    this.appContext = context.getApplicationContext();
    this.log = MealseLogger.get(appContext, TAG);
    this.listenPort = listenPort;
  }

  /** Запустить сервер (в фоновом потоке). */
  public void start() {
    if (running) {
      log.warn("SOCKS5: уже запущен");
      return;
    }

    executor = Executors.newCachedThreadPool();
    acceptThread = new Thread(this::acceptLoop, "Socks5AcceptThread");
    acceptThread.start();
    log.log("SOCKS5: запускается на порту " + listenPort);
  }

  /** Остановить сервер. */
  public void stop() {
    log.log("SOCKS5: остановка");
    running = false;

    try {
      if (serverSocket != null && !serverSocket.isClosed()) {
        serverSocket.close();
      }
    } catch (IOException ignored) {
    }

    for (UdpSession session : udpSessions.values()) {
      session.close();
    }
    udpSessions.clear();

    if (executor != null) {
      executor.shutdownNow();
    }

    log.log("SOCKS5: остановлен");
  }

  public boolean isRunning() {
    return running;
  }

  // ============================================================
  // Accept-loop
  // ============================================================

  private void acceptLoop() {
    try {
      serverSocket = new ServerSocket();
      serverSocket.setReuseAddress(true);
      serverSocket.bind(new InetSocketAddress("::", listenPort));
      log.log("✓ SOCKS5 сервер слушает [::]:" + listenPort + " (dual-stack)");
      running = true;

      while (running) {
        try {
          Socket client = serverSocket.accept();
          String addr = client.getInetAddress().getHostAddress() + ":" + client.getPort();
          log.log("SOCKS5: accept " + addr);
          executor.submit(() -> handleClient(client));
        } catch (IOException e) {
          if (running) {
            log.logError("SOCKS5 accept", e);
          }
        }
      }
    } catch (IOException e) {
      log.logError("SOCKS5: не удалось запустить ServerSocket на " + listenPort, e);
    }
  }

  // ============================================================
  // Обработка одного клиента
  // ============================================================

  private void handleClient(Socket client) {
    int connId = activeConnections.incrementAndGet();
    UdpSession udpSession = null;

    try {
      client.setSoTimeout(30_000);
      DataInputStream in = new DataInputStream(client.getInputStream());
      DataOutputStream out = new DataOutputStream(client.getOutputStream());

      // --- 1. Handshake: greeting ---
      int ver = in.readUnsignedByte();
      if (ver != SOCKS_VERSION) {
        log.warn("SOCKS5 [" + connId + "]: неверная версия " + ver);
        return;
      }
      int nMethods = in.readUnsignedByte();
      boolean noAuthSupported = false;
      for (int i = 0; i < nMethods; i++) {
        int method = in.readUnsignedByte();
        if (method == METHOD_NO_AUTH)
          noAuthSupported = true;
      }
      if (!noAuthSupported) {
        log.warn("SOCKS5 [" + connId + "]: клиент не поддерживает NO_AUTH");
        out.writeByte(SOCKS_VERSION);
        out.writeByte(0xFF);
        out.flush();
        return;
      }
      out.writeByte(SOCKS_VERSION);
      out.writeByte(METHOD_NO_AUTH);
      out.flush();

      // --- 2. Request ---
      int rVer = in.readUnsignedByte();
      int cmd = in.readUnsignedByte();
      int rsv = in.readUnsignedByte();
      int atyp = in.readUnsignedByte();

      if (rVer != SOCKS_VERSION) {
        log.warn("SOCKS5 [" + connId + "]: неверная версия в запросе " + rVer);
        sendReply(out, REPLY_GENERAL_FAILURE);
        return;
      }

      // --- 3. Парсим целевой адрес ---
      String host;
      int targetPort;
      byte[] rawIpv6 = null;

      if (atyp == ATYP_IPV4) {
        byte[] addr = new byte[4];
        in.readFully(addr);
        host = (addr[0] & 0xFF) + "." + (addr[1] & 0xFF) + "." + (addr[2] & 0xFF) + "."
            + (addr[3] & 0xFF);
        targetPort = in.readUnsignedShort();

      } else if (atyp == ATYP_IPV6) {
        byte[] addr = new byte[16];
        in.readFully(addr);
        rawIpv6 = addr;
        host = InetAddress.getByAddress(addr).getHostAddress();
        targetPort = in.readUnsignedShort();

      } else if (atyp == ATYP_DOMAIN) {
        int len = in.readUnsignedByte();
        byte[] hostBytes = new byte[len];
        in.readFully(hostBytes);
        host = new String(hostBytes, "UTF-8");
        targetPort = in.readUnsignedShort();

      } else {
        log.warn("SOCKS5 [" + connId + "]: неподдерживаемый ATYP=" + atyp);
        sendReply(out, REPLY_ADDRESS_TYPE_NOT_SUPPORTED);
        return;
      }

      log.log("SOCKS5 [" + connId + "]: " + cmdName(cmd) + " " + host + ":" + targetPort + " (atyp="
          + atyp + ")");

      // ============ CMD_CONNECT ============
      if (cmd == CMD_CONNECT) {
        handleConnect(connId, client, in, out, host, targetPort, atyp, rawIpv6);
        return;
      }

      // ============ CMD_UDP_ASSOCIATE ============
      if (cmd == CMD_UDP_ASSOCIATE) {
        udpSession = handleUdpAssociate(connId, client, in, out, atyp);
        return;
      }

      // ============ Неизвестная команда ============
      log.warn("SOCKS5 [" + connId + "]: неподдерживаемая команда cmd=" + cmd);
      sendReply(out, REPLY_COMMAND_NOT_SUPPORTED);

    } catch (Exception e) {
      log.logError("SOCKS5 [" + connId + "] handleClient", e);
    } finally {
      if (udpSession != null) {
        udpSession.close();
        udpSessions.remove(client);
      }
      closeQuietly(client);
      activeConnections.decrementAndGet();
    }
  }

  private String cmdName(int cmd) {
    switch (cmd) {
      case CMD_CONNECT:
        return "CONNECT";
      case CMD_UDP_ASSOCIATE:
        return "UDP_ASSOCIATE";
      default:
        return "CMD_" + cmd;
    }
  }

  // ============================================================
  // CMD_CONNECT (TCP)
  // ============================================================

  /**
   * Обрабатывает CONNECT.
   *
   * ★ Логика выбора адреса:
   *  - ATYP_IPV4   → только IPv4.
   *  - ATYP_DOMAIN → приоритет IPv4 (совместимость); при отсутствии IPv4 — IPv6.
   *  - ATYP_IPV6   → сначала прямое подключение к IPv6; при ошибке сети
   *                  (ENETUNREACH/NoRouteToHost) — fallback через reverse DNS → IPv4.
   */
  private void handleConnect(int connId, Socket client, DataInputStream in, DataOutputStream out,
      String host, int targetPort, int atyp, byte[] rawIpv6) throws IOException {
    Socket target = null;
    try {
      long connectStart = System.currentTimeMillis();

      // ★ Сначала пробуем "предпочтительный" адрес
      InetAddress firstAddr = pickPreferredAddress(host, atyp, rawIpv6, connId);
      if (firstAddr == null) {
        log.warn("SOCKS5 [" + connId + "]: не удалось разрешить адрес для " + host + ":" + targetPort);
        sendReply(out, REPLY_HOST_UNREACHABLE);
        return;
      }

      try {
        target = tryConnect(firstAddr, targetPort);
        long elapsed = System.currentTimeMillis() - connectStart;
        log.log("SOCKS5 [" + connId + "]: connect OK → " + firstAddr.getHostAddress() + ":"
            + targetPort + " за " + elapsed + " мс");
      } catch (IOException firstEx) {
        // ★ Если это IPv6 и ошибка сетевая — пробуем fallback на IPv4
        if (firstAddr instanceof Inet6Address) {
          log.log("SOCKS5 [" + connId + "]: IPv6 connect failed (" + firstEx.getMessage()
              + ") — пробуем fallback на IPv4 через reverse DNS");
          InetAddress v4 = resolveIpv4ViaReverseDns(rawIpv6, host, connId);
          if (v4 != null) {
            try {
              target = tryConnect(v4, targetPort);
              long elapsed = System.currentTimeMillis() - connectStart;
              log.log("SOCKS5 [" + connId + "]: connect OK (IPv4-fallback) → "
                  + v4.getHostAddress() + ":" + targetPort + " за " + elapsed + " мс");
            } catch (IOException secondEx) {
              logConnectFailure(connId, v4, targetPort, secondEx);
              sendReply(out, mapConnectErrorToReply(secondEx));
              return;
            }
          } else {
            log.warn("SOCKS5 [" + connId + "]: fallback IPv4 не найден для " + host
                + " — отдаём ошибку");
            sendReply(out, mapConnectErrorToReply(firstEx));
            return;
          }
        } else {
          logConnectFailure(connId, firstAddr, targetPort, firstEx);
          sendReply(out, mapConnectErrorToReply(firstEx));
          return;
        }
      }

      // Отвечаем: успех
      sendReply(out, REPLY_SUCCEEDED);

      // Relay: двунаправленная передача
      client.setSoTimeout(0);
      target.setSoTimeout(0);

      final Socket fTarget = target;
      final Socket fClient = client;

      Thread upstream = new Thread(() -> relay(fClient, fTarget, "client->target#" + connId),
          "Socks5RelayClientToTarget-" + connId);
      Thread downstream = new Thread(() -> relay(fTarget, fClient, "target->client#" + connId),
          "Socks5RelayTargetToClient-" + connId);
      upstream.start();
      downstream.start();

      try {
        upstream.join();
        downstream.join();
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
      } finally {
        log.log("SOCKS5 [" + connId + "]: relay завершён (оба потока)");
      }
    } finally {
      closeQuietly(target);
    }
  }

  private Socket tryConnect(InetAddress addr, int port) throws IOException {
    Socket s = new Socket();
    s.setTcpNoDelay(true);
    try {
      s.connect(new InetSocketAddress(addr, port), TARGET_CONNECT_TIMEOUT_MS);
      return s;
    } catch (IOException e) {
      closeQuietly(s);
      throw e;
    }
  }

  private void logConnectFailure(int connId, InetAddress addr, int port, IOException e) {
    if (e instanceof java.net.SocketTimeoutException) {
      log.warn("SOCKS5 [" + connId + "]: timeout подключения к " + addr.getHostAddress() + ":"
          + port + " за " + TARGET_CONNECT_TIMEOUT_MS + " мс");
    } else if (e instanceof java.net.ConnectException) {
      log.warn("SOCKS5 [" + connId + "]: connection refused к " + addr.getHostAddress() + ":" + port
          + " — " + e.getMessage());
    } else if (e instanceof java.net.NoRouteToHostException) {
      log.warn("SOCKS5 [" + connId + "]: no route to " + addr.getHostAddress() + ":" + port);
    } else {
      log.warn("SOCKS5 [" + connId + "]: connect failed к " + addr.getHostAddress() + ":" + port
          + " — " + e.getMessage());
    }
  }

  private int mapConnectErrorToReply(IOException e) {
    if (e instanceof java.net.SocketTimeoutException)
      return REPLY_HOST_UNREACHABLE;
    if (e instanceof java.net.ConnectException)
      return REPLY_CONNECTION_REFUSED;
    if (e instanceof java.net.NoRouteToHostException)
      return REPLY_NETWORK_UNREACHABLE;
    return REPLY_HOST_UNREACHABLE;
  }

  // ============================================================
  // CMD_UDP_ASSOCIATE (UDP)
  // ============================================================

  /**
   * Обрабатывает команду UDP ASSOCIATE.
   *
   * 1. Создаёт DatagramSocket, привязанный к [::]:0 (любой свободный порт).
   * 2. Отправляет клиенту SOCKS5-ответ с BND.ADDR:BND.PORT.
   * 3. Проверяет, не закрыл ли клиент TCP сразу после reply — если да,
   *    UDP-сессию не создаём. Это нормальное поведение teapod-tun2socks:
   *    он держит TCP только пока ему нужна UDP-сессия.
   * 4. Создаёт UdpSession и запускает udpReadLoop + tcpWatchLoop.
   *
   * @return созданная UdpSession (для последующего закрытия), либо null.
   */
  private UdpSession handleUdpAssociate(int connId, Socket client, DataInputStream in,
      DataOutputStream out, int atyp) throws IOException {
    DatagramSocket udpSocket = null;
    try {
      udpSocket = new DatagramSocket(null);
      udpSocket.setReuseAddress(true);
      udpSocket.bind(new InetSocketAddress("::", 0));
      udpSocket.setSoTimeout(30_000);

      int bndPort = udpSocket.getLocalPort();
      log.log("SOCKS5 [" + connId + "]: UDP ASSOCIATE → BND=[::]:" + bndPort);

      // Отвечаем клиенту: BND.ADDR = ::, BND.PORT = bndPort
      out.writeByte(SOCKS_VERSION);
      out.writeByte(REPLY_SUCCEEDED);
      out.writeByte(0x00);
      out.writeByte(ATYP_IPV6);
      out.write(new byte[16]); // ::
      out.writeShort(bndPort);
      out.flush();

      // ★ КРИТИЧНО: клиент (teapod-tun2socks) может закрыть TCP-канал
      // сразу после получения reply. Это нормально для UDP-сессий —
      // сессия живёт до истечения UDP-таймаута, а не до закрытия TCP.
      if (client.isClosed()) {
        log.log("SOCKS5 [" + connId + "]: клиент закрыл TCP сразу после reply — "
            + "UDP-сессию не создаём");
        closeQuietly(udpSocket);
        return null;
      }

      UdpSession session = new UdpSession(connId, client, udpSocket, in);
      udpSessions.put(client, session);
      session.start();

      log.log("SOCKS5 [" + connId + "]: UDP-сессия запущена, " + "всего активных UDP-сессий: "
          + udpSessions.size());

      return session;

    } catch (IOException e) {
      log.logError("SOCKS5 [" + connId + "]: не удалось создать UDP-сокет", e);
      closeQuietly(udpSocket);
      try {
        sendReply(out, REPLY_GENERAL_FAILURE);
      } catch (IOException ignored) {
      }
      return null;
    }
  }

  // ============================================================
  // UDP-сессия
  // ============================================================

  private class UdpSession {
    private final int connId;
    private final Socket tcpClient;
    private final DatagramSocket udpSocket;
    private final DataInputStream tcpIn;
    private volatile boolean active = true;
    private Thread udpReaderThread;
    private Thread tcpWatcherThread;

    // Счётчики
    private long packetsFromClient = 0;
    private long bytesFromClient = 0;
    private long packetsToClient = 0;
    private long bytesToClient = 0;
    private long packetsDropped = 0;

    UdpSession(int connId, Socket tcpClient, DatagramSocket udpSocket, DataInputStream tcpIn) {
      this.connId = connId;
      this.tcpClient = tcpClient;
      this.udpSocket = udpSocket;
      this.tcpIn = tcpIn;
    }

    void start() {
      // ★ Двойная страховка: если TCP уже мёртв (закрылся между
      // проверкой в handleUdpAssociate и вызовом start()), потоки
      // не стартуют — иначе tcpWatchLoop упадёт с SocketException.
      if (tcpClient == null || tcpClient.isClosed()) {
        log.log("UDP [" + connId + "]: TCP уже закрыт — сессию не стартуем");
        close();
        return;
      }

      udpReaderThread = new Thread(this::udpReadLoop, "Socks5UdpReader-" + connId);
      udpReaderThread.start();

      tcpWatcherThread = new Thread(this::tcpWatchLoop, "Socks5UdpTcpWatch-" + connId);
      tcpWatcherThread.start();
    }

    private void udpReadLoop() {
      log.log("UDP [" + connId + "]: reader thread started");

      byte[] buffer = new byte[UDP_BUFFER_SIZE];
      DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

      while (active) {
        try {
          udpSocket.receive(packet);
        } catch (java.net.SocketTimeoutException e) {
          if (!active)
            break;
          if (packetsFromClient == 0) {
            log.log("UDP [" + connId
                + "]: таймаут приёма первой датаграммы (30с), сессия продолжает ждать");
          }
          continue;
        } catch (IOException e) {
          if (active) {
            log.logError("UDP [" + connId + "]: ошибка receive", e);
          }
          break;
        }

        packetsFromClient++;
        bytesFromClient += packet.getLength();

        try {
          byte[] data = packet.getData();
          int length = packet.getLength();
          int offset = 0;

          if (length < 4) {
            log.warn("UDP [" + connId + "]: датаграмма слишком короткая (" + length + ")");
            packetsDropped++;
            continue;
          }

          // RSV (2 байта)
          int rsv = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
          offset += 2;
          if (rsv != 0) {
            log.warn("UDP [" + connId + "]: RSV != 0 (" + rsv + ")");
          }

          // FRAG (1 байт)
          int frag = data[offset] & 0xFF;
          offset += 1;
          if (frag != 0) {
            log.warn("UDP [" + connId + "]: FRAG=" + frag + " не поддерживается");
            packetsDropped++;
            continue;
          }

          // ATYP (1 байт)
          int udpAtyp = data[offset] & 0xFF;
          offset += 1;

          String dstHost;
          byte[] dstIpv6 = null;
          if (udpAtyp == ATYP_IPV4) {
            if (length < offset + 4 + 2) {
              packetsDropped++;
              continue;
            }
            dstHost = (data[offset] & 0xFF) + "." + (data[offset + 1] & 0xFF) + "."
                + (data[offset + 2] & 0xFF) + "." + (data[offset + 3] & 0xFF);
            offset += 4;
          } else if (udpAtyp == ATYP_IPV6) {
            if (length < offset + 16 + 2) {
              packetsDropped++;
              continue;
            }
            byte[] addr = new byte[16];
            System.arraycopy(data, offset, addr, 0, 16);
            dstIpv6 = addr;
            dstHost = InetAddress.getByAddress(addr).getHostAddress();
            offset += 16;
          } else if (udpAtyp == ATYP_DOMAIN) {
            if (length < offset + 1) {
              packetsDropped++;
              continue;
            }
            int len = data[offset] & 0xFF;
            offset += 1;
            if (length < offset + len + 2) {
              packetsDropped++;
              continue;
            }
            dstHost = new String(data, offset, len, "UTF-8");
            offset += len;
          } else {
            log.warn("UDP [" + connId + "]: неизвестный ATYP=" + udpAtyp);
            packetsDropped++;
            continue;
          }

          if (length < offset + 2) {
            packetsDropped++;
            continue;
          }
          int dstPort = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
          offset += 2;

          int dataLen = length - offset;
          if (dataLen <= 0) {
            log.warn("UDP [" + connId + "]: пустой payload");
            packetsDropped++;
            continue;
          }
          byte[] payload = new byte[dataLen];
          System.arraycopy(data, offset, payload, 0, dataLen);

          // ★ UDP: приоритет IPv4 (совместимость), fallback — IPv6 при отсутствии IPv4
          InetAddress targetAddr = pickPreferredAddress(dstHost, udpAtyp, dstIpv6, connId);
          if (targetAddr == null) {
            log.warn("UDP [" + connId + "]: не удалось разрешить адрес для " + dstHost + ":"
                + dstPort);
            packetsDropped++;
            continue;
          }

          DatagramPacket outPacket = new DatagramPacket(payload, payload.length,
              new InetSocketAddress(targetAddr, dstPort));
          udpSocket.send(outPacket);

          byte[] respBuffer = new byte[UDP_BUFFER_SIZE];
          DatagramPacket respPacket = new DatagramPacket(respBuffer, respBuffer.length);
          try {
            udpSocket.receive(respPacket);
          } catch (java.net.SocketTimeoutException e) {
            continue;
          } catch (IOException e) {
            if (active) {
              log.warn("UDP [" + connId + "]: ошибка чтения ответа: " + e.getMessage());
            }
            continue;
          }

          packetsToClient++;
          bytesToClient += respPacket.getLength();

          InetAddress senderAddr = respPacket.getAddress();
          int senderPort = respPacket.getPort();
          byte[] senderIp = senderAddr.getAddress();

          byte[] response = new byte[4 + senderIp.length + 2 + respPacket.getLength()];
          int ro = 0;
          response[ro++] = 0; // RSV
          response[ro++] = 0;
          response[ro++] = 0; // FRAG
          if (senderIp.length == 4) {
            response[ro++] = (byte) ATYP_IPV4;
          } else if (senderIp.length == 16) {
            response[ro++] = (byte) ATYP_IPV6;
          } else {
            continue;
          }
          System.arraycopy(senderIp, 0, response, ro, senderIp.length);
          ro += senderIp.length;
          response[ro++] = (byte) ((senderPort >> 8) & 0xFF);
          response[ro++] = (byte) (senderPort & 0xFF);
          System.arraycopy(respPacket.getData(), 0, response, ro, respPacket.getLength());

          DatagramPacket toClient = new DatagramPacket(response, response.length,
              packet.getAddress(), packet.getPort());
          udpSocket.send(toClient);

        } catch (Exception e) {
          log.logError("UDP [" + connId + "]: ошибка обработки датаграммы", e);
        }
      }

      log.log("UDP [" + connId + "]: reader thread stopped;" + " fromClient=" + packetsFromClient
          + "pkt/" + bytesFromClient + "B," + " toClient=" + packetsToClient + "pkt/"
          + bytesToClient + "B," + " dropped=" + packetsDropped);
    }

    private void tcpWatchLoop() {
      // ★ Гонка: TCP мог закрыться между start() и этим методом.
      // Проверяем isClosed() ДО setSoTimeout() — иначе SocketException.
      if (tcpClient == null || tcpClient.isClosed()) {
        log.log("UDP [" + connId + "]: TCP уже закрыт до tcpWatchLoop — выходим");
        active = false;
        close();
        return;
      }

      try {
        tcpClient.setSoTimeout(1000);
      } catch (SocketException e) {
        // ★ Гонка: сокет закрылся прямо перед setSoTimeout.
        // Это штатное завершение, не логируем стектрейс.
        log.log("UDP [" + connId + "]: TCP закрыт во время setSoTimeout — завершаем сессию");
        active = false;
        close();
        return;
      }

      while (active) {
        try {
          int b = tcpIn.read();
          if (b == -1) {
            log.log("UDP [" + connId + "]: TCP-канал закрыт клиентом");
            break;
          }
        } catch (java.net.SocketTimeoutException e) {
          // нормально — проверяем active на следующей итерации
        } catch (SocketException e) {
          // ★ Гонка close/read: штатное завершение
          if (active) {
            log.log("UDP [" + connId + "]: TCP-канал закрыт (SocketException: " + e.getMessage()
                + ")");
          }
          break;
        } catch (IOException e) {
          if (active) {
            log.log("UDP [" + connId + "]: TCP-канал разорван: " + e.getMessage());
          }
          break;
        }
      }

      active = false;
      log.log("UDP [" + connId + "]: TCP-канал закрыт → завершаю сессию");
      close();
    }

    void close() {
      active = false;
      if (udpSocket != null && !udpSocket.isClosed()) {
        udpSocket.close();
      }
    }
  }

  // ============================================================
  // Разрешение целевого адреса
  // ============================================================

  /**
   * Выбирает "предпочтительный" адрес для подключения:
   *
   *  - ATYP_IPV4   → Inet4Address (getByName).
   *  - ATYP_DOMAIN → getAllByName, приоритет IPv4; если IPv4 нет — берём первый IPv6.
   *  - ATYP_IPV6   → Inet6Address напрямую (для попытки connect); fallback на IPv4
   *                  делается уже в handleConnect при ошибке сети.
   */
  private InetAddress pickPreferredAddress(String host, int atyp, byte[] rawIpv6, int connId) {
    if (atyp == ATYP_IPV4) {
      try {
        InetAddress addr = InetAddress.getByName(host);
        if (addr instanceof Inet4Address) {
          log.log("SOCKS5 [" + connId + "]: target IPv4 " + host);
          return addr;
        }
        log.warn("SOCKS5 [" + connId + "]: getByName вернул не IPv4 для " + host + " — "
            + addr.getClass().getSimpleName());
      } catch (Exception e) {
        log.warn("SOCKS5 [" + connId + "]: getByName(" + host + ") — " + e.getMessage());
      }
      return null;
    }

    if (atyp == ATYP_DOMAIN) {
      try {
        InetAddress[] all = InetAddress.getAllByName(host);
        InetAddress v4 = pickFirstIPv4(all);
        if (v4 != null) {
          log.log("SOCKS5 [" + connId + "]: target domain " + host + " → IPv4 "
              + v4.getHostAddress() + " (из " + all.length + " адресов)");
          return v4;
        }
        // ★ Нет IPv4 — берём первый IPv6
        InetAddress v6 = pickFirstIPv6(all);
        if (v6 != null) {
          log.log("SOCKS5 [" + connId + "]: target domain " + host + " → IPv6 (IPv4 не найден): "
              + v6.getHostAddress());
          return v6;
        }
        log.warn("SOCKS5 [" + connId + "]: для " + host + " нет ни IPv4, ни IPv6 ("
            + all.length + " адресов)");
      } catch (Exception e) {
        log.warn("SOCKS5 [" + connId + "]: getAllByName(" + host + ") — " + e.getMessage());
      }
      return null;
    }

    if (atyp == ATYP_IPV6) {
      if (rawIpv6 == null) {
        log.warn("SOCKS5 [" + connId + "]: ATYP_IPV6, но rawIpv6 == null");
        return null;
      }
      try {
        InetAddress addr = InetAddress.getByAddress(rawIpv6);
        log.log("SOCKS5 [" + connId + "]: target IPv6 " + addr.getHostAddress()
            + " (пробуем напрямую)");
        return addr;
      } catch (Exception e) {
        log.warn("SOCKS5 [" + connId + "]: getByAddress(ipv6) — " + e.getMessage());
        return null;
      }
    }

    return null;
  }

  /**
   * Fallback для ATYP_IPV6: пытаемся через reverse DNS найти IPv4-адрес того же хоста.
   * Вызывается только когда прямое подключение к IPv6 упало (ENETUNREACH/NoRouteToHost).
   */
  private InetAddress resolveIpv4ViaReverseDns(byte[] rawIpv6, String host, int connId) {
    if (rawIpv6 == null) {
      log.log("SOCKS5 [" + connId + "]: reverse DNS пропущен — rawIpv6 == null");
      return null;
    }
    try {
      InetAddress addr = InetAddress.getByAddress(rawIpv6);
      String canonical = addr.getCanonicalHostName();
      if (canonical == null || canonical.equals(host)
          || canonical.equals(addr.getHostAddress())) {
        log.log("SOCKS5 [" + connId + "]: reverse DNS не дал имени для " + host);
        return null;
      }
      log.log("SOCKS5 [" + connId + "]: reverse DNS " + host + " → " + canonical);
      InetAddress[] resolved = InetAddress.getAllByName(canonical);
      InetAddress v4 = pickFirstIPv4(resolved);
      if (v4 != null) {
        log.log("SOCKS5 [" + connId + "]: fallback IPv4 для " + host + " → "
            + v4.getHostAddress() + " (через " + canonical + ")");
        return v4;
      }
      log.warn("SOCKS5 [" + connId + "]: у " + canonical + " нет IPv4 среди "
          + resolved.length + " адресов");
    } catch (Exception e) {
      log.log("SOCKS5 [" + connId + "]: reverse DNS для " + host + " — " + e.getMessage());
    }
    return null;
  }

  private static InetAddress pickFirstIPv4(InetAddress[] addresses) {
    if (addresses == null)
      return null;
    for (InetAddress a : addresses) {
      if (a instanceof Inet4Address) {
        return a;
      }
    }
    return null;
  }

  private static InetAddress pickFirstIPv6(InetAddress[] addresses) {
    if (addresses == null)
      return null;
    for (InetAddress a : addresses) {
      if (a instanceof Inet6Address) {
        return a;
      }
    }
    return null;
  }

  // ============================================================
  // Отправка ответов (TCP)
  // ============================================================

  private void sendReply(DataOutputStream out, int replyCode) throws IOException {
    out.writeByte(SOCKS_VERSION);
    out.writeByte(replyCode);
    out.writeByte(0x00); // reserved
    out.writeByte(ATYP_IPV4); // bind addr type
    out.write(new byte[] { 0, 0, 0, 0 }); // bind addr (0.0.0.0)
    out.writeShort(0); // bind port
    out.flush();
  }

  /**
   * Двунаправленный relay: читает из src, пишет в dst. Логирует имя направления, прогресс и итоговые байты.
   */
  private void relay(Socket src, Socket dst, String direction) {
    long totalBytes = 0;
    long startTime = System.currentTimeMillis();
    log.log("relay[" + direction + "] START");
    try (InputStream in = src.getInputStream(); OutputStream out = dst.getOutputStream()) {
      byte[] buf = new byte[8192];
      int r;
      while ((r = in.read(buf)) != -1) {
        out.write(buf, 0, r);
        out.flush();
        totalBytes += r;
        if (totalBytes > 0 && totalBytes % (64 * 1024) < buf.length) {
          log.log("relay[" + direction + "]: " + totalBytes + " B передано");
        }
      }
    } catch (IOException e) {
      long elapsed = System.currentTimeMillis() - startTime;
      log.log("relay[" + direction + "] IO exception за " + elapsed + "ms: "
          + e.getClass().getSimpleName() + ": " + e.getMessage() + " (передано " + totalBytes
          + " B)");
    } finally {
      long elapsed = System.currentTimeMillis() - startTime;
      log.log("relay[" + direction + "] STOP, total=" + totalBytes + " B за " + elapsed + "ms");
    }
  }

  private static void closeQuietly(java.io.Closeable c) {
    if (c == null)
      return;
    try {
      c.close();
    } catch (Throwable ignored) {
    }
  }
}