package com.my.mealse.browser.proxy;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.WebResourceResponse;

import com.my.mealse.util.MealseLogger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Дисковый кэш для ответов прокси.
 *
 * Ключ = SHA-256 от "METHOD|URL".
 * Файлы: getFilesDir()/browser/cache/<hash>.bin  (тело)
 *        getFilesDir()/browser/cache/<hash>.meta (заголовки + mime, plaintext)
 *
 * Ограничение по размеру: 50 МБ (LRU удаляет старые).
 *
 * Что кэшируем:
 *  - GET/HEAD 200 OK
 *  - Content-Type: text/*, application/javascript, application/json,
 *    image/*, font/*, application/xml
 *  - Cache-Control: max-age > 0 (или отсутствует — считаем 1 час)
 *
 * Что не кэшируем:
 *  - POST/PUT/DELETE
 *  - ответы с Cache-Control: no-store / private (без max-age)
 *  - Set-Cookie
 */
public class ProxyCache {

    private static final String TAG = "ProxyCache";
    private static final String PREF_CACHE = "mealse_browser_cache";
    private static final String KEY_TOTAL_BYTES = "total_bytes";
    private static final long MAX_BYTES = 50L * 1024 * 1024;
    private static final long DEFAULT_TTL_MS = 60L * 60 * 1000;

    private final Context appContext;
    private final MealseLogger log;
    private final File cacheDir;
    private final SharedPreferences prefs;

    public ProxyCache(Context context) {
        this.appContext = context.getApplicationContext();
        this.log = MealseLogger.get(appContext, TAG);
        this.cacheDir = new File(new File(appContext.getFilesDir(), "browser"), "cache");
        if (!cacheDir.exists()) cacheDir.mkdirs();
        this.prefs = appContext.getSharedPreferences(PREF_CACHE, Context.MODE_PRIVATE);
    }

