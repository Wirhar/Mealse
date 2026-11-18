package com.my.mealse.browser.proxy;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Статистика блокировки рекламы.
 *
 * Хранится в SharedPreferences "mealse_adblock_stats".
 * Счётчики держатся в AtomicLong в памяти, flush() — при onDestroy().
 */
public final class AdblockStats {

    private static final String PREF = "mealse_adblock_stats";
    private static final String KEY_BLOCKED = "blocked_count";
    private static final String KEY_SAVED_BYTES = "saved_bytes";

    private final SharedPreferences prefs;
    private final AtomicLong blocked = new AtomicLong();
    private final AtomicLong savedBytes = new AtomicLong();

    public AdblockStats(Context context) {
        this.prefs = context.getApplicationContext()
            .getSharedPreferences(PREF, Context.MODE_PRIVATE);
        blocked.set(prefs.getLong(KEY_BLOCKED, 0));
        savedBytes.set(prefs.getLong(KEY_SAVED_BYTES, 0));
    }

    public void recordBlocked(String url, String host) {
        blocked.incrementAndGet();
        // Оценка экономии: 50 КБ на баннер/рекламу
        savedBytes.addAndGet(50 * 1024);
    }

    public long getBlockedCount() {
        return blocked.get();
    }

    public long getSavedBytes() {
        return savedBytes.get();
    }

    public void flush() {
        prefs.edit()
            .putLong(KEY_BLOCKED, blocked.get())
            .putLong(KEY_SAVED_BYTES, savedBytes.get())
            .apply();
    }

    public void reset() {
        blocked.set(0);
        savedBytes.set(0);
        flush();
    }
}