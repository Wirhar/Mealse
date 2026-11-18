package com.my.mealse.filemanager.server;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.my.mealse.MainActivity;
import com.my.mealse.R;
import com.my.mealse.model.TrustedNode;
import com.my.mealse.util.MealseLogger;
import com.my.mealse.util.NodeManager;

/**
 * Авторизация входящих запросов к RemoteFsService.
 *
 * Логика:
 *   1. Клиент присылает X-Mealse-Fingerprint и X-Mealse-DeviceId.
 *   2. Сервер ищет узел в NodeManager:
 *        - если fingerprint есть у узла и у узла стоит fileAccessGranted=true
 *          → доступ разрешён;
 *        - если fingerprint есть у узла и fileAccessGranted=false
 *          → 403 ACCESS_DENIED («доступ не разрешён»);
 *        - если fingerprint не найден в узлах вообще
 *          → 403 ACCESS_DENIED («неизвестный узел»);
 *   3. При попытке доступа от известного узла без разрешения —
 *      отправляем нотификацию владельцу «Узел X просит доступ к файлам»
 *      (с кнопкой «Разрешить» / «Отклонить»).
 *
 * Требует, чтобы TrustedNode имел поле fileAccessGranted (см. правки
 * в NodeManager/TrustedNode в конце Шага 3).
 *
 * Запрос разрешения можно повторять не чаще, чем раз в N секунд на узел
 * (rate-limit), чтобы не спамить владельца.
 */
public class RemoteFsAuth {

    private static final String TAG = "RemoteFsAuth";
    private static final String CHANNEL_ID = "MealseFsAccessChannel";
    private static final int NOTIF_ID_BASE = 300;
    private static final long REQUEST_RATE_LIMIT_MS = 30_000;

    private final Context appContext;
    private final MealseLogger log;
    private final NodeManager nodeManager;

    /** sourceId (ключ узла) → timestamp последнего запроса разрешения. */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> lastRequestTs =
            new java.util.concurrent.ConcurrentHashMap<>();

    public RemoteFsAuth(Context context) {
        this.appContext = context.getApplicationContext();
        this.log = MealseLogger.get(appContext, TAG);
        this.nodeManager = new NodeManager(appContext);
        createChannel();
    }

    // ============================================================
    //  Результат проверки
    // ============================================================

    public static class AuthResult {
        public final boolean allowed;
        public final TrustedNode node;      // null, если узел неизвестен
        public final String reason;

        private AuthResult(boolean allowed, TrustedNode node, String reason) {
            this.allowed = allowed;
            this.node = node;
            this.reason = reason;
        }

        public static AuthResult allow(TrustedNode node) {
            return new AuthResult(true, node, "granted");
        }
        public static AuthResult denyUnknown() {
            return new AuthResult(false, null, "unknown node");
        }
        public static AuthResult denyNotGranted(TrustedNode node) {
            return new AuthResult(false, node, "not granted");
        }
    }

    // ============================================================
    //  Основная проверка
    // ============================================================

    /**
     * Проверить входящий запрос.
     *
     * @param clientFingerprint  из заголовка X-Mealse-Fingerprint
     * @param clientDeviceId     из заголовка X-Mealse-DeviceId
     * @param clientIp           IP клиента (для формирования sourceId)
     * @return AuthResult
     */
    public AuthResult authorize(String clientFingerprint, String clientDeviceId, String clientIp) {
        if (clientFingerprint == null || clientFingerprint.isEmpty()) {
            log.warn("authorize: пустой fingerprint");
            return AuthResult.denyUnknown();
        }

        // Ищем узел по fingerprint
        TrustedNode node = nodeManager.findByFingerprint(clientFingerprint);
        if (node == null && clientDeviceId != null && !clientDeviceId.isEmpty()) {
            // Fallback — по deviceId
            java.util.List<TrustedNode> byDevice = nodeManager.findAllByDeviceId(clientDeviceId);
            if (!byDevice.isEmpty()) node = byDevice.get(0);
        }

        if (node == null) {
            log.warn("authorize: узел не найден (fp=" + shortId(clientFingerprint) + ")");
            return AuthResult.denyUnknown();
        }

        // Проверяем флаг разрешения
        if (node.isFileAccessGranted()) {
            log.log("authorize: доступ разрешён для " + node.getName() + " (" + node.getKey() + ")");
            return AuthResult.allow(node);
        }

        // Не разрешён — попробуем показать запрос владельцу
        log.warn("authorize: узел " + node.getName() + " известен, но fileAccessGranted=false");
        maybeRequestPermission(node, clientIp);

        return AuthResult.denyNotGranted(node);
    }

