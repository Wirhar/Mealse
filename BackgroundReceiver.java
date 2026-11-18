package com.my.mealse.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;

import com.my.mealse.filemanager.server.RemoteFsService;
import com.my.mealse.service.ServerService;
import com.my.mealse.service.TunnelVpnService;
import com.my.mealse.util.MealseLogger;

public class BackgroundReceiver extends BroadcastReceiver {

  private static final String TAG = "BackgroundReceiver";

  private static final String PREF_TUNNEL = "mealse_tunnel_prefs";
  private static final String KEY_TUNNEL_MODE = "mode";
  private static final String KEY_TUNNEL_LISTEN_PORT = "listen_port";
  private static final String MODE_SERVER = "server";
  private static final int DEFAULT_LISTEN_PORT = 8083;

  @Override
  public void onReceive(Context context, Intent intent) {
    MealseLogger log = MealseLogger.get(context, TAG);

    String action = intent != null ? intent.getAction() : null;
    log.log("══ onReceive START ══");
    log.log("Action: " + action);

    try {
      if (Intent.ACTION_BOOT_COMPLETED.equals(action)
          || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
          || "android.net.wifi.WIFI_STATE_CHANGED".equals(action)) {

        // ========== 1. WiFi IP ==========
        String ipAddress = getWifiIpAddress(context, log);
        String deviceId = getDeviceId(context, log);
        log.log("ipAddress=" + ipAddress + ", deviceId=" + deviceId);

        // ========== 2. ServerService ==========
        startServerService(context, log, ipAddress, deviceId);

        // ========== 3. RemoteFsService ==========
        // Запускаем только при boot / package replaced,
        // при WIFI_STATE_CHANGED — не трогаем (иначе дёргается при каждом изменении).
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
            || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
          startRemoteFsService(context, log);
        }

        // ========== 4. VPN-сервер (только при boot / update) ==========
        boolean isBootOrUpdate = Intent.ACTION_BOOT_COMPLETED.equals(action)
            || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);

        if (isBootOrUpdate && isServerModeEnabled(context, log)) {
          startVpnServer(context, log);
        } else if (isBootOrUpdate) {
          log.log("VPN-сервер не запускаем: устройство не настроено как шлюз");
        } else {
          log.log("WIFI_STATE_CHANGED: VPN-сервер не трогаем");
        }

      } else {
        log.log("Action не обрабатывается: " + action);
      }
    } catch (Exception e) {
      log.logError("Критическая ошибка в BackgroundReceiver", e);
    }

    log.log("══ onReceive END ══");
  }

  // ============================================================
  // Вспомогательные методы
  // ============================================================

  private String getWifiIpAddress(Context context, MealseLogger log) {
    try {
      WifiManager wifiManager = (WifiManager) context.getApplicationContext()
          .getSystemService(Context.WIFI_SERVICE);
      if (wifiManager == null) {
        log.warn("WifiManager == null");
        return "0.0.0.0";
      }

      WifiInfo info = wifiManager.getConnectionInfo();
      if (info == null) {
        log.warn("WifiInfo == null");
        return "0.0.0.0";
      }

      int ip = info.getIpAddress();
      if (ip == 0) {
        log.warn("Wi-Fi не подключён");
        return "0.0.0.0";
      }

      String addr = ((ip & 0xff) + "." + ((ip >> 8) & 0xff) + "." + ((ip >> 16) & 0xff) + "."
          + ((ip >> 24) & 0xff));
      log.log("Wi-Fi IP: " + addr);
      return addr;
    } catch (Exception e) {
      log.logError("Ошибка получения Wi-Fi IP", e);
      return "0.0.0.0";
    }
  }

  private String getDeviceId(Context context, MealseLogger log) {
    try {
      String id = Settings.Secure.getString(context.getContentResolver(),
          Settings.Secure.ANDROID_ID);
      if (id == null || id.isEmpty()) {
        log.warn("ANDROID_ID пустой");
        return "unknown_device";
      }
      return id;
    } catch (Exception e) {
      log.logError("Ошибка получения ANDROID_ID", e);
      return "unknown_device";
    }
  }

  private void startServerService(Context context, MealseLogger log, String ipAddress,
      String deviceId) {
    try {
      Intent serverIntent = new Intent(context, ServerService.class);
      serverIntent.putExtra("local_ip", ipAddress);
      serverIntent.putExtra("device_id", deviceId);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(serverIntent);
      } else {
        context.startService(serverIntent);
      }
      log.log("✓ ServerService запущен");
    } catch (Exception e) {
      log.logError("Ошибка запуска ServerService", e);
    }
  }

  /**
   * ★ НОВОЕ (Шаг 7): запуск RemoteFsService — файлового HTTP-сервера на порту 8084, обслуживающего запросы удалённых узлов к внутренней памяти приложения.
   */
  /**
   * ★ ИЗМЕНЕНО: проверяем флаг "fs_user_enabled". Если пользователь остановил сервер вручную — не запускаем даже при boot / wifi change. Первый запуск (флаг отсутствует) — считаем "включено" (default true).
   */
  private void startRemoteFsService(Context context, MealseLogger log) {
    try {
      SharedPreferences prefs = context.getSharedPreferences("mealse_app_prefs",
          Context.MODE_PRIVATE);
      boolean userEnabled = prefs.getBoolean("fs_user_enabled", true);

      if (!userEnabled) {
        log.log("RemoteFsService: пользователь остановил вручную — не запускаем");
        return;
      }

      Intent fsIntent = new Intent(context, RemoteFsService.class);
      fsIntent.setAction(RemoteFsService.ACTION_START);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(fsIntent);
      } else {
        context.startService(fsIntent);
      }
      log.log("✓ RemoteFsService запущен");
    } catch (Exception e) {
      log.logError("Ошибка запуска RemoteFsService", e);
    }
  }

  private boolean isServerModeEnabled(Context context, MealseLogger log) {
    try {
      SharedPreferences prefs = context.getSharedPreferences(PREF_TUNNEL, Context.MODE_PRIVATE);
      String mode = prefs.getString(KEY_TUNNEL_MODE, "client");
      int listenPort = prefs.getInt(KEY_TUNNEL_LISTEN_PORT, DEFAULT_LISTEN_PORT);
      log.log("Tunnel prefs: mode=" + mode + ", listenPort=" + listenPort);
      return MODE_SERVER.equalsIgnoreCase(mode);
    } catch (Exception e) {
      log.logError("Ошибка чтения tunnel prefs", e);
      return false;
    }
  }

  private void startVpnServer(Context context, MealseLogger log) {
    try {
      SharedPreferences prefs = context.getSharedPreferences(PREF_TUNNEL, Context.MODE_PRIVATE);
      int listenPort = prefs.getInt(KEY_TUNNEL_LISTEN_PORT, DEFAULT_LISTEN_PORT);

      Intent vpnIntent = new Intent(context, TunnelVpnService.class);
      vpnIntent.setAction(TunnelVpnService.ACTION_START);
      vpnIntent.putExtra(TunnelVpnService.EXTRA_MODE, TunnelVpnService.MODE_SERVER);
      vpnIntent.putExtra(TunnelVpnService.EXTRA_LISTEN_PORT, listenPort);

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(vpnIntent);
      } else {
        context.startService(vpnIntent);
      }
      log.log("✓ TunnelVpnService(SERVER, " + listenPort + ") запущен");
    } catch (Exception e) {
      log.logError("Ошибка запуска VPN-сервера", e);
    }
  }
}t listenPort = prefs.getInt(KEY_TUNNEL_LISTEN_PORT, DEFAULT_LISTEN_PORT);

      Intent vpnIntent = new Intent(context, TunnelVpnService.class);
      vpnIntent.setAction(TunnelVpnService.ACTION_START);
      vpnIntent.putExtra(TunnelVpnService.EXTRA_MODE, TunnelVpnService.MODE_SERVER);
      vpnIntent.putExtra(TunnelVpnService.EXTRA_LISTEN_PORT, listenPort);

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(vpnIntent);
      } else {
        context.startService(vpnIntent);
      }
      log.log("✓ TunnelVpnService(SERVER, " + listenPort + ") запущен");
    } catch (Exception e) {
      log.logError("Ошибка запуска VPN-сервера", e);
    }
  }
}