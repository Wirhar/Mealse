package com.my.mealse.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.util.Log;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Утилиты для получения информации об устройстве:
 *  - локальные IPv4 / IPv6 адреса
 *  - device id (ANDROID_ID)
 *  - тип активной сети (Wi-Fi / cell / ethernet / vpn)
 *  - грубая проверка на CGNAT
 *  - сводка для UI («Показать данные устройства»)
 *
 * Логирование — через MealseLogger, во всех публичных методах.
 * Модели (TrustedNode и т.п.) логирование не используют — это сделано
 * на уровне вызывающих Activity/менеджеров.
 */
public class DeviceInfoHelper {

  private static final String TAG = "DeviceInfoHelper";

  // ============================================================
  // IPv4
  // ============================================================

  /**
   * Возвращает локальный IPv4-адрес устройства (Wi-Fi или через NetworkInterface).
   * Алиас для {@link #getLocalIpAddress(Context)} — используется в CallActivity
   * для проверки self-call.
   */
  public static String getSelfIp(Context context) {
    return getLocalIpAddress(context);
  }

  /**
   * Определяет локальный IPv4-адрес устройства:
   *  1) через WifiManager (быстро, но работает только на Wi-Fi);
   *  2) через NetworkInterface (fallback для Ethernet / мобильной сети).
   * Возвращает "127.0.0.1" в крайнем случае.
   *
   * ★ ИЗМЕНЕНО: возвращает только IPv4. Для IPv6 — отдельный метод.
   */
  public static String getLocalIpAddress(Context context) {
    MealseLogger log = MealseLogger.get(context, TAG);
    log.log("══ getLocalIpAddress START ══");

    // 1. Wi-Fi
    try {
      WifiManager wifiManager = (WifiManager)
          context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
      if (wifiManager != null) {
        int ipInt = wifiManager.getConnectionInfo().getIpAddress();
        log.log("WifiManager.ipAddress(int)=" + ipInt);
        if (ipInt != 0) {
          String ip = ((ipInt & 0xff) + "." +
              ((ipInt >> 8) & 0xff) + "." +
              ((ipInt >> 16) & 0xff) + "." +
              ((ipInt >> 24) & 0xff));
          log.log("IP из WifiManager: " + ip);
          log.log("══ getLocalIpAddress END ══");
          return ip;
        } else {
          log.log("WifiManager вернул 0 — Wi-Fi не подключён, пробуем NetworkInterface");
        }
      } else {
        log.warn("WifiManager == null");
      }
    } catch (Exception e) {
      log.logError("Ошибка получения IP через WifiManager", e);
    }

    // 2. NetworkInterface — IPv4
    try {
      Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
      if (interfaces == null) {
        log.warn("NetworkInterface.getNetworkInterfaces() вернул null");
      } else {
        while (interfaces.hasMoreElements()) {
          NetworkInterface nif = interfaces.nextElement();
          boolean skip = nif.isLoopback() || nif.isPointToPoint();
          log.log("NetworkInterface: name=" + nif.getName()
              + ", isLoopback=" + nif.isLoopback()
              + ", isPointToPoint=" + nif.isPointToPoint()
              + ", isUp=" + nif.isUp()
              + (skip ? " → пропускаем" : ""));

          if (skip) continue;

          Enumeration<InetAddress> addresses = nif.getInetAddresses();
          while (addresses.hasMoreElements()) {
            InetAddress address = addresses.nextElement();
            boolean isIpv4 = address instanceof Inet4Address;
            String hostAddress = address.getHostAddress();
            log.log("  address: " + hostAddress
                + ", isLoopback=" + address.isLoopbackAddress()
                + ", isIPv4=" + isIpv4);

            if (!address.isLoopbackAddress() && isIpv4) {
              log.log("IP из NetworkInterface: " + hostAddress
                  + " (интерфейс " + nif.getName() + ")");
              log.log("══ getLocalIpAddress END ══");
              return hostAddress;
            }
          }
        }
      }
    } catch (SocketException e) {
      log.logError("Ошибка перебора NetworkInterface", e);
    } catch (Exception e) {
      log.logError("Неожиданная ошибка при получении IP", e);
    }

    log.warn("IPv4 не удалось определить — возвращаю 127.0.0.1");
    log.log("══ getLocalIpAddress END ══");
    return "127.0.0.1";
  }

