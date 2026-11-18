package com.my.mealse.browser.proxy;

import android.content.Context;

import java.net.URL;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Движок блокировки рекламы.
 *
 * Приоритет проверки:
 *  1. Whitelist — если URL содержит whitelist-подстроку, пропускаем.
 *  2. Hosts — точное совпадение или суффикс домена.
 *  3. Regex — точечные правила.
 *
 * Статистика ведётся в AdblockStats.
 */
public final class AdblockEngine {

    private static final String TAG = "AdblockEngine";

    private final Pattern[] compiledRegex;
    private final AdblockStats stats;

    public AdblockEngine(Context context) {
        this.compiledRegex = new Pattern[AdblockRules.BLOCKED_REGEX.length];
        for (int i = 0; i < AdblockRules.BLOCKED_REGEX.length; i++) {
            try {
                compiledRegex[i] = Pattern.compile(
                    AdblockRules.BLOCKED_REGEX[i],
                    Pattern.CASE_INSENSITIVE
                );
            } catch (PatternSyntaxException e) {
                compiledRegex[i] = null;
            }
        }
        this.stats = new AdblockStats(context);
    }

    /**
     * @return true — заблокировать; false — пропустить
     */
    public boolean shouldBlock(String url) {
        if (url == null || url.isEmpty()) return false;
        if (isWhitelisted(url)) return false;

        // 1. Hosts
        String host = extractHost(url);
        if (host != null && isHostBlocked(host)) {
            stats.recordBlocked(url, host);
            return true;
        }

        // 2. Regex
        for (Pattern p : compiledRegex) {
            if (p != null && p.matcher(url).find()) {
                stats.recordBlocked(url, host);
                return true;
            }
        }
        return false;
    }

    private boolean isHostBlocked(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        for (String rule : AdblockRules.BLOCKED_HOSTS) {
            if (h.equals(rule) || h.endsWith("." + rule)) return true;
        }
        return false;
    }

    private boolean isWhitelisted(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        for (String w : AdblockRules.WHITELIST) {
            if (u.contains(w)) return true;
        }
        return false;
    }

    private static String extractHost(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    public AdblockStats getStats() {
        return stats;
    }
}