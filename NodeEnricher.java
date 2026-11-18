package com.my.mealse.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.my.mealse.model.TrustedNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ★ НОВОЕ: «самообучение» узлов.
 *
 * При любом успешном TCP-контакте с узлом (по IPv4/IPv6/HTTP/WD) запрашивает у него GET /ping и обогащает запись TrustedNode недостающими данными: - device_id (если у нас пусто) - fingerprint (если у нас пусто) - ipv6Address (если сервер отдаёт свой IPv6 — добавим в /ping) - lastSeenTimestamp - lastSeenNetwork
 *
 * Использование: NodeEnricher.enrich(this, node); // асинхронно, ничего не блокирует
 *
 * Потокобезопасен, дедуплицирует одновременные запросы к одному узлу.
 */
public final class NodeEnricher {

  private static final String TAG = "NodeEnricher";
  private static final int TIMEOUT_MS = 3000;

  private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4);
  private static final Handler MAIN = new Handler(Looper.getMainLooper());

  /** Ключи "ip:port", по которым уже идёт обогащение — чтобы не дублировать. */
  private static final java.util.Set<String> IN_FLIGHT = java.util.Collections
      .newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

  private NodeEnricher() {
  }

  /**
   * Асинхронно обогащает узел данными из его /ping. Никаких исключений не бросает, ошибки тихо логируются.
   */
  public static void enrich(Context context, TrustedNode node) {
    if (context == null || node == null)
      return;
    if (node.getIpAddress() == null || node.getIpAddress().isEmpty())
      return;

    final String key = node.getKey();
    if (!IN_FLIGHT.add(key)) {
      // уже идёт — пропускаем
      return;
    }

    final Context appCtx = context.getApplicationContext();
    final String ip = node.getIpAddress();
    final int port = node.getPort();
    final String oldDeviceId = node.getDeviceId();
    final String oldFingerprint = node.getFingerprint();

    EXECUTOR.execute(() -> {
      MealseLogger log = MealseLogger.get(appCtx, TAG);
      try {
        log.log("enrich START: " + key);

        String urlStr = buildPingUrl(ip, port);
        JsonObject resp = httpGetJson(urlStr);
        if (resp == null) {
          log.log("enrich: /ping вернул null для " + urlStr);
          return;
        }

        String deviceId = optString(resp, "device_id");
        String fingerprint = optString(resp, "fingerprint");
        String ipv6 = optString(resp, "ipv6"); // см. правку /ping ниже
        String networkType = optString(resp, "network_type"); // опционально

        // Проверяем, есть ли что новое
        boolean hasNewDeviceId = isNotEmpty(deviceId) && !deviceId.equals(oldDeviceId);
        boolean hasNewFingerprint = isNotEmpty(fingerprint) && !fingerprint.equals(oldFingerprint);
        boolean hasNewIpv6 = isNotEmpty(ipv6) && !ipv6.equals(node.getIpv6Address());
        boolean hasNetwork = isNotEmpty(networkType);

        if (!hasNewDeviceId && !hasNewFingerprint && !hasNewIpv6 && !hasNetwork) {
          log.log("enrich: нет новых данных для " + key);
          return;
        }

        // Читаем свежий узел из хранилища (мог измениться)
        NodeManager nm = new NodeManager(appCtx);
        TrustedNode fresh = nm.findByIpPort(ip, port);
        if (fresh == null) {
          log.warn("enrich: узел " + key + " исчез из NodeManager");
          return;
        }

        if (hasNewDeviceId) {
          log.log("enrich: deviceId " + fresh.getDeviceId() + " → " + deviceId);
          fresh.setDeviceId(deviceId);
        }
        if (hasNewFingerprint) {
          if (isNotEmpty(oldFingerprint) && !oldFingerprint.equals(fingerprint)) {
            // ★ КРИТИЧНО: fingerprint изменился — это может быть MITM
            log.warn("enrich: FINGERPRINT ИЗМЕНИЛСЯ у " + key + " старый=" + oldFingerprint
                + " новый=" + fingerprint + " — не перезаписываю (возможен MITM)");
          } else {
            log.log("enrich: fingerprint " + fresh.getFingerprint() + " → " + fingerprint);
            fresh.setFingerprint(fingerprint);
          }
        }
        if (hasNewIpv6) {
          log.log("enrich: ipv6Address " + fresh.getIpv6Address() + " → " + ipv6);
          fresh.setIpv6Address(ipv6);
        }

        fresh.touchLastSeen(hasNetwork ? networkType : fresh.getLastSeenNetwork());

        boolean saved = nm.saveNode(fresh);
        log.log("enrich: saveNode=" + saved + " для " + key);

      } catch (Exception e) {
        log.logError("enrich: ошибка для " + key, e);
      } finally {
        IN_FLIGHT.remove(key);
      }
    });
  }

  // ============================================================
  // Вспомогательные
  // ============================================================

  private static String buildPingUrl(String ip, int port) {
    String host = ip;
    // Если это IPv6-литерал (содержит ':'), оборачиваем в []
    if (ip.contains(":") && !ip.startsWith("[")) {
      host = "[" + ip + "]";
    }
    return "http://" + host + ":" + port + "/ping";
  }

  private static JsonObject httpGetJson(String urlStr) {
    HttpURLConnection conn = null;
    try {
      URL url = new URL(urlStr);
      conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(TIMEOUT_MS);
      conn.setReadTimeout(TIMEOUT_MS);
      conn.setRequestProperty("Accept", "application/json");

      int code = conn.getResponseCode();
      if (code != 200)
        return null;

      try (BufferedReader r = new BufferedReader(
          new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null)
          sb.append(line);
        return JsonParser.parseString(sb.toString()).getAsJsonObject();
      }
    } catch (Exception e) {
      return null;
    } finally {
      if (conn != null) {
        try {
          conn.disconnect();
        } catch (Exception ignored) {
        }
      }
    }
  }

  private static String optString(JsonObject o, String key) {
    try {
      if (o == null || !o.has(key) || o.get(key).isJsonNull())
        return null;
      return o.get(key).getAsString();
    } catch (Exception e) {
      return null;
    }
  }

  private static boolean isNotEmpty(String s) {
    return s != null && !s.isEmpty();
  }

  /**
   * ★ Обогащение узла данными из Wi-Fi Direct handshake.
   *
   * Узел ищется по (mac, 0) — как это делает WifiDirectActivity.saveWifiDirectNode. Данные берутся из remotePeer (deviceId, fingerprint, deviceName).
   */
  public static void enrichFromWifiDirect(Context context, WifiDirectManager wdm) {
    if (context == null || wdm == null)
      return;

    try {
      WifiDirectManager.Peer remote = wdm.getRemotePeer();
      if (remote == null)
        return;

      String mac = remote.deviceAddress;
      if (mac == null || mac.isEmpty())
        return;

      String deviceId = remote.deviceId;
      String fingerprint = remote.fingerprint;
      // ★ НОВОЕ: IPv6 пира
      String ipv6 = remote.ipv6;

      // Если вообще нечего обогащать — выходим
      if ((deviceId == null || deviceId.isEmpty()) && (fingerprint == null || fingerprint.isEmpty())
          && (ipv6 == null || ipv6.isEmpty())) {
        return;
      }

      final Context appCtx = context.getApplicationContext();
      final String key = mac + ":0";

      if (!IN_FLIGHT.add("wd:" + key)) {
        return;
      }

      EXECUTOR.execute(() -> {
        MealseLogger log = MealseLogger.get(appCtx, TAG);
        try {
          log.log("enrichFromWifiDirect START: " + key);

          NodeManager nm = new NodeManager(appCtx);
          TrustedNode fresh = nm.findByIpPort(mac, 0);
          if (fresh == null) {
            log.log("enrichFromWifiDirect: узел " + key + " не найден");
            return;
          }

          String oldDeviceId = fresh.getDeviceId();
          String oldFp = fresh.getFingerprint();
          String oldV6 = fresh.getIpv6Address();

          boolean hasNewDeviceId = deviceId != null && !deviceId.isEmpty()
              && !deviceId.equals(oldDeviceId);
          boolean hasNewFp = fingerprint != null && !fingerprint.isEmpty()
              && !fingerprint.equals(oldFp);
          boolean hasNewV6 = ipv6 != null && !ipv6.isEmpty() && !ipv6.equals(oldV6);

          boolean fingerprintConflict = false;
          if (hasNewFp && oldFp != null && !oldFp.isEmpty()) {
            fingerprintConflict = true;
            log.warn("enrichFromWifiDirect: FINGERPRINT ИЗМЕНИЛСЯ у " + key + " старый=" + oldFp
                + " новый=" + fingerprint + " — НЕ перезаписываю");
          }

          if (hasNewDeviceId) {
            log.log("enrichFromWifiDirect: deviceId " + oldDeviceId + " → " + deviceId);
            fresh.setDeviceId(deviceId);
          }
          if (hasNewFp && !fingerprintConflict) {
            log.log("enrichFromWifiDirect: fingerprint " + oldFp + " → " + fingerprint);
            fresh.setFingerprint(fingerprint);
          }
          if (hasNewV6) {
            log.log("enrichFromWifiDirect: ipv6Address " + oldV6 + " → " + ipv6);
            fresh.setIpv6Address(ipv6);
          }

          if (hasNewDeviceId || (hasNewFp && !fingerprintConflict) || hasNewV6) {
            fresh.touchLastSeen(fresh.getLastSeenNetwork());
            boolean saved = nm.saveNode(fresh);
            log.log("enrichFromWifiDirect: saveNode=" + saved + " для " + key);
          } else {
            log.log("enrichFromWifiDirect: нет новых данных для " + key);
          }

        } catch (Exception e) {
          log.logError("enrichFromWifiDirect: ошибка для " + key, e);
        } finally {
          IN_FLIGHT.remove("wd:" + key);
        }
      });
    } catch (Exception e) {
      // тихо
    }
  }

}