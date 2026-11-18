package com.my.mealse.messenger.transport;

import android.content.Context;

import androidx.annotation.NonNull;

import com.my.mealse.model.TrustedNode;
import com.my.mealse.util.MealseLogger;
import com.my.mealse.util.NodeManager;
import com.my.mealse.util.WifiDirectManager;

/**
 * Отправка сообщений через Wi-Fi Direct (офлайн).
 *
 * ★ ИЗМЕНЕНО (шаг 18.1): sendSignal() теперь возвращает boolean —
 *   реально ли кадр поставлен в очередь на отправку. Если WD отвалился
 *   посреди сессии (Android убил channel, RST и т.п.), wdm.sendSignal()
 *   вернёт false → возвращаем SendResult.error() → DeliveryScheduler
 *   сделает markAttempt и повторит попытку позже. Раньше в этом случае
 *   ставился фальшивый SENT, а сообщение не доставлялось.
 */
public class WifiDirectMessageSender implements MessageSender {

    private static final String TAG = "MessengerWdSender";

    private final Context appContext;
    private final MealseLogger log;
    private final WifiDirectManager wdm;

    public WifiDirectMessageSender(Context context, WifiDirectManager wdm) {
        this.appContext = context.getApplicationContext();
        this.log = MealseLogger.get(appContext, TAG);
        this.wdm = wdm;
    }

    @Override
    public void send(@NonNull String targetNodeKey,
                     @NonNull String envelopeJson,
                     @NonNull Callback callback) {

        long start = System.currentTimeMillis();

        if (wdm == null || !wdm.isWifiDirectSupported()) {
            callback.onResult(SendResult.error("wifidirect",
                "Wi-Fi Direct недоступен", System.currentTimeMillis() - start));
            return;
        }

        if (!wdm.isConnected()) {
            callback.onResult(SendResult.error("wifidirect",
                "Wi-Fi Direct не подключён", System.currentTimeMillis() - start));
            return;
        }

        try {
            WifiDirectManager.Peer remote = wdm.getRemotePeer();
            if (remote == null) {
                callback.onResult(SendResult.error("wifidirect",
                    "remotePeer == null", System.currentTimeMillis() - start));
                return;
            }

            int idx = targetNodeKey.lastIndexOf(':');
            if (idx > 0) {
                String mac = targetNodeKey.substring(0, idx);
                int port = 0;
                try {
                    port = Integer.parseInt(targetNodeKey.substring(idx + 1));
                } catch (NumberFormatException ignored) {
                }
                NodeManager nm = new NodeManager(appContext);
                TrustedNode target = nm.findByIpPort(mac, port);
                if (target != null && target.getDeviceId() != null
                    && remote.deviceId != null
                    && !target.getDeviceId().equals(remote.deviceId)) {
                    callback.onResult(SendResult.error("wifidirect",
                        "подключены к другому устройству (remote.deviceId="
                            + remote.deviceId + ", target.deviceId=" + target.getDeviceId() + ")",
                        System.currentTimeMillis() - start));
                    return;
                }
            }

            // ★ ИЗМЕНЕНО: проверяем результат sendSignal()
            boolean queued = wdm.sendSignal(envelopeJson);

            long elapsed = System.currentTimeMillis() - start;

            if (!queued) {
                // WD-канал есть, но кадр не влез в очередь или сокет мёртв.
                // Возвращаем error → DeliveryScheduler.markAttempt → retry.
                log.warn("send WD: кадр НЕ поставлен в очередь (len="
                    + envelopeJson.length() + "), target=" + targetNodeKey);
                callback.onResult(SendResult.error("wifidirect",
                    "sendSignal failed (канал разорван?)", elapsed));
                return;
            }

            log.log("send WD: " + targetNodeKey + ", " + envelopeJson.length()
                + " симв., queued за " + elapsed + "мс");
            callback.onResult(SendResult.ok("wifidirect", elapsed));

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.logError("send WD failed: " + targetNodeKey, e);
            callback.onResult(SendResult.error("wifidirect", e.getMessage(), elapsed));
        }
    }
}