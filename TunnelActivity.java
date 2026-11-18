package com.my.mealse;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ImageButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.my.mealse.service.TunnelVpnService;
import com.my.mealse.util.BroadcastUtils;
import com.my.mealse.util.MealseLogger;
import com.my.mealse.model.TrustedNode;
import com.my.mealse.util.NodeManager;
import com.my.mealse.BaseActivity;

import java.util.ArrayList;
import java.util.List;

public class TunnelActivity extends Activity {

  private static final String TAG = "TunnelActivity";
  private static final int REQ_VPN_PREPARE = 1001;
  private static final String ACTION_VPN_STATUS = "com.my.mealse.vpn.STATUS";

  private static final String PREF_NAME = "mealse_tunnel_prefs";
  private static final String KEY_MODE = "mode";
  private static final String KEY_GATEWAY_IP = "gateway_ip";
  private static final String KEY_GATEWAY_PORT = "gateway_port";
  private static final String KEY_LISTEN_PORT = "listen_port";

  private static final String DEFAULT_GATEWAY_IP = "";
  private static final int DEFAULT_GATEWAY_PORT = 8083;
  private static final int DEFAULT_LISTEN_PORT = 8083;

  // ===== Views =====
  private TextView tvStatus;
  private TextView tvCurrentMode;
  private TextView tvClientsCount;
  private LinearLayout rgMode;
  private RadioButton rbModeClient;
  private RadioButton rbModeServer;
  private LinearLayout clientFields;
  private LinearLayout serverFields;
  private EditText editIp;
  private EditText editPort;
  private EditText editListenPort;
  private Button btnStart;
  private Button btnStop;

  // ★ НОВОЕ: кнопка и результат теста сети
  private Button btnTestNetwork;
  private TextView tvTestNetworkResult;

  // Логгер
  private MealseLogger log;

  // Состояние
  private boolean receiverRegistered = false;
  private boolean vpnRunning = false;

  // Что передадим в сервис после запроса разрешения VPN
  private String pendingMode;
  private String pendingIp;
  private int pendingPort;
  private int pendingListenPort;

  private String selectedMode = TunnelVpnService.MODE_CLIENT;

  private ImageButton btnPickIpv4;
  private ImageButton btnPickIpv6;

  // + предпочтения
  private static final String KEY_GATEWAY_IP_V6 = "gateway_ip_v6";
  private static final String KEY_LISTEN_IPV6 = "listen_ipv6";
  private static final String KEY_TUN_IPV6 = "tun_ipv6";
  private static final String KEY_TRANSPORT_IPV4 = "transport_ipv4";
  private static final String KEY_TRANSPORT_IPV6 = "transport_ipv6";
  private static final String KEY_GATEWAY_PRIORITY = "gateway_priority";

  private CheckBox cbTransportIpv4, cbTransportIpv6, cbListenIpv6;
  private EditText editGatewayIpV6, editTunIpv6;
  private RadioGroup rgGatewayPriority;
  private RadioButton rbPriorityIpv6, rbPriorityIpv4, rbPriorityAuto;
  private Button btnCheckGateway;
  private TextView tvGatewayCheckResult, tvSelfIpv6;

  // ============================================================
  // VPN BroadcastReceiver
  // ============================================================

  private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      try {
        if (intent == null)
          return;
        String action = intent.getAction();
        log.log("📩 VPN broadcast: action=" + action);

        boolean running = intent.getBooleanExtra(TunnelVpnService.EXTRA_RUNNING, false);
        String message = intent.getStringExtra(TunnelVpnService.EXTRA_MESSAGE);
        String mode = intent.getStringExtra(TunnelVpnService.EXTRA_MODE);

        log.log("📩 running=" + running + ", message=" + message + ", mode=" + mode);

        updateStatus(running, message);
        updateCurrentMode(mode);
        updateClientsCount(message);
      } catch (Exception e) {
        logError("Ошибка в statusReceiver", e);
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

    try {
      log.log("Шаг 1: setContentView");
      setContentView(R.layout.activity_tunnel);
      log.log("Шаг 1: OK");

      log.log("Шаг 2: findViewById");
      tvStatus = findViewById(R.id.tv_tunnel_status);
      tvCurrentMode = findViewById(R.id.tv_current_mode);
      tvClientsCount = findViewById(R.id.tv_clients_count);
      rgMode = findViewById(R.id.rg_mode);
      rbModeClient = findViewById(R.id.rb_mode_client);
      rbModeServer = findViewById(R.id.rb_mode_server);
      clientFields = findViewById(R.id.client_fields);
      serverFields = findViewById(R.id.server_fields);
      editIp = findViewById(R.id.edit_gateway_ip);
      editPort = findViewById(R.id.edit_gateway_port);
      editListenPort = findViewById(R.id.edit_listen_port);
      btnStart = findViewById(R.id.btn_start_tunnel);
      btnStop = findViewById(R.id.btn_stop_tunnel);
      btnPickIpv4 = findViewById(R.id.btn_pick_ipv4);
      btnPickIpv6 = findViewById(R.id.btn_pick_ipv6);

      cbTransportIpv4 = findViewById(R.id.cb_transport_ipv4);
      cbTransportIpv6 = findViewById(R.id.cb_transport_ipv6);
      cbListenIpv6 = findViewById(R.id.cb_listen_ipv6);

      editGatewayIpV6 = findViewById(R.id.edit_gateway_ip_v6);
      editTunIpv6 = findViewById(R.id.edit_tun_ipv6);

      rgGatewayPriority = findViewById(R.id.rg_gateway_priority);
      rbPriorityIpv6 = findViewById(R.id.rb_priority_ipv6);
      rbPriorityIpv4 = findViewById(R.id.rb_priority_ipv4);
      rbPriorityAuto = findViewById(R.id.rb_priority_auto);

      btnCheckGateway = findViewById(R.id.btn_check_gateway);
      tvGatewayCheckResult = findViewById(R.id.tv_gateway_check_result);
      tvSelfIpv6 = findViewById(R.id.tv_self_ipv6);

      // ★ НОВОЕ: кнопка и TextView теста сети
      btnTestNetwork = findViewById(R.id.btn_test_network);
      tvTestNetworkResult = findViewById(R.id.tv_test_network_result);

      checkViewNotNull(tvStatus, "tv_tunnel_status");
      checkViewNotNull(tvCurrentMode, "tv_current_mode");
      checkViewNotNull(rgMode, "rg_mode");
      checkViewNotNull(rbModeClient, "rb_mode_client");
      checkViewNotNull(rbModeServer, "rb_mode_server");
      checkViewNotNull(clientFields, "client_fields");
      checkViewNotNull(serverFields, "server_fields");
      checkViewNotNull(btnStart, "btn_start_tunnel");
      checkViewNotNull(btnStop, "btn_stop_tunnel");
      // ★ btnTestNetwork и tvTestNetworkResult — необязательные View.
      // Если их нет в разметке (старая версия XML), просто залогируем.
      if (btnTestNetwork == null) {
        log.warn(
            "btn_test_network не найден — тест сети недоступен (обновите activity_tunnel.xml)");
      }
      if (tvTestNetworkResult == null) {
        log.warn("tv_test_network_result не найден — результат теста будет только в логах");
      }
      log.log("Шаг 2: OK");

      log.log("Шаг 3: setupClickListeners");
      btnStart.setOnClickListener(v -> {
        log.log("Клик: btnStart");
        onStartClicked();
      });
      btnStop.setOnClickListener(v -> {
        log.log("Клик: btnStop");
        onStopClicked();
      });

      rbModeClient.setOnClickListener(v -> selectMode(TunnelVpnService.MODE_CLIENT));
      rbModeServer.setOnClickListener(v -> selectMode(TunnelVpnService.MODE_SERVER));

      if (btnPickIpv4 != null) {
        btnPickIpv4.setOnClickListener(v -> showNodePickerDialog(true));
      }
      if (btnPickIpv6 != null) {
        btnPickIpv6.setOnClickListener(v -> showNodePickerDialog(false));
      }
      if (btnCheckGateway != null) {
        btnCheckGateway.setOnClickListener(v -> checkGatewayAvailability());
      }
      // ★ НОВОЕ: обработчик теста сети
      if (btnTestNetwork != null) {
        btnTestNetwork.setOnClickListener(v -> onTestNetworkClicked());
      }

      log.log("Шаг 3: OK");

      log.log("Шаг 4: загрузка сохранённых настроек и применение Intent");
      loadPrefsAndIntent();
      log.log("Шаг 4: OK");

      log.log("Шаг 5: начальный статус");
      updateStatus(false, "VPN выключен");
      updateCurrentMode(null);
      updateClientsCount(null);
      updateFieldsVisibility();
      log.log("Шаг 5: OK");

      log.log("══ onCreate END ══");

    } catch (Exception e) {
      logError("КРИТИЧЕСКАЯ ОШИБКА в onCreate", e);
      Toast.makeText(this, "Ошибка запуска: " + e.getMessage(), Toast.LENGTH_LONG).show();
      finish();
    }
  }

