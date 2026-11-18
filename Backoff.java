package com.my.mealse.messenger.transport;

/**
 * Экспоненциальный backoff для офлайн-доставки.
 *
 * Шаги (секунды): 2, 5, 15, 60, 300, 900, 3600, 21600 (кап 24ч).
 * Попытка 0 → 2с, 1 → 5с, ..., 7+ → 21600с.
 *
 * Плюс небольшой jitter (±10%), чтобы N сообщений не ретраились
 * ровно в одну миллисекунду.
 */
public final class Backoff {

    private static final long[] STEPS_SEC = {
        2, 5, 15, 60, 300, 900, 3600, 21600
    };

    /** Кап — 24 часа. */
    public static final long MAX_INTERVAL_MS = 24L * 60L * 60L * 1000L;

    /** Сколько попыток после которых считаем FAILED. */
    public static final int MAX_ATTEMPTS = 20;

    private Backoff() {
    }

    /**
     * Следующий интервал (мс) для attemptCount.
     * attemptCount = 0 → 2000мс, 1 → 5000мс, ... 7+ → 21600000мс.
     */
    public static long nextIntervalMs(int attemptCount) {
        if (attemptCount < 0) attemptCount = 0;
        int idx = Math.min(attemptCount, STEPS_SEC.length - 1);
        long baseMs = STEPS_SEC[idx] * 1000L;
        // jitter ±10%
        long jitter = (long) (baseMs * (Math.random() * 0.2 - 0.1));
        long result = baseMs + jitter;
        if (result < 1000L) result = 1000L;
        if (result > MAX_INTERVAL_MS) result = MAX_INTERVAL_MS;
        return result;
    }

    /** nextAttemptAt = now + interval. */
    public static long nextAttemptAt(int attemptCount, long nowMs) {
        return nowMs + nextIntervalMs(attemptCount);
    }

    /** Исчерпаны ли попытки. */
    public static boolean isExhausted(int attemptCount) {
        return attemptCount >= MAX_ATTEMPTS;
    }
}