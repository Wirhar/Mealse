package com.my.mealse.browser.proxy;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * Определяет, включён ли шлюз (VPN-клиент Mealse активен).
 *
 * Условия:
 *  1. В mealse_tunnel_prefs: mode == "client"
 *  2. gateway_ip не пустой
 *  3. TunnelVpnService запущен
 *
 * Если всё true — возвращает GatewayInfo с ip и port (порт ServerService = 8081,
 * НЕ порт VPN. Прокси — это HTTP на 8081, а VPN-туннель на 8083 — это разные вещи).
 *
 * ★ ВАЖНО: gateway_ip — это IP шлюза в локальной сети/VPN.
 *          Прокси-эндпоинт шлюза живёт на порту ServerService (8081).
 *          VPN-клиент, если активен, автоматически заворачивает запросы к шлюзу
 *          через TUN, поэтому прокси работает и по 4G.
 */
public class GatewayDetector {

    public static class GatewayInfo {
        public final String gatewayIp;
        public final int proxyPort;      // порт ServerService шлюза (8081)
        public final int vpnPort;        // порт VPN-туннеля шлюза (8083), для информации

        public GatewayInfo(String gatewayIp, int proxyPort, int vpnPort) {
            this.gatewayIp = gatewayIp;
            this.proxyPort = proxyPort;
            this.vpnPort = vpnPort;
        }

        @Override
        public String toString() {
            return gatewayIp + ":" + proxyPort + " (VPN " + vpnPort + ")";
        }
    }

    private static final String PREF_TUNNEL = "mealse_tunnel_prefs";
    private static final String KEY_MODE = "mode";
    private static final String KEY_GATEWAY_IP = "gateway_ip";
    private static final String KEY_GATEWAY_PORT = "gateway_port";
    private static final String MODE_CLIENT = "client";
    private static final int SERVER_SERVICE_PORT = 8081;
    private static final int DEFAULT_VPN_PORT = 8083;

    private final Context appContext;

    public GatewayDetector(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /**
     * Возвращает GatewayInfo, если шлюз активен; null — иначе.
     */
    public GatewayInfo getActiveGateway() {
        try {
            SharedPreferences prefs = appContext.getSharedPreferences(PREF_TUNNEL, Context.MODE_PRIVATE);
            String mode = prefs.getString(KEY_MODE, "");
            String gatewayIp = prefs.getString(KEY_GATEWAY_IP, "");
            int vpnPort = prefs.getInt(KEY_GATEWAY_PORT, DEFAULT_VPN_PORT);

            if (!MODE_CLIENT.equalsIgnoreCase(mode)) return null;
            if (gatewayIp == null || gatewayIp.isEmpty()) return null;

            if (!isTunnelServiceRunning()) return null;

            return new GatewayInfo(gatewayIp, SERVER_SERVICE_PORT, vpnPort);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Проверяет, запущен ли TunnelVpnService.
     */
    private boolean isTunnelServiceRunning() {
        try {
            ActivityManager am = (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            // Класс можно указать по имени строкой, чтобы не тащить зависимость
            // от com.my.mealse.service.TunnelVpnService
            String targetClass = "com.my.mealse.service.TunnelVpnService";
            for (ActivityManager.RunningServiceInfo info : am.getRunningServices(Integer.MAX_VALUE)) {
                if (targetClass.equals(info.service.getClassName())) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }
}