  private void showNodePickerDialog(boolean forIpv4) {
    try {
      NodeManager nm = new NodeManager(this);
      List<TrustedNode> all = nm.getAllNodes();

      List<TrustedNode> candidates = new ArrayList<>();
      for (TrustedNode n : all) {
        if (forIpv4) {
          if ((n.getIpAddress() != null && !n.getIpAddress().isEmpty())
              || (n.getGatewayIp() != null && !n.getGatewayIp().isEmpty())) {
            candidates.add(n);
          }
        } else {
          if (n.hasIpv6() || n.hasGatewayIpv6()) {
            candidates.add(n);
          }
        }
      }

      if (candidates.isEmpty()) {
        Toast.makeText(this,
            forIpv4 ? "Нет узлов с сохранённым IPv4" : "Нет узлов с сохранённым IPv6",
            Toast.LENGTH_LONG).show();
        return;
      }

      String[] labels = new String[candidates.size()];
      for (int i = 0; i < candidates.size(); i++) {
        TrustedNode n = candidates.get(i);
        String name = n.getName() != null ? n.getName() : n.getKey();
        String addr;
        if (forIpv4) {
          if (n.getGatewayIp() != null && !n.getGatewayIp().isEmpty()) {
            addr = n.getGatewayIp() + " (шлюз)";
          } else {
            addr = n.getIpAddress();
          }
        } else {
          if (n.hasGatewayIpv6()) {
            addr = n.getGatewayIpv6() + " (шлюз)";
          } else {
            addr = n.getIpv6Address();
          }
        }
        labels[i] = name + "  —  " + addr;
      }

      new AlertDialog.Builder(this)
          .setTitle(forIpv4 ? "Выбрать IPv4 из узлов" : "Выбрать IPv6 из узлов")
          .setItems(labels, (d, which) -> {
            TrustedNode n = candidates.get(which);

            if (forIpv4) {
              String ip = (n.getGatewayIp() != null && !n.getGatewayIp().isEmpty())
                  ? n.getGatewayIp()
                  : n.getIpAddress();
              if (editIp != null)
                editIp.setText(ip);

              int port = (n.getGatewayPort() > 0) ? n.getGatewayPort() : n.getPort();
              if (editPort != null && port > 0) {
                editPort.setText(String.valueOf(port));
              }
              log.log("IPv4 подставлен из узла: " + n.getName() + " → " + ip);
            } else {
              String ipv6 = n.hasGatewayIpv6() ? n.getGatewayIpv6() : n.getIpv6Address();
              if (editGatewayIpV6 != null) {
                editGatewayIpV6.setText(ipv6);
              }
              log.log("IPv6 подставлен из узла: " + n.getName() + " → " + ipv6);
            }
          }).setNegativeButton("Отмена", null).show();
    } catch (Exception e) {
      logError("showNodePickerDialog", e);
    }
  }

