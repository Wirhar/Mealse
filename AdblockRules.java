package com.my.mealse.browser.proxy;

/**
 * Встроенные правила блокировки рекламы.
 *
 * hosts-список + regex-правила + whitelist.
 * Компилируются один раз в AdblockEngine.
 */
public final class AdblockRules {

    // Hosts — точное совпадение или суффикс
    public static final String[] BLOCKED_HOSTS = {
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "google-analytics.com",
        "googletagmanager.com",
        "googletagservices.com",
        "an.yandex.ru",
        "ads.yandex.ru",
        "ad.mail.ru",
        "r.mail.ru",
        "top-fwz1.mail.ru",
        "adriver.ru",
        "adfox.ru",
        "mc.yandex.ru",          // ★ опционально — см. whitelist
        "appmetrica.yandex.ru",
        "apptracer.ru",
        "sdk-api.apptracer.ru",
        "connect.facebook.net",
        "analytics.tiktok.com",
        "ads.tiktok.com",
        "mon.tiktokv.com",
        "mon-sg.tiktokv.com",
        "mcs-sg.tiktokv.com",
        "libraweb-sg.tiktok.com",
        "mssdk-va.tiktok.com",
        "vn.tiktokv.com"
    };

    // Regex — компилируются один раз в AdblockEngine
    public static final String[] BLOCKED_REGEX = {
        "^https?://([a-z0-9.-]+\\.)?(doubleclick|googlesyndication|googleadservices|adservice)\\.(net|com)/",
        "^https?://([a-z0-9.-]+\\.)?(yandex|ya)\\.(ru|net)/(ads?|an|rtb)/.*",
        "^https?://([a-z0-9.-]+\\.)?(mail\\.ru|vk\\.com)/(adq|vp|adman)/",
        "^https?://([a-z0-9.-]+\\.)?(metrika|metrika-informer)\\.yandex\\.(ru|net)/",
        "^https?://([a-z0-9.-]+\\.)?(appmetrica|apptracer)\\.(ru|com|net)/",
        "^https?://([a-z0-9.-]+\\.)?stats\\.dzen\\.ru/api/.*pixels.*",
        "^https?://([a-z0-9.-]+\\.)?mc\\.yandex\\.ru/watch/.*"
    };

    // Whitelist — НЕ блокировать, даже если совпало с правилом
    public static final String[] WHITELIST = {
        "mc.yandex.ru/metrika/tag.js",  // ★ SSO-зависимый
        "google-analytics.com/analytics.js"
    };

    private AdblockRules() {}
}