  // ============================================================
  // ★ НОВОЕ: IPv6
  // ============================================================

  /**
   * Возвращает глобальный IPv6-адрес устройства (не loopback, не link-local).
   *
   * Link-local (fe80::/10) НЕ подходит для P2P — он работает только в одной
   * локальной сети, не роутится и не даёт глобальной достижимости.
   *
   * Возвращает null, если глобального IPv6 нет.
   */
  public static String getIpv6Address(Context context) {
    MealseLogger log = MealseLogger.get(context, TAG);
    log.log("══ getIpv6Address START ══");

    List<String> all = getAllIpv6Addresses(context);
    if (all.isEmpty()) {
      log.log("IPv6 не найден");
      log.log("══ getIpv6Address END ══");
      return null;
    }

    // Первый из всех — обычно предпочтительный (например, 2a00:... или 2001:...)
    String ipv6 = all.get(0);
    log.log("Глобальный IPv6: " + ipv6);
    log.log("══ getIpv6Address END ══");
    return ipv6;
  }

  /**
   * Возвращает список всех глобальных IPv6-адресов устройства.
   * Исключает:
   *  - loopback (::1)
   *  - link-local (fe80::/10)
   *  - multicast (ff00::/8)
   *  - unique local (fc00::/7 — это как IPv4-приватные, но не для P2P)
   *
   * Используется для диагностики и для метода getIpv6Address().
   */
  public static List<String> getAllIpv6Addresses(Context context) {
    MealseLogger log = MealseLogger.get(context, TAG);
    List<String> result = new ArrayList<>();

    try {
      Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
      if (interfaces == null) {
        log.warn("getAllIpv6Addresses: NetworkInterface.getNetworkInterfaces() == null");
        return result;
      }

      while (interfaces.hasMoreElements()) {
        NetworkInterface nif = interfaces.nextElement();
        if (nif.isLoopback()) continue;

        Enumeration<InetAddress> addresses = nif.getInetAddresses();
        while (addresses.hasMoreElements()) {
          InetAddress address = addresses.nextElement();
          if (!(address instanceof Inet6Address)) continue;
          if (address.isLoopbackAddress()) continue;

          String host = address.getHostAddress();
          if (host == null) continue;

          // Убираем зону (например, "fe80::1%wlan0" → "fe80::1")
          int zoneIdx = host.indexOf('%');
          String cleanHost = zoneIdx > 0 ? host.substring(0, zoneIdx) : host;
          String lower = cleanHost.toLowerCase();

          // Фильтры нежелательных IPv6
          if (lower.startsWith("fe80")) {
            log.log("  skip link-local: " + cleanHost);
            continue;
          }
          if (lower.startsWith("fc") || lower.startsWith("fd")) {
            // fc00::/7 — unique local. Не глобальный.
            log.log("  skip unique-local: " + cleanHost);
            continue;
          }
          if (lower.startsWith("ff")) {
            log.log("  skip multicast: " + cleanHost);
            continue;
          }

          log.log("  found IPv6: " + cleanHost + " (интерфейс " + nif.getName() + ")");
          result.add(cleanHost);
        }
      }
    } catch (SocketException e) {
      log.logError("getAllIpv6Addresses: ошибка перебора NetworkInterface", e);
    } catch (Exception e) {
      log.logError("getAllIpv6Addresses: неожиданная ошибка", e);
    }

    log.log("getAllIpv6Addresses: итого " + result.size() + " адрес(ов)");
    return result;
  }

  // ============================================================
  // ★ НОВОЕ: тип сети
  // ============================================================