  private void checkGatewayAvailability() {
    final String ipV4 = editIp != null ? editIp.getText().toString().trim() : "";
    final String ipV6 = editGatewayIpV6 != null ? editGatewayIpV6.getText().toString().trim() : "";
    final int port;
    try {
      port = Integer.parseInt(editPort != null ? editPort.getText().toString().trim() : "8083");
    } catch (NumberFormatException e) {
      Toast.makeText(this, "Неверный порт", Toast.LENGTH_SHORT).show();
      return;
    }

    if (ipV4.isEmpty() && ipV6.isEmpty()) {
      Toast.makeText(this, "Укажите хотя бы один адрес шлюза", Toast.LENGTH_SHORT).show();
      return;
    }

    if (tvGatewayCheckResult != null) {
      tvGatewayCheckResult.setVisibility(TextView.VISIBLE);
      tvGatewayCheckResult.setText("Проверка…");
      tvGatewayCheckResult.setTextColor(0xFF4A5568);
    }
    if (btnCheckGateway != null)
      btnCheckGateway.setEnabled(false);

    new Thread(() -> {
      StringBuilder sb = new StringBuilder();
      log.log("── Gateway check START ──");

      // Шаг 1: TCP-проверка порта VPN (8083) по IPv4
      if (!ipV4.isEmpty()) {
        long t = probeTcpWithTime(ipV4, port);
        if (t >= 0) {
          sb.append("✅ Шлюз ").append(ipV4).append(":").append(port).append(" доступен (").append(t)
              .append(" мс)\n");
          log.log("Gateway check: tcp v4 = true (" + t + "ms)");
        } else {
          sb.append("❌ Шлюз ").append(ipV4).append(":").append(port).append(" недоступен\n");
          log.log("Gateway check: tcp v4 = false");
        }
      } else {
        sb.append("— IPv4 не задан\n");
      }

      // Шаг 2: TCP-проверка порта VPN (8083) по IPv6
      if (!ipV6.isEmpty()) {
        long t = probeTcpWithTime(ipV6, port);
        if (t >= 0) {
          sb.append("✅ Шлюз [").append(ipV6).append("]:").append(port).append(" доступен (")
              .append(t).append(" мс)\n");
          log.log("Gateway check: tcp v6 = true (" + t + "ms)");
        } else {
          sb.append("❌ Шлюз [").append(ipV6).append("]:").append(port).append(" недоступен\n");
          log.log("Gateway check: tcp v6 = false");
        }
      } else {
        sb.append("— IPv6 не задан\n");
      }

      // Шаг 3: SOCKS5-проверка шлюза (порт 1080) — только по IPv4
      // Это ключевая новая проверка: клиент пробует SOCKS5 так, как это делал бы hev.
      if (!ipV4.isEmpty()) {
        sb.append("\n");
        sb.append("── Проверка SOCKS5 на шлюзе ──\n");

        boolean socksPortOpen = probeTcp(ipV4, 1080, 3000);
        sb.append(socksPortOpen ? "✅ SOCKS5-порт " + ipV4 + ":1080 открыт\n"
            : "❌ SOCKS5-порт " + ipV4 + ":1080 закрыт\n");
        log.log("Gateway check: socks5 port open = " + socksPortOpen);

        if (socksPortOpen) {
          // Handshake + CONNECT к 1.1.1.1:443 через SOCKS5 на шлюзе
          boolean socksConnect = socks5ConnectTest(ipV4, 1080, "1.1.1.1", 443, 8000);
          sb.append(socksConnect ? "✅ SOCKS5 CONNECT → 1.1.1.1:443 OK\n"
              : "❌ SOCKS5 CONNECT → 1.1.1.1:443 FAIL\n");
          log.log("Gateway check: socks5 CONNECT v4 = " + socksConnect);

          // Реальный HTTP через SOCKS5 на шлюзе
          String httpStatus = socks5HttpGetTest(ipV4, 1080, "example.com", 80, "/", 10000);
          boolean httpOk = httpStatus != null && httpStatus.contains("200");
          sb.append(httpOk ? "✅ HTTP через SOCKS5 шлюза: " + httpStatus + "\n"
              : "❌ HTTP через SOCKS5 шлюза FAIL: "
                  + (httpStatus != null ? httpStatus : "нет ответа") + "\n");
          log.log("Gateway check: http via remote SOCKS5 = " + httpOk + " (" + httpStatus + ")");
        }
      }

      sb.append("\n");
      sb.append("Если SOCKS5-порт открыт, но CONNECT/HTTP падают — проблема на стороне шлюза.\n");
      sb.append("Если всё зелёное — можно звонить/подключаться.");

      final String result = sb.toString();

      runOnUiThread(() -> {
        if (tvGatewayCheckResult != null) {
          tvGatewayCheckResult.setText(result);
          tvGatewayCheckResult.setTextColor(0xFF2F855A); // зелёный — детали в тексте
        }
        if (btnCheckGateway != null)
          btnCheckGateway.setEnabled(true);
      });

      log.log("── Gateway check END ──");
    }, "GatewayCheckThread").start();
  }
  
  

  /** Старый probeTcp для кнопки «Проверить шлюз» — возвращает время или -1. */
  private long probeTcpWithTime(String host, int port) {
    java.net.Socket s = null;
    try {
      long start = System.currentTimeMillis();
      s = new java.net.Socket();
      s.connect(new java.net.InetSocketAddress(host, port), 3000);
      long elapsed = System.currentTimeMillis() - start;
      return elapsed;
    } catch (Exception e) {
      return -1;
    } finally {
      if (s != null) {
        try {
          s.close();
        } catch (Exception ignored) {
        }
      }
    }
  }

  // ============================================================
  // ★ НОВОЕ: Тест сети
  // ============================================================