    // ============================================================
    //  Запрос разрешения у владельца
    // ============================================================

    private void maybeRequestPermission(TrustedNode node, String clientIp) {
        String sourceId = node.getKey();
        long now = System.currentTimeMillis();
        Long last = lastRequestTs.get(sourceId);
        if (last != null && (now - last) < REQUEST_RATE_LIMIT_MS) {
            log.log("maybeRequestPermission: rate-limit для " + sourceId);
            return;
        }
        lastRequestTs.put(sourceId, now);

        String nodeName = node.getName() != null ? node.getName() : "Узел";

        Notification.Builder b = newNotificationBuilder();
        b.setContentTitle("Запрос доступа к файлам")
                .setContentText(nodeName + " просит доступ к файлам этого устройства")
                .setSmallIcon(R.drawable.ic_file)
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_HIGH);

        // Тап по нотификации — открыть MainActivity (там будет список узлов,
        // в меню узла — «Разрешить доступ к файлам»)
        Intent openIntent = new Intent(appContext, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                appContext,
                NOTIF_ID_BASE + sourceId.hashCode(),
                openIntent,
                pendingFlags());

        b.setContentIntent(pi);

        NotificationManager nm = (NotificationManager)
                appContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            int notifId = NOTIF_ID_BASE + Math.abs(sourceId.hashCode() % 1000);
            nm.notify(notifId, b.build());
            log.log("Показан запрос разрешения для " + sourceId);
        }
    }

    // ============================================================
    //  Публичные методы (вызываются из UI)
    // ============================================================

    /**
     * Разрешить доступ к файлам узлу с указанным ключом.
     */
    public boolean grantAccess(String nodeKey) {
        TrustedNode node = findNodeByKey(nodeKey);
        if (node == null) {
            log.warn("grantAccess: узел не найден: " + nodeKey);
            return false;
        }
        node.setFileAccessGranted(true);
        boolean ok = nodeManager.saveNode(node);
        if (ok) {
            log.log("grantAccess: доступ разрешён для " + nodeKey);
            cancelNotification(nodeKey);
        }
        return ok;
    }

    /**
     * Отозвать доступ к файлам.
     */
    public boolean revokeAccess(String nodeKey) {
        TrustedNode node = findNodeByKey(nodeKey);
        if (node == null) {
            log.warn("revokeAccess: узел не найден: " + nodeKey);
            return false;
        }
        node.setFileAccessGranted(false);
        boolean ok = nodeManager.saveNode(node);
        if (ok) {
            log.log("revokeAccess: доступ отозван для " + nodeKey);
        }
        return ok;
    }

    /**
     * Проверить, разрешён ли доступ для узла по ключу.
     */
    public boolean isGranted(String nodeKey) {
        TrustedNode node = findNodeByKey(nodeKey);
        return node != null && node.isFileAccessGranted();
    }

    // ============================================================
    //  Хелперы
    // ============================================================

    private TrustedNode findNodeByKey(String nodeKey) {
        if (nodeKey == null) return null;
        java.util.List<TrustedNode> all = nodeManager.getAllNodes();
        for (TrustedNode n : all) {
            if (nodeKey.equals(n.getKey())) return n;
        }
        return null;
    }

    private void cancelNotification(String nodeKey) {
        NotificationManager nm = (NotificationManager)
                appContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        int notifId = NOTIF_ID_BASE + Math.abs(nodeKey.hashCode() % 1000);
        nm.cancel(notifId);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "Доступ к файлам Mealse",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Запросы от узлов на доступ к файлам");
            NotificationManager nm = (NotificationManager)
                    appContext.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification.Builder newNotificationBuilder() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(appContext, CHANNEL_ID);
        } else {
            //noinspection deprecation
            return new Notification.Builder(appContext);
        }
    }

    private int pendingFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return flags;
    }

    private static String shortId(String s) {
        if (s == null) return "null";
        return s.length() > 8 ? s.substring(0, 8) : s;
    }
}