    /**
     * Пытается прочитать ответ из кэша.
     *
     * @return WebResourceResponse если в кэше, иначе null
     */
    public WebResourceResponse get(String method, String url) {
        if (!"GET".equals(method) && !"HEAD".equals(method)) return null;
        try {
            String hash = hash(method + "|" + url);
            File metaFile = new File(cacheDir, hash + ".meta");
            File bodyFile = new File(cacheDir, hash + ".bin");
            if (!metaFile.exists() || !bodyFile.exists()) return null;

            // Проверяем TTL
            long mtime = metaFile.lastModified();
            long ttl = DEFAULT_TTL_MS;
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader(metaFile), 4096)) {
                String line;
                String mime = "text/plain";
                String encoding = "utf-8";
                int status = 200;
                String ttlStr = null;
                StringBuilder extraHeaders = new StringBuilder();
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("mime:")) mime = line.substring(5).trim();
                    else if (line.startsWith("encoding:")) encoding = line.substring(9).trim();
                    else if (line.startsWith("status:")) {
                        try { status = Integer.parseInt(line.substring(7).trim()); }
                        catch (Exception ignored) {}
                    } else if (line.startsWith("ttl:")) {
                        ttlStr = line.substring(4).trim();
                    } else if (line.startsWith("h:")) {
                        // Дополнительный заголовок
                        extraHeaders.append(line.substring(2)).append("\n");
                    }
                }
                if (ttlStr != null) {
                    try { ttl = Long.parseLong(ttlStr); } catch (Exception ignored) {}
                }
                long ageMs = System.currentTimeMillis() - mtime;
                if (ageMs > ttl) {
                    log.log("cache EXPIRED: " + method + " " + url + " (age=" + ageMs + "ms)");
                    return null;
                }

                InputStream body = new FileInputStream(bodyFile);
                java.util.HashMap<String, String> headers = new java.util.HashMap<>();
                headers.put("Content-Type", mime);
                return new WebResourceResponse(mime, encoding, status, "OK", headers, body);
            }
        } catch (Throwable t) {
            log.warn("cache get error: " + t.getMessage());
            return null;
        }
    }

    /**
     * Сохраняет ответ в кэш (только если он кэшируемый).
     */
    public void put(String method, String url, int status, Map<String, String> headers,
                    byte[] body) {
        if (!"GET".equals(method) && !"HEAD".equals(method)) return;
        if (status != 200 && status != 203 && status != 204) return;
        if (body == null) return;

        // Не кэшируем, если есть Set-Cookie
        String cacheControl = null;
        String mime = null;
        String encoding = "utf-8";
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() == null) continue;
                String k = e.getKey().toLowerCase();
                if ("set-cookie".equals(k)) return;
                if ("cache-control".equals(k)) cacheControl = e.getValue();
                if ("content-type".equals(k)) {
                    String ct = e.getValue();
                    int semi = ct.indexOf(';');
                    if (semi > 0) {
                        mime = ct.substring(0, semi).trim();
                        String rest = ct.substring(semi + 1).toLowerCase();
                        if (rest.contains("charset=")) {
                            int ci = rest.indexOf("charset=");
                            encoding = rest.substring(ci + 8).trim();
                        }
                    } else {
                        mime = ct.trim();
                    }
                }
            }
        }

        if (cacheControl != null) {
            String ccLower = cacheControl.toLowerCase();
            if (ccLower.contains("no-store") || ccLower.contains("no-cache")) return;
            if (ccLower.contains("private") && !ccLower.contains("max-age")) return;
        }

        if (mime == null) return;
        if (!isCacheableMime(mime)) return;

        long ttl = DEFAULT_TTL_MS;
        if (cacheControl != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("max-age=(\\d+)").matcher(cacheControl);
            if (m.find()) {
                try { ttl = Long.parseLong(m.group(1)) * 1000L; } catch (Exception ignored) {}
            }
        }

        try {
            String hash = hash(method + "|" + url);
            File metaFile = new File(cacheDir, hash + ".meta");
            File bodyFile = new File(cacheDir, hash + ".bin");

            // Удаляем старое
            long oldSize = bodyFile.exists() ? bodyFile.length() : 0;
            if (metaFile.exists()) metaFile.delete();
            if (bodyFile.exists()) bodyFile.delete();

            try (FileOutputStream fos = new FileOutputStream(bodyFile)) {
                fos.write(body);
            }

            try (java.io.FileWriter fw = new java.io.FileWriter(metaFile)) {
                fw.write("mime:" + mime + "\n");
                fw.write("encoding:" + encoding + "\n");
                fw.write("status:" + status + "\n");
                fw.write("ttl:" + ttl + "\n");
            }

            // Обновляем счётчик
            long delta = (long) body.length - oldSize;
            long total = prefs.getLong(KEY_TOTAL_BYTES, 0) + delta;
            prefs.edit().putLong(KEY_TOTAL_BYTES, total).apply();

            // Чистим при переполнении
            if (total > MAX_BYTES) {
                log.log("cache OVERFLOW: " + total + " > " + MAX_BYTES + " — чистим");
                clearOldest(total - MAX_BYTES + MAX_BYTES / 4);
            }

            log.log("cache PUT: " + method + " " + url + " (" + body.length + "B)");
        } catch (Throwable t) {
            log.warn("cache put error: " + t.getMessage());
        }
    }

    /** Очищает кэш полностью. */
    public void clear() {
        try {
            File[] files = cacheDir.listFiles();
            if (files != null) {
                for (File f : files) f.delete();
            }
            prefs.edit().putLong(KEY_TOTAL_BYTES, 0).apply();
            log.log("cache CLEARED");
        } catch (Throwable t) {
            log.warn("cache clear: " + t.getMessage());
        }
    }

    public long getTotalBytes() {
        return prefs.getLong(KEY_TOTAL_BYTES, 0);
    }

    // ============ Внутреннее ============

    private void clearOldest(long needFree) {
        try {
            File[] metas = cacheDir.listFiles((d, name) -> name.endsWith(".meta"));
            if (metas == null) return;
            java.util.Arrays.sort(metas, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            long freed = 0;
            long total = prefs.getLong(KEY_TOTAL_BYTES, 0);
            for (File m : metas) {
                if (freed >= needFree) break;
                String base = m.getName().substring(0, m.getName().length() - 5);
                File body = new File(cacheDir, base + ".bin");
                long sz = body.exists() ? body.length() : 0;
                m.delete();
                body.delete();
                freed += sz;
                total -= sz;
            }
            prefs.edit().putLong(KEY_TOTAL_BYTES, Math.max(0, total)).apply();
            log.log("cache FREED: " + freed + "B");
        } catch (Throwable t) {
            log.warn("clearOldest: " + t.getMessage());
        }
    }

    private static boolean isCacheableMime(String mime) {
        String m = mime.toLowerCase();
        return m.startsWith("text/")
                || m.startsWith("image/")
                || m.startsWith("font/")
                || m.equals("application/javascript")
                || m.equals("application/json")
                || m.equals("application/xml")
                || m.equals("application/xhtml+xml");
    }

    private static String hash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }
}