  private void onTestNetworkClicked() {
    log.log("onTestNetworkClicked START");

    if (!TunnelVpnService.MODE_SERVER.equals(selectedMode)) {
      Toast.makeText(this, "Тест сети доступен только в режиме сервера", Toast.LENGTH_SHORT).show();
      return;
    }
    if (!vpnRunning) {
      Toast.makeText(this, "Сначала запустите VPN-сервер", Toast.LENGTH_SHORT).show();
      return;
    }

    if (btnTestNetwork != null)
      btnTestNetwork.setEnabled(false);
    if (tvTestNetworkResult != null) {
      tvTestNetworkResult.setVisibility(TextView.VISIBLE);
      tvTestNetworkResult.setText("Тестирование...");
      tvTestNetworkResult.setTextColor(0xFF4A5568);
    }

    new Thread(() -> {
      StringBuilder sb = new StringBuilder();
      log.log("── Network test START ──");

      // 1. Прямой интернет сервера
      boolean directOk = probeTcp("1.1.1.1", 53, 5000);
      sb.append(directOk ? "✅ Интернет: есть (1.1.1.1:53)\n"
          : "❌ Интернет: НЕТ (1.1.1.1:53 недоступен)\n");
      log.log("Network test: direct internet = " + directOk);

      // 2. SOCKS5 слушает локально
      boolean socksListening = probeTcp("127.0.0.1", 1080, 2000);
      sb.append(socksListening ? "✅ SOCKS5 слушает 127.0.0.1:1080\n"
          : "❌ SOCKS5 НЕ слушает 127.0.0.1:1080\n");
      log.log("Network test: socks5 listening = " + socksListening);

      // 3. SOCKS5 проксирует TCP CONNECT к IPv4
      boolean socksV4Ok = false;
      if (socksListening) {
        socksV4Ok = socks5ConnectTest("127.0.0.1", 1080, "1.1.1.1", 443, 8000);
      }
      sb.append(socksV4Ok ? "✅ SOCKS5 → 1.1.1.1:443 (IPv4) OK\n"
          : "❌ SOCKS5 → 1.1.1.1:443 (IPv4) FAIL\n");
      log.log("Network test: socks5 → IPv4 = " + socksV4Ok);

      // 4. ★ НОВОЕ: SOCKS5 проксирует TCP CONNECT к IPv6
      boolean socksV6Ok = false;
      if (socksListening) {
        socksV6Ok = socks5ConnectTest("127.0.0.1", 1080, "2606:4700:4700::1111", 443, 8000);
      }
      sb.append(socksV6Ok ? "✅ SOCKS5 → [2606:4700:4700::1111]:443 (IPv6) OK\n"
          : "❌ SOCKS5 → [2606:4700:4700::1111]:443 (IPv6) FAIL (оператор не маршрутизирует IPv6?)\n");
      log.log("Network test: socks5 → IPv6 = " + socksV6Ok);

      // 5. ★ НОВОЕ: SOCKS5 с DOMAINNAME (remote DNS на стороне SOCKS5)
      boolean socksDomainOk = false;
      if (socksListening) {
        socksDomainOk = socks5ConnectTest("127.0.0.1", 1080, "example.com", 80, 8000);
      }
      sb.append(socksDomainOk ? "✅ SOCKS5 → example.com:80 (domain) OK\n"
          : "❌ SOCKS5 → example.com:80 (domain) FAIL\n");
      log.log("Network test: socks5 → domain = " + socksDomainOk);

      // 6. ★ НОВОЕ: реальный HTTP-запрос через SOCKS5
      String httpResult = socks5HttpGetTest("127.0.0.1", 1080, "example.com", 80, "/", 8000);
      boolean httpOk = httpResult != null && httpResult.contains("200");
      sb.append(httpOk ? "✅ HTTP через SOCKS5: 200 OK\n"
          : "❌ HTTP через SOCKS5 FAIL: " + (httpResult != null ? httpResult : "no response")
              + "\n");
      log.log("Network test: HTTP via SOCKS5 = " + httpOk + " (" + httpResult + ")");

      // 7. DNS
      boolean dnsOk = dnsResolveTest("google.com");
      sb.append(dnsOk ? "✅ DNS работает (google.com)\n" : "❌ DNS не работает (google.com)\n");
      log.log("Network test: dns = " + dnsOk);

      final boolean allOk = directOk && socksListening && socksV4Ok && dnsOk;
      log.log("── Network test END: allOk=" + allOk + " ──");
      log.log("   v4=" + socksV4Ok + " v6=" + socksV6Ok + " domain=" + socksDomainOk + " http="
          + httpOk + " ──");

      final String result = sb.toString();

      runOnUiThread(() -> {
        if (tvTestNetworkResult != null) {
          tvTestNetworkResult.setText(result);
          tvTestNetworkResult.setTextColor(allOk ? 0xFF2F855A : 0xFFC53030);
        }
        if (btnTestNetwork != null)
          btnTestNetwork.setEnabled(true);
      });
    }, "NetworkTestThread").start();
  }