  /**
   * Возвращает тип активной сети:
   *  - "wifi"
   *  - "cell"     (мобильный интернет)
   *  - "ethernet"
   *  - "vpn"      (активен VPN-интерфейс)
   *  - "none"     (нет подключения)
   *  - "unknown"
   *
   * Использует ConnectivityManager (современный API).
   * На старых API (< 23) — NetworkInfo как fallback.
   */
  public static String getConnectionType(Context context) {
    MealseLogger log = MealseLogger.get(context, TAG);
    log.log("══ getConnectionType START ══");

    try {
      ConnectivityManager cm = (ConnectivityManager)
          context.getSystemService(Context.CONNECTIVITY_SERVICE);
      if (cm == null) {
        log.warn("ConnectivityManager == null");
        return "unknown";
      }

      Network active = cm.getActiveNetwork();
      if (active == null) {
        log.log("Активной сети нет → none");
        return "none";
      }

      NetworkCapabilities caps = cm.getNetworkCapabilities(active);
      if (caps == null) {
        log.warn("NetworkCapabilities == null → unknown");
        return "unknown";
      }

      String type;
      if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
        type = "vpn";
      } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
        type = "wifi";
      } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
        type = "cell";
      } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
        type = "ethernet";
      } else {
        type = "unknown";
      }

      log.log("Connection type: " + type);
      log.log("══ getConnectionType END ══");
      return type;

    } catch (Exception e) {
      log.logError("getConnectionType: ошибка", e);
      return "unknown";
    }
  }

  /**
   * Активен ли VPN-интерфейс у устройства.
   * Проверяется через ConnectivityManager: есть ли сеть с TRANSPORT_VPN.
   */
  public static boolean isVpnActive(Context context) {
    MealseLogger log = MealseLogger.get(context, TAG);
    try {
      ConnectivityManager cm = (ConnectivityManager)
          context.getSystemService(Context.CONNECTIVITY_SERVICE);
      if (cm == null) return false;

      Network[] networks = cm.getAllNetworks();
      if (networks == null) return false;

      for (Network n : networks) {
        NetworkCapabilities caps = cm.getNetworkCapabilities(n);
        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
          log.log("VPN активен (network=" + n + ")");
          return true;
        }
      }
      log.log("VPN не активен");
      return false;
    } catch (Exception e) {
      log.logError("isVpnActive: ошибка", e);
      return false;
    }
  }

  // ============================================================
  // ★ НОВОЕ: грубая эвристика CGNAT
  // ============================================================

  /**
   * Грубая эвристика: устройство, вероятно, за CGNAT.
   *
   * Проверяет, попадает ли локальный IPv4 в диапазон 100.64.0.0/10
   * (RFC 6598, «shared address space», используемый CGNAT операторов).
   *
   * ⚠️ Это НЕ 100% гарантия. Оператор может использовать и другие
   * приватные диапазоны, или давать «серый» IP через NAT провайдера.
   * Но если IP в 100.64/10 — это почти наверняка CGNAT.
   *
   * Возвращает:
   *  - true, если IPv4 в 100.64.0.0/10
   *  - false в остальных случаях (не значит «нет CGNAT»)
   */
  public static boolean isCgnat(Context context) {
    MealseLogger log = MealseLogger.get(context, TAG);
    try {
      String ip = getLocalIpAddress(context);
      if (ip == null || ip.isEmpty()) {
        log.log("isCgnat: не удалось получить IPv4");
        return false;
      }

      String[] parts = ip.split("\\.");
      if (parts.length != 4) return false;

      int a = Integer.parseInt(parts[0]);
      int b = Integer.parseInt(parts[1]);

      // 100.64.0.0/10 → первые два октета в диапазоне 100.64 - 100.127
      if (a == 100 && b >= 64 && b <= 127) {
        log.log("isCgnat: IPv4 " + ip + " попадает в 100.64.0.0/10 → CGNAT");
        return true;
      }
      log.log("isCgnat: IPv4 " + ip + " не в CGNAT-диапазоне");
      return false;

    } catch (Exception e) {
      log.logError("isCgnat: ошибка", e);
      return false;
    }
  }

  /**
   * ★ НОВОЕ: очень грубая эвристика «есть ли публичный IPv4».
   *
   * Проверяет, что IPv4 не в приватных диапазонах (10/8, 172.16/12,
   * 192.168/16) и не в CGNAT (100.64/10) и не в loopback (127/8).
   *
   * ⚠️ НЕ используем как «истину». Устройство может быть за NAT
   * домашнего роутера, где локальный IP приватный, но публичный IP
   * у роутера — есть. Этот метод только для UI-подсказки.
   */
  public static boolean hasPublicIpv4(Context context) {
    MealseLogger log = MealseLogger.get(context, TAG);
    try {
      String ip = getLocalIpAddress(context);
      if (ip == null || ip.isEmpty()) return false;

      String[] parts = ip.split("\\.");
      if (parts.length != 4) return false;

      int a = Integer.parseInt(parts[0]);
      int b = Integer.parseInt(parts[1]);

      // Приватные и специальные диапазоны
      boolean isPrivate =
          (a == 10)
          || (a == 172 && b >= 16 && b <= 31)
          || (a == 192 && b == 168)
          || (a == 127)
          || (a == 100 && b >= 64 && b <= 127)  // CGNAT
          || (a == 169 && b == 254);            // link-local

      boolean isPublic = !isPrivate;
      log.log("hasPublicIpv4: " + ip + " → isPublic=" + isPublic);
      return isPublic;

    } catch (Exception e) {
      log.logError("hasPublicIpv4: ошибка", e);
      return false;
    }
  }

  // ============================================================
  // ★ НОВОЕ: сводка для UI
  // ============================================================

  /**
   * Формирует многострочную сводку о сетевом состоянии устройства
   * для отображения в SettingsActivity («Показать данные устройства»).
   */
  public static String getNetworkInfoSummary(Context context) {
    MealseLogger log = MealseLogger.get(context, TAG);
    log.log("══ getNetworkInfoSummary START ══");

    StringBuilder sb = new StringBuilder();

    try {
      // IPv4
      String ipv4 = getLocalIpAddress(context);
      sb.append("IPv4: ").append(ipv4 != null ? ipv4 : "—").append("\n");

      // IPv6
      String ipv6 = getIpv6Address(context);
      if (ipv6 != null) {
        sb.append("IPv6: ").append(ipv6).append("\n");
      } else {
        sb.append("IPv6: отсутствует\n");
      }

      // Тип сети
      String connType = getConnectionType(context);
      sb.append("Тип сети: ").append(connType).append("\n");

      // VPN
      sb.append("VPN: ").append(isVpnActive(context) ? "активен" : "не активен").append("\n");

      // CGNAT
      boolean cgnat = isCgnat(context);
      sb.append("CGNAT: ").append(cgnat ? "вероятно (100.64/10)" : "не обнаружен").append("\n");

      // Публичный IPv4
      boolean pub = hasPublicIpv4(context);
      sb.append("Публичный IPv4: ").append(pub ? "возможно" : "нет").append("\n");

      // P2P через IPv6
      sb.append("P2P через IPv6: ")
          .append(ipv6 != null ? "доступен" : "недоступен")
          .append("\n");

    } catch (Exception e) {
      log.logError("getNetworkInfoSummary: ошибка", e);
      sb.append("\nОшибка получения сводки: ").append(e.getMessage());
    }

    log.log("getNetworkInfoSummary:\n" + sb);
    log.log("══ getNetworkInfoSummary END ══");
    return sb.toString();
  }

  // ============================================================
  // Device ID
  // ============================================================

  public static String getDeviceId(Context context) {
    MealseLogger log = MealseLogger.get(context, TAG);
    try {
      String androidId = Settings.Secure.getString(
          context.getContentResolver(), Settings.Secure.ANDROID_ID);
      if (androidId != null && !androidId.isEmpty()) {
        log.log("getDeviceId: ANDROID_ID=" + androidId);
        return androidId;
      }
      log.warn("getDeviceId: ANDROID_ID=null или пустой");
    } catch (Exception e) {
      log.logError("getDeviceId: ошибка чтения ANDROID_ID", e);
    }
    log.warn("getDeviceId: возвращаю 'unknown_device'");
    return "unknown_device";
  }
}