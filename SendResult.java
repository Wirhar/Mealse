package com.my.mealse.messenger.transport;

import androidx.annotation.Nullable;

/**
 * Результат одной попытки отправки.
 */
public final class SendResult {

    private final boolean success;
    private final int httpCode;       // 0 для Wi-Fi Direct
    private final String errorMessage;
    private final long elapsedMs;
    private final String transport;   // "http" | "wifidirect"

    private SendResult(boolean success, int httpCode, String errorMessage,
                       long elapsedMs, String transport) {
        this.success = success;
        this.httpCode = httpCode;
        this.errorMessage = errorMessage;
        this.elapsedMs = elapsedMs;
        this.transport = transport;
    }

    public static SendResult ok(String transport, long elapsedMs) {
        return new SendResult(true, 200, null, elapsedMs, transport);
    }

    public static SendResult okHttp(int code, long elapsedMs) {
        return new SendResult(true, code, null, elapsedMs, "http");
    }

    public static SendResult error(String transport, String message, long elapsedMs) {
        return new SendResult(false, 0, message, elapsedMs, transport);
    }

    public static SendResult errorHttp(int code, String message, long elapsedMs) {
        return new SendResult(false, code, message, elapsedMs, "http");
    }

    public boolean isSuccess() {
        return success;
    }

    public int getHttpCode() {
        return httpCode;
    }

    @Nullable
    public String getErrorMessage() {
        return errorMessage;
    }

    public long getElapsedMs() {
        return elapsedMs;
    }

    public String getTransport() {
        return transport;
    }

    @Override
    public String toString() {
        return "SendResult{success=" + success
            + ", transport=" + transport
            + ", code=" + httpCode
            + ", elapsed=" + elapsedMs + "ms"
            + ", error='" + errorMessage + "'}";
    }
}