  /**
   * Делает полноценный HTTP GET через SOCKS5 и возвращает статус-строку ответа, или null при ошибке.
   */
  private String socks5HttpGetTest(String proxyHost, int proxyPort, String targetHost,
      int targetPort, String path, int timeoutMs) {
    java.net.Socket s = null;
    try {
      s = new java.net.Socket();
      s.connect(new java.net.InetSocketAddress(proxyHost, proxyPort), timeoutMs);
      s.setSoTimeout(timeoutMs);
      s.setTcpNoDelay(true);

      java.io.OutputStream out = s.getOutputStream();
      java.io.InputStream in = s.getInputStream();

      // 1. Greeting
      out.write(new byte[] { 0x05, 0x01, 0x00 });
      out.flush();
      byte[] greet = new byte[2];
      readFully(in, greet);
      if (greet[0] != 0x05 || greet[1] != 0x00)
        return null;

      // 2. CONNECT (ATYP=DOMAIN)
      byte[] hostBytes = targetHost.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      byte[] req = new byte[4 + 1 + hostBytes.length + 2];
      req[0] = 0x05;
      req[1] = 0x01;
      req[2] = 0x00;
      req[3] = 0x03;
      req[4] = (byte) hostBytes.length;
      System.arraycopy(hostBytes, 0, req, 5, hostBytes.length);
      req[5 + hostBytes.length] = (byte) ((targetPort >> 8) & 0xFF);
      req[5 + hostBytes.length + 1] = (byte) (targetPort & 0xFF);
      out.write(req);
      out.flush();

      // 3. Reply
      byte[] head = new byte[4];
      readFully(in, head);
      if (head[0] != 0x05)
        return null;
      int rep = head[1] & 0xFF;
      int atyp = head[3] & 0xFF;
      int bndLen;
      if (atyp == 0x01)
        bndLen = 4;
      else if (atyp == 0x04)
        bndLen = 16;
      else if (atyp == 0x03) {
        byte[] lenBuf = new byte[1];
        readFully(in, lenBuf);
        bndLen = lenBuf[0] & 0xFF;
      } else
        return null;
      if (bndLen > 0)
        readFully(in, new byte[bndLen]);
      readFully(in, new byte[2]);

      if (rep != 0x00)
        return "SOCKS5 reply rep=" + rep;

      // 4. HTTP GET
      String httpReq = "GET " + path + " HTTP/1.0\r\n" + "Host: " + targetHost + "\r\n"
          + "User-Agent: Mealse/1.0\r\n" + "Connection: close\r\n\r\n";
      out.write(httpReq.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      out.flush();

      // 5. Читаем статус-строку
      StringBuilder statusLine = new StringBuilder();
      int b;
      int count = 0;
      while ((b = in.read()) != -1 && count < 512) {
        count++;
        if (b == '\r' || b == '\n')
          break;
        statusLine.append((char) b);
      }
      return statusLine.toString();
    } catch (Exception e) {
      log.log(
          "socks5HttpGetTest exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
      return null;
    } finally {
      if (s != null) {
        try {
          s.close();
        } catch (Exception ignored) {
        }
      }
    }
  }

  /** Простой TCP-probe: true/false. */
  private boolean probeTcp(String host, int port, int timeoutMs) {
    java.net.Socket s = null;
    try {
      s = new java.net.Socket();
      s.connect(new java.net.InetSocketAddress(host, port), timeoutMs);
      return true;
    } catch (Exception e) {
      return false;
    } finally {
      if (s != null) {
        try {
          s.close();
        } catch (Exception ignored) {
        }
      }
    }
  }

  /**
   * Минимальный тест SOCKS5: handshake + CONNECT. Возвращает true, если сервер вернул успешный reply (0x00).
   */
  private boolean socks5ConnectTest(String proxyHost, int proxyPort, String targetHost,
      int targetPort, int timeoutMs) {
    java.net.Socket s = null;
    try {
      s = new java.net.Socket();
      s.connect(new java.net.InetSocketAddress(proxyHost, proxyPort), timeoutMs);
      s.setSoTimeout(timeoutMs);
      s.setTcpNoDelay(true);

      java.io.OutputStream out = s.getOutputStream();
      java.io.InputStream in = s.getInputStream();

      // 1. Greeting: version=5, nmethods=1, method=0x00 (NO_AUTH)
      out.write(new byte[] { 0x05, 0x01, 0x00 });
      out.flush();

      byte[] greetingResp = new byte[2];
      readFully(in, greetingResp);
      if (greetingResp[0] != 0x05 || greetingResp[1] != 0x00) {
        log.log("  socks5ConnectTest: greeting rejected: ver=" + (greetingResp[0] & 0xFF)
            + ", method=" + (greetingResp[1] & 0xFF));
        return false;
      }

      // 2. CONNECT на targetHost:targetPort через ATYP=DOMAIN
      byte[] hostBytes = targetHost.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      byte[] req = new byte[4 + 1 + hostBytes.length + 2];
      req[0] = 0x05; // VER
      req[1] = 0x01; // CMD = CONNECT
      req[2] = 0x00; // RSV
      req[3] = 0x03; // ATYP = DOMAIN
      req[4] = (byte) hostBytes.length;
      System.arraycopy(hostBytes, 0, req, 5, hostBytes.length);
      req[5 + hostBytes.length] = (byte) ((targetPort >> 8) & 0xFF);
      req[5 + hostBytes.length + 1] = (byte) (targetPort & 0xFF);
      out.write(req);
      out.flush();

      // 3. Читаем reply: VER REP RSV ATYP BND.ADDR BND.PORT
      byte[] head = new byte[4];
      readFully(in, head);

      if (head[0] != 0x05) {
        log.log("  socks5ConnectTest: bad VER in reply: " + (head[0] & 0xFF));
        return false;
      }

      int rep = head[1] & 0xFF;
      int atyp = head[3] & 0xFF;

      // Пропускаем BND.ADDR
      int bndAddrLen;
      if (atyp == 0x01) {
        bndAddrLen = 4;
      } else if (atyp == 0x04) {
        bndAddrLen = 16;
      } else if (atyp == 0x03) {
        byte[] lenBuf = new byte[1];
        readFully(in, lenBuf);
        bndAddrLen = lenBuf[0] & 0xFF;
      } else {
        log.log("  socks5ConnectTest: unexpected ATYP=" + atyp);
        return false;
      }
      if (bndAddrLen > 0) {
        readFully(in, new byte[bndAddrLen]);
      }
      // Пропускаем BND.PORT
      readFully(in, new byte[2]);

      log.log("  socks5ConnectTest: reply rep=" + rep + " (0x00 = success)");
      return rep == 0x00;
    } catch (Exception e) {
      log.log(
          "  socks5ConnectTest: exception " + e.getClass().getSimpleName() + ": " + e.getMessage());
      return false;
    } finally {
      if (s != null) {
        try {
          s.close();
        } catch (Exception ignored) {
        }
      }
    }
  }

  /** Читает ровно buf.length байт или бросает EOFException. */
  private void readFully(java.io.InputStream in, byte[] buf) throws java.io.IOException {
    int off = 0;
    while (off < buf.length) {
      int n = in.read(buf, off, buf.length - off);
      if (n < 0)
        throw new java.io.EOFException("stream closed: read " + off + "/" + buf.length);
      off += n;
    }
  }

  private boolean dnsResolveTest(String host) {
    try {
      java.net.InetAddress[] addrs = java.net.InetAddress.getAllByName(host);
      return addrs != null && addrs.length > 0;
    } catch (Exception e) {
      return false;
    }
  }

  // ============================================================
  // ★ КОНЕЦ НОВОГО БЛОКА
  // ============================================================

  private void checkViewNotNull(Object view, String id) {
    if (view == null) {
      String msg = "View не найден: " + id + " — проверь activity_tunnel.xml";
      log.error(msg);
      throw new IllegalStateException(msg);
    }
  }

  private void selectMode(String mode) {
    selectedMode = mode;
    rbModeClient.setChecked(TunnelVpnService.MODE_CLIENT.equals(mode));
    rbModeServer.setChecked(TunnelVpnService.MODE_SERVER.equals(mode));
    log.log("Режим переключён: " + selectedMode);
    updateFieldsVisibility();
  }

  private void loadPrefsAndIntent() {
    SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);

    String savedMode = prefs.getString(KEY_MODE, TunnelVpnService.MODE_CLIENT);
    String savedGatewayIp = prefs.getString(KEY_GATEWAY_IP, DEFAULT_GATEWAY_IP);
    int savedGatewayPort = prefs.getInt(KEY_GATEWAY_PORT, DEFAULT_GATEWAY_PORT);
    int savedListenPort = prefs.getInt(KEY_LISTEN_PORT, DEFAULT_LISTEN_PORT);

    String savedGatewayIpV6 = prefs.getString(KEY_GATEWAY_IP_V6, "");
    boolean savedListenIpv6 = prefs.getBoolean(KEY_LISTEN_IPV6, true);
    String savedTunIpv6 = prefs.getString(KEY_TUN_IPV6, "fd00::1");
    boolean savedTransportIpv4 = prefs.getBoolean(KEY_TRANSPORT_IPV4, true);
    boolean savedTransportIpv6 = prefs.getBoolean(KEY_TRANSPORT_IPV6, true);
    String savedPriority = prefs.getString(KEY_GATEWAY_PRIORITY, "ipv6");

    log.log("loadPrefsAndIntent: prefs →" + " mode=" + savedMode + ", gatewayIp=" + savedGatewayIp
        + ", gatewayPort=" + savedGatewayPort + ", listenPort=" + savedListenPort + ", gatewayIpV6="
        + savedGatewayIpV6 + ", listenIpv6=" + savedListenIpv6 + ", tunIpv6=" + savedTunIpv6
        + ", transport v4=" + savedTransportIpv4 + " v6=" + savedTransportIpv6 + ", priority="
        + savedPriority);

    Intent intent = getIntent();
    if (intent != null) {
      String ipFromIntent = intent.getStringExtra("gateway_ip");
      int portFromIntent = intent.getIntExtra("gateway_port", -1);
      String modeFromIntent = intent.getStringExtra("mode");
      int listenPortFromIntent = intent.getIntExtra("listen_port", -1);
      String ipV6FromIntent = intent.getStringExtra("gateway_ip_v6");
      boolean listenIpv6FromIntent = intent.getBooleanExtra("listen_ipv6", savedListenIpv6);
      String tunIpv6FromIntent = intent.getStringExtra("tun_ipv6");
      String priorityFromIntent = intent.getStringExtra("gateway_priority");

      if (modeFromIntent != null) {
        if (TunnelVpnService.MODE_SERVER.equalsIgnoreCase(modeFromIntent)) {
          savedMode = TunnelVpnService.MODE_SERVER;
        } else {
          savedMode = TunnelVpnService.MODE_CLIENT;
        }
        log.log("Intent: mode=" + modeFromIntent + " → " + savedMode);
      }
      if (ipFromIntent != null) {
        savedGatewayIp = ipFromIntent;
      }
      if (portFromIntent > 0) {
        savedGatewayPort = portFromIntent;
      }
      if (listenPortFromIntent > 0) {
        savedListenPort = listenPortFromIntent;
      }
      if (ipV6FromIntent != null) {
        savedGatewayIpV6 = ipV6FromIntent;
      }
      savedListenIpv6 = listenIpv6FromIntent;
      if (tunIpv6FromIntent != null && !tunIpv6FromIntent.isEmpty()) {
        savedTunIpv6 = tunIpv6FromIntent;
      }
      if (priorityFromIntent != null && !priorityFromIntent.isEmpty()) {
        savedPriority = priorityFromIntent;
      }
    }

    selectedMode = savedMode;

    if (editIp != null)
      editIp.setText(savedGatewayIp);
    if (editPort != null)
      editPort.setText(String.valueOf(savedGatewayPort));
    if (editListenPort != null)
      editListenPort.setText(String.valueOf(savedListenPort));

    if (editGatewayIpV6 != null)
      editGatewayIpV6.setText(savedGatewayIpV6);
    if (cbListenIpv6 != null)
      cbListenIpv6.setChecked(savedListenIpv6);
    if (editTunIpv6 != null)
      editTunIpv6.setText(savedTunIpv6);
    if (cbTransportIpv4 != null)
      cbTransportIpv4.setChecked(savedTransportIpv4);
    if (cbTransportIpv6 != null)
      cbTransportIpv6.setChecked(savedTransportIpv6);

    if (rgGatewayPriority != null) {
      if ("ipv4".equals(savedPriority) && rbPriorityIpv4 != null) {
        rbPriorityIpv4.setChecked(true);
      } else if ("auto".equals(savedPriority) && rbPriorityAuto != null) {
        rbPriorityAuto.setChecked(true);
      } else if (rbPriorityIpv6 != null) {
        rbPriorityIpv6.setChecked(true);
      }
    }

    rbModeClient.setChecked(TunnelVpnService.MODE_CLIENT.equals(savedMode));
    rbModeServer.setChecked(TunnelVpnService.MODE_SERVER.equals(savedMode));

    if (tvSelfIpv6 != null) {
      try {
        String selfV6 = com.my.mealse.util.DeviceInfoHelper.getIpv6Address(this);
        tvSelfIpv6.setText(selfV6 != null ? selfV6 : "не обнаружен");
      } catch (Exception e) {
        log.warn("tvSelfIpv6: " + e.getMessage());
        tvSelfIpv6.setText("ошибка: " + e.getMessage());
      }
    }

    updateFieldsVisibility();

    log.log("loadPrefsAndIntent: применено mode=" + savedMode);
  }

  private void savePrefs(String mode, String gatewayIp, int gatewayPort, int listenPort,
      String gatewayIpV6, boolean listenIpv6, String tunIpv6, boolean transportIpv4,
      boolean transportIpv6, String gatewayPriority) {
    try {
      getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putString(KEY_MODE, mode)
          .putString(KEY_GATEWAY_IP, gatewayIp != null ? gatewayIp : "")
          .putInt(KEY_GATEWAY_PORT, gatewayPort).putInt(KEY_LISTEN_PORT, listenPort)
          .putString(KEY_GATEWAY_IP_V6, gatewayIpV6 != null ? gatewayIpV6 : "")
          .putBoolean(KEY_LISTEN_IPV6, listenIpv6)
          .putString(KEY_TUN_IPV6, tunIpv6 != null && !tunIpv6.isEmpty() ? tunIpv6 : "fd00::1")
          .putBoolean(KEY_TRANSPORT_IPV4, transportIpv4)
          .putBoolean(KEY_TRANSPORT_IPV6, transportIpv6)
          .putString(KEY_GATEWAY_PRIORITY, gatewayPriority != null ? gatewayPriority : "ipv6")
          .apply();
      log.log("savePrefs: mode=" + mode + ", gwV4=" + gatewayIp + ":" + gatewayPort + ", gwV6="
          + gatewayIpV6 + ", listenPort=" + listenPort + ", listenV6=" + listenIpv6 + ", tunV6="
          + tunIpv6 + ", transport v4=" + transportIpv4 + " v6=" + transportIpv6 + ", priority="
          + gatewayPriority);
    } catch (Exception e) {
      logError("Ошибка savePrefs", e);
    }
  }

  private String currentPriority() {
    if (rbPriorityIpv4 != null && rbPriorityIpv4.isChecked())
      return "ipv4";
    if (rbPriorityAuto != null && rbPriorityAuto.isChecked())
      return "auto";
    return "ipv6";
  }

  private boolean cbChecked(CheckBox cb, boolean defaultValue) {
    return cb != null ? cb.isChecked() : defaultValue;
  }

  private String textOf(EditText et) {
    return et != null ? et.getText().toString().trim() : "";
  }

  private void updateFieldsVisibility() {
    if (TunnelVpnService.MODE_SERVER.equals(selectedMode)) {
      if (clientFields != null)
        clientFields.setVisibility(View.GONE);
      if (serverFields != null)
        serverFields.setVisibility(View.VISIBLE);
      if (tvClientsCount != null)
        tvClientsCount.setVisibility(View.VISIBLE);
    } else {
      if (clientFields != null)
        clientFields.setVisibility(View.VISIBLE);
      if (serverFields != null)
        serverFields.setVisibility(View.GONE);
      if (tvClientsCount != null)
        tvClientsCount.setVisibility(View.GONE);
    }

    // ★ Кнопка теста сети — только для сервера и только когда VPN активен
    if (btnTestNetwork != null) {
      boolean isServer = TunnelVpnService.MODE_SERVER.equals(selectedMode);
      btnTestNetwork.setEnabled(isServer && vpnRunning);
    }
    // Скрываем старый результат, если переключили режим
    if (tvTestNetworkResult != null && !TunnelVpnService.MODE_SERVER.equals(selectedMode)) {
      tvTestNetworkResult.setVisibility(TextView.GONE);
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    log.log("══ onResume START ══");

    try {
      if (!receiverRegistered) {
        IntentFilter filter = new IntentFilter(ACTION_VPN_STATUS);
        BroadcastUtils.registerNotExported(this, statusReceiver, filter);
        receiverRegistered = true;
        log.log("statusReceiver зарегистрирован");
      }
      refreshVpnState();
      log.log("══ onResume END ══");
    } catch (Exception e) {
      logError("Ошибка в onResume", e);
    }
  }

  @Override
  protected void onPause() {
    log.log("onPause START");
    try {
      if (receiverRegistered) {
        BroadcastUtils.unregisterSafely(this, statusReceiver);
        receiverRegistered = false;
      }
    } catch (Exception e) {
      logError("Ошибка в onPause", e);
    }
    log.log("onPause END");
    super.onPause();
  }

  private boolean isVpnServiceRunning() {
    try {
      ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
      if (manager == null)
        return false;
      for (ActivityManager.RunningServiceInfo info : manager
          .getRunningServices(Integer.MAX_VALUE)) {
        if (TunnelVpnService.class.getName().equals(info.service.getClassName())) {
          return true;
        }
      }
    } catch (Exception e) {
      logError("isVpnServiceRunning", e);
    }
    return false;
  }

  private void refreshVpnState() {
    boolean running = isVpnServiceRunning();
    log.log("refreshVpnState: TunnelVpnService running=" + running);

    if (!running) {
      updateStatus(false, "VPN выключен");
      updateCurrentMode(null);
      updateClientsCount(null);
      return;
    }

    String savedMode = TunnelVpnService.MODE_CLIENT;
    try {
      savedMode = getSharedPreferences(PREF_NAME, MODE_PRIVATE).getString(KEY_MODE,
          TunnelVpnService.MODE_CLIENT);
    } catch (Exception e) {
      logError("refreshVpnState: prefs", e);
    }
    log.log("refreshVpnState: mode из prefs=" + savedMode);

    selectedMode = savedMode;

    rbModeClient.setChecked(TunnelVpnService.MODE_CLIENT.equals(savedMode));
    rbModeServer.setChecked(TunnelVpnService.MODE_SERVER.equals(savedMode));

    updateFieldsVisibility();

    String modeLabel = TunnelVpnService.MODE_SERVER.equals(savedMode) ? "сервер" : "клиент";
    updateStatus(true, "VPN активен (" + modeLabel + ")");
    updateCurrentMode(savedMode);

    queryVpnStatus();
  }

  private void queryVpnStatus() {
    try {
      if (!isVpnServiceRunning()) {
        log.log("queryVpnStatus: сервис не запущен — не отправляем запрос");
        return;
      }
      Intent intent = new Intent(this, TunnelVpnService.class);
      intent.setAction(TunnelVpnService.ACTION_QUERY_STATUS);
      startService(intent);
    } catch (Exception e) {
      logError("queryVpnStatus", e);
    }
  }

  private void onStartClicked() {
    log.log("onStartClicked START: selectedMode=" + selectedMode);

    try {
      String ip;
      int port;
      int listenPort;

      boolean transportV4 = cbChecked(cbTransportIpv4, true);
      boolean transportV6 = cbChecked(cbTransportIpv6, true);

      if (TunnelVpnService.MODE_SERVER.equals(selectedMode)) {
        String listenStr = editListenPort != null ? editListenPort.getText().toString().trim()
            : String.valueOf(DEFAULT_LISTEN_PORT);

        try {
          listenPort = TextUtils.isEmpty(listenStr) ? DEFAULT_LISTEN_PORT
              : Integer.parseInt(listenStr);
          if (listenPort < 1024 || listenPort > 65535) {
            throw new NumberFormatException();
          }
        } catch (NumberFormatException e) {
          Toast.makeText(this, "Неверный порт сервера (1024-65535)", Toast.LENGTH_SHORT).show();
          return;
        }

        ip = null;
        port = 0;

        savePrefs(TunnelVpnService.MODE_SERVER, "", 0, listenPort, textOf(editGatewayIpV6),
            cbChecked(cbListenIpv6, true), textOf(editTunIpv6), transportV4, transportV6,
            currentPriority());

        pendingMode = TunnelVpnService.MODE_SERVER;
        pendingIp = null;
        pendingPort = 0;
        pendingListenPort = listenPort;

      } else {
        ip = editIp != null ? editIp.getText().toString().trim() : "";
        String portStr = editPort != null ? editPort.getText().toString().trim() : "";

        if (TextUtils.isEmpty(ip)) {
          Toast.makeText(this, "Укажите IP шлюза", Toast.LENGTH_SHORT).show();
          return;
        }

        try {
          port = TextUtils.isEmpty(portStr) ? DEFAULT_GATEWAY_PORT : Integer.parseInt(portStr);
          if (port < 1024 || port > 65535)
            throw new NumberFormatException();
        } catch (NumberFormatException e) {
          Toast.makeText(this, "Неверный порт (1024-65535)", Toast.LENGTH_SHORT).show();
          return;
        }

        savePrefs(TunnelVpnService.MODE_CLIENT, ip, port, DEFAULT_LISTEN_PORT,
            textOf(editGatewayIpV6), cbChecked(cbListenIpv6, true), textOf(editTunIpv6),
            transportV4, transportV6, currentPriority());

        pendingMode = TunnelVpnService.MODE_CLIENT;
        pendingIp = ip;
        pendingPort = port;
        pendingListenPort = 0;
      }

      Intent prepareIntent = VpnService.prepare(this);
      if (prepareIntent != null) {
        startActivityForResult(prepareIntent, REQ_VPN_PREPARE);
      } else {
        actuallyStartVpn(pendingMode, pendingIp, pendingPort, pendingListenPort);
      }

    } catch (Exception e) {
      logError("Ошибка onStartClicked", e);
      Toast.makeText(this, "Ошибка запуска: " + e.getMessage(), Toast.LENGTH_LONG).show();
    }
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    log.log("onActivityResult: requestCode=" + requestCode + ", resultCode=" + resultCode);

    try {
      if (requestCode == REQ_VPN_PREPARE) {
        if (resultCode == RESULT_OK) {
          if (pendingMode == null)
            pendingMode = selectedMode;
          actuallyStartVpn(pendingMode, pendingIp, pendingPort, pendingListenPort);
        } else {
          Toast.makeText(this, "VPN отклонён пользователем", Toast.LENGTH_SHORT).show();
          updateStatus(false, "VPN выключен");
        }
      }
    } catch (Exception e) {
      logError("Ошибка onActivityResult", e);
    }
  }

  private void actuallyStartVpn(String mode, String ip, int port, int listenPort) {
    try {
      Intent intent = new Intent(this, TunnelVpnService.class);
      intent.setAction(TunnelVpnService.ACTION_START);
      intent.putExtra(TunnelVpnService.EXTRA_MODE, mode);

      boolean transportV4 = cbTransportIpv4 != null && cbTransportIpv4.isChecked();
      boolean transportV6 = cbTransportIpv6 != null && cbTransportIpv6.isChecked();
      String priority = currentPriority();

      intent.putExtra(TunnelVpnService.EXTRA_TRANSPORT_IPV4, transportV4);
      intent.putExtra(TunnelVpnService.EXTRA_TRANSPORT_IPV6, transportV6);
      intent.putExtra(TunnelVpnService.EXTRA_GATEWAY_PRIORITY, priority);

      log.log("actuallyStartVpn: mode=" + mode + ", transport v4=" + transportV4 + " v6="
          + transportV6 + ", priority=" + priority);

      if (TunnelVpnService.MODE_SERVER.equals(mode)) {
        intent.putExtra(TunnelVpnService.EXTRA_LISTEN_PORT, listenPort);

        String tunIpv6 = editTunIpv6 != null ? editTunIpv6.getText().toString().trim() : "";
        if (tunIpv6.isEmpty()) {
          tunIpv6 = "fd00::1";
        }
        boolean listenIpv6 = cbListenIpv6 == null || cbListenIpv6.isChecked();

        intent.putExtra(TunnelVpnService.EXTRA_TUN_IPV6, tunIpv6);
        intent.putExtra(TunnelVpnService.EXTRA_LISTEN_IPV6, listenIpv6);

        log.log("actuallyStartVpn: server → listenPort=" + listenPort + ", tunIpv6=" + tunIpv6
            + ", listenIpv6=" + listenIpv6);

      } else {
        intent.putExtra(TunnelVpnService.EXTRA_SERVER_IP, ip);
        intent.putExtra(TunnelVpnService.EXTRA_SERVER_PORT, port);

        String gatewayIpV6 = editGatewayIpV6 != null ? editGatewayIpV6.getText().toString().trim()
            : "";
        if (!gatewayIpV6.isEmpty()) {
          intent.putExtra(TunnelVpnService.EXTRA_SERVER_IP_V6, gatewayIpV6);
        }

        log.log("actuallyStartVpn: client → serverIp=" + ip + ":" + port + ", serverIpV6="
            + (gatewayIpV6.isEmpty() ? "(нет)" : gatewayIpV6));
      }

      startForegroundService(intent);
      updateStatus(true, "VPN запускается...");

    } catch (Exception e) {
      logError("Ошибка actuallyStartVpn", e);
      Toast.makeText(this, "Ошибка запуска VPN: " + e.getMessage(), Toast.LENGTH_LONG).show();
    }
  }

  private void onStopClicked() {
    log.log("onStopClicked START");

    try {
      Intent intent = new Intent(this, TunnelVpnService.class);
      intent.setAction(TunnelVpnService.ACTION_STOP);
      startForegroundService(intent);

      updateStatus(false, "VPN останавливается...");

    } catch (Exception e) {
      logError("Ошибка onStopClicked", e);
      Toast.makeText(this, "Ошибка остановки VPN: " + e.getMessage(), Toast.LENGTH_LONG).show();
    }
  }

  private void updateStatus(boolean running, String message) {
    try {
      if (tvStatus == null)
        return;
      vpnRunning = running;
      if (message == null)
        message = running ? "VPN активен" : "VPN выключен";

      tvStatus.setText(message);
      tvStatus.setTextColor(running ? 0xFF4CAF50 : 0xFFE53E3E);

      if (btnStart != null)
        btnStart.setEnabled(!running);
      if (btnStop != null)
        btnStop.setEnabled(running);

      if (rbModeClient != null)
        rbModeClient.setEnabled(!running);
      if (rbModeServer != null)
        rbModeServer.setEnabled(!running);

      // ★ Обновляем доступность кнопки «Тест сети»
      if (btnTestNetwork != null) {
        boolean isServer = TunnelVpnService.MODE_SERVER.equals(selectedMode);
        btnTestNetwork.setEnabled(isServer && running);
      }
    } catch (Exception e) {
      logError("Ошибка updateStatus", e);
    }
  }

  private void updateCurrentMode(String mode) {
    try {
      if (tvCurrentMode == null)
        return;
      if (mode == null) {
        tvCurrentMode.setText("Режим: —");
        return;
      }
      if (TunnelVpnService.MODE_SERVER.equals(mode)) {
        tvCurrentMode.setText("Режим: сервер (шлюз)");
      } else if (TunnelVpnService.MODE_CLIENT.equals(mode)) {
        tvCurrentMode.setText("Режим: клиент");
      } else {
        tvCurrentMode.setText("Режим: " + mode);
      }
    } catch (Exception e) {
      logError("Ошибка updateCurrentMode", e);
    }
  }

  private void updateClientsCount(String message) {
    try {
      if (tvClientsCount == null)
        return;
      if (message == null || !message.contains("Клиентов подключено")) {
        tvClientsCount.setText("");
        return;
      }
      String digits = message.replaceAll("[^0-9]", "");
      tvClientsCount.setText(digits.isEmpty() ? "" : "Клиентов: " + digits);
    } catch (Exception e) {
      logError("Ошибка updateClientsCount", e);
    }
  }

  private void logError(String message, Throwable e) {
    if (log != null)
      log.logError(message, e);
    else
      Log.e(TAG, message, e);
  }
}