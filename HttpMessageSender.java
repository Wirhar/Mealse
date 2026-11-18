package com.my.mealse.messenger.transport;

import android.content.Context;

import androidx.annotation.NonNull;

import com.my.mealse.util.MealseLogger;

import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

/**
 * Отправка сообщений по HTTP — СИНХРОННО, с реальным ответом.
 *
 * ★ Шаг 13.1: переписан с fire-and-forget (через ConnectionManager)
 *   на синхронный HTTP. Причины:
 *     - офлайн-доставка: если получатель выключен — ConnectException
 *       → DeliveryScheduler.markAttempt → backoff → retry;
 *     - честный статус SENT: только если получатель ответил 200;
 *     - ack DELIVERED/READ: получатель шлёт отдельным POST /signal/message,
 *       MessageReceiver его обрабатывает (Шаг 13.2).
 *
 * ⚠️ send() блокирующий. Вызывается из DeliveryScheduler.executor
 * (фоновый single-thread) — не из UI. Не вызывать из main thread.
 *
 * URL: POST http://<ip>:<port>/signal/message
 * Body: envelopeJson (MessageEnvelope.toJson())
 * Ответ: 200 {"status":"ok","type":"message"} — успех.
 *        404 — ServerService не знает /signal/message (старая версия).
 *        400 — некорректный JSON (наша ошибка).
 *        403 — auth denied (получатель не разрешил доступ).
 *        5xx — ошибка сервера.
 *
 * Таймауты: connect 10с, read 15с. Получатель отвечает быстро — ему
 * нужно только распарсить JSON, self-loop проверить и broadcast'нуть.
 */
public class HttpMessageSender implements MessageSender {

    private static final String TAG = "MessengerHttpSender";

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    private final Context appContext;
    private final MealseLogger log;

    public HttpMessageSender(Context context) {
        this.appContext = context.getApplicationContext();
        this.log = MealseLogger.get(appContext, TAG);
    }

    @Override
    public void send(@NonNull String targetNodeKey,
                     @NonNull String envelopeJson,
                     @NonNull Callback callback) {

        long start = System.currentTimeMillis();

        // Парсим "ip:port"
        String ip;
        int port;
        try {
            int idx = targetNodeKey.lastIndexOf(':');
            if (idx <= 0) {
                callback.onResult(SendResult.error("http",
                    "invalid nodeKey: " + targetNodeKey, 0));
                return;
            }
            ip = targetNodeKey.substring(0, idx);
            port = Integer.parseInt(targetNodeKey.substring(idx + 1));
        } catch (Exception e) {
            callback.onResult(SendResult.error("http",
                "parse nodeKey: " + e.getMessage(), 0));
            return;
        }

        if (ip == null || ip.isEmpty() || port <= 0 || port > 65535) {
            callback.onResult(SendResult.error("http",
                "invalid ip/port: " + ip + ":" + port, 0));
            return;
        }

        String urlStr = "http://" + formatHost(ip) + ":" + port + "/signal/message";
        log.log("send HTTP: POST " + urlStr + ", " + envelopeJson.length() + " симв.");

        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "Mealse-Messenger/1.0");
            conn.setDoOutput(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            byte[] bodyBytes = envelopeJson.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bodyBytes.length);

            try (BufferedWriter w = new BufferedWriter(
                new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8))) {
                w.write(envelopeJson);
                w.flush();
            }

            int code = conn.getResponseCode();
            long elapsed = System.currentTimeMillis() - start;

            // Читаем ответ для лога
            String respBody = "";
            try (InputStream in = (code >= 200 && code < 300)
                ? conn.getInputStream() : conn.getErrorStream()) {
                if (in != null) {
                    byte[] buf = new byte[1024];
                    int r;
                    StringBuilder sb = new StringBuilder();
                    while ((r = in.read(buf)) != -1) {
                        sb.append(new String(buf, 0, r, StandardCharsets.UTF_8));
                        if (sb.length() > 512) break;
                    }
                    respBody = sb.toString();
                }
            } catch (Exception ignored) {}

            if (code == 200) {
                log.log("send HTTP OK: " + targetNodeKey + " за " + elapsed
                    + "мс, ответ=" + truncate(respBody, 100));
                callback.onResult(SendResult.okHttp(200, elapsed));
            } else if (code == 404) {
                // ServerService получателя ещё не знает /signal/message —
                // значит у него старая версия. Ретраить бессмысленно (не
                // изменится). Но и не удалять сразу — вдруг обновится.
                log.warn("send HTTP 404: получатель не поддерживает /signal/message"
                    + " (возможно, старая версия Mealse)");
                callback.onResult(SendResult.errorHttp(404,
                    "endpoint not supported", elapsed));
            } else if (code == 403) {
                log.warn("send HTTP 403: auth denied для " + targetNodeKey);
                callback.onResult(SendResult.errorHttp(403,
                    "access denied: " + truncate(respBody, 100), elapsed));
            } else {
                log.warn("send HTTP " + code + " для " + targetNodeKey
                    + ": " + truncate(respBody, 100));
                callback.onResult(SendResult.errorHttp(code,
                    "HTTP " + code + ": " + truncate(respBody, 100), elapsed));
            }

        } catch (SocketTimeoutException e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("send HTTP TIMEOUT: " + targetNodeKey + " за " + elapsed + "мс");
            callback.onResult(SendResult.error("http",
                "timeout (" + elapsed + "ms)", elapsed));
        } catch (ConnectException e) {
            long elapsed = System.currentTimeMillis() - start;
            String msg = e.getMessage() != null ? e.getMessage() : "connect refused";
            log.warn("send HTTP ConnectException: " + targetNodeKey + " — " + msg);
            callback.onResult(SendResult.error("http", msg, elapsed));
        } catch (UnknownHostException e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("send HTTP UnknownHost: " + ip);
            callback.onResult(SendResult.error("http", "unknown host: " + ip, elapsed));
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.logError("send HTTP failed: " + targetNodeKey, e);
            callback.onResult(SendResult.error("http",
                e.getMessage() != null ? e.getMessage() : "unknown error", elapsed));
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * IPv6-литерал нужно обернуть в [..] для URL.
     * Например, "2a00:1fa0::1" → "[2a00:1fa0::1]".
     */
    private static String formatHost(String ip) {
        if (ip == null) return "";
        if (ip.contains(":") && !ip.startsWith("[")) {
            return "[" + ip + "]";
        }
        return ip;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}