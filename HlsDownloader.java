package com.my.mealse.filemanager.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.my.mealse.util.MealseLogger;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * HlsDownloader — загрузка HLS (m3u8) потоков.
 *
 * Логика:
 *   1. Скачиваем m3u8-плейлист.
 *   2. Извлекаем URL .ts-сегментов (учитываем относительные пути).
 *   3. Последовательно качаем сегменты во временную папку.
 *   4. Склеиваем в один .ts-файл.
 *   5. Возвращаем результат в callback.
 *
 * Поддерживает отмену через cancel().
 *
 * Колбэки вызываются в главном потоке.
 */
public class HlsDownloader {

    private static final String TAG = "HlsDownloader";

    private final Context appContext;
    private final MealseLogger log;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private OkHttpClient httpClient;

    private volatile boolean cancelled = false;
    private HlsCallback callback;
    private String sourceName = "video";

    public interface HlsCallback {
        void onStart();
        void onProgress(int percent);
        void onSuccess(File outputFile);
        void onError(String error);
    }

    public HlsDownloader(Context context) {
        this.appContext = context.getApplicationContext();
        this.log = MealseLogger.get(appContext, TAG);
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
        log.log("HlsDownloader создан");
    }

    public void setCallback(HlsCallback cb) {
        this.callback = cb;
    }

    public void setSourceName(String name) {
        this.sourceName = (name != null && !name.isEmpty()) ? name : "video";
    }

    public void cancel() {
        cancelled = true;
    }

    /**
     * Скачивает HLS-поток и сохраняет склеенный файл.
     *
     * @param m3u8Url  URL плейлиста
     * @param fileName имя без расширения; итоговый файл — fileName.ts
     */
    public void downloadAndConvert(String m3u8Url, String fileName) {
        executor.execute(() -> {
            cancelled = false;
            postStart();
            File tempDir = null;
            try {
                // 1. Плейлист
                String playlist = downloadString(m3u8Url);
                if (playlist == null) {
                    postError("Не удалось скачать m3u8");
                    return;
                }

                // 2. Сегменты
                List<String> segments = extractSegmentUrls(playlist, m3u8Url);
                if (segments.isEmpty()) {
                    postError("В плейлисте нет сегментов");
                    return;
                }
                log.log("HLS: сегментов " + segments.size());

                // 3. Временная папка
                tempDir = new File(appContext.getCacheDir(),
                        "hls_" + System.currentTimeMillis());
                if (!tempDir.mkdirs()) {
                    postError("Не удалось создать temp");
                    return;
                }

                List<File> segmentFiles = new ArrayList<>();
                int total = segments.size();
                int downloaded = 0;

                for (int i = 0; i < total; i++) {
                    if (cancelled) {
                        cleanup(tempDir);
                        postCancelled("Отменено");
                        return;
                    }
                    String segUrl = segments.get(i);
                    File segFile = new File(tempDir, String.format("seg_%05d.ts", i));

                    boolean ok = downloadFile(segUrl, segFile);
                    if (!ok) {
                        cleanup(tempDir);
                        postError("Ошибка сегмента " + i);
                        return;
                    }
                    segmentFiles.add(segFile);
                    downloaded = i + 1;
                    int percent = (downloaded * 100) / total;
                    postProgress(percent);
                }

                // 4. Склейка
                String safeBase = sanitizeName(fileName);
                String finalName = safeBase + "_" + System.currentTimeMillis() + ".ts";
                File output = new File(getOutputDir(), finalName);

                if (!mergeTsFiles(segmentFiles, output)) {
                    cleanup(tempDir);
                    postError("Ошибка склейки");
                    return;
                }

                cleanup(tempDir);
                postSuccess(output);
                log.log("HLS готов: " + output.getAbsolutePath());

            } catch (Exception e) {
                log.logError("downloadAndConvert", e);
                if (tempDir != null) cleanup(tempDir);
                postError("Ошибка: " + e.getMessage());
            }
        });
    }

    // ============================================================
    //  Работа с сетью
    // ============================================================

    private String downloadString(String url) {
        try {
            Request req = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 12)")
                    .build();
            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                return resp.body().string();
            }
        } catch (Exception e) {
            log.logError("downloadString " + url, e);
            return null;
        }
    }

    private boolean downloadFile(String url, File dest) {
        try {
            Request req = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 12)")
                    .build();
            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return false;
                try (InputStream in = resp.body().byteStream();
                     FileOutputStream fos = new FileOutputStream(dest)) {
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = in.read(buf)) != -1) {
                        if (cancelled) return false;
                        fos.write(buf, 0, r);
                    }
                    fos.flush();
                }
                return true;
            }
        } catch (Exception e) {
            log.logError("downloadFile " + url, e);
            return false;
        }
    }

    // ============================================================
    //  Разбор плейлиста
    // ============================================================

    private List<String> extractSegmentUrls(String playlist, String baseUrl) {
        List<String> result = new ArrayList<>();
        String basePath = getBasePath(baseUrl);

        // Строки, которые НЕ начинаются с "#" и содержат ".ts"
        Pattern p = Pattern.compile("^([^#\\s].*\\.ts.*)$", Pattern.MULTILINE);
        Matcher m = p.matcher(playlist);
        while (m.find()) {
            String seg = m.group(1).trim();
            seg = resolveUrl(seg, baseUrl, basePath);
            result.add(seg);
        }
        if (result.isEmpty()) {
            // Fallback: ищем любые .ts
            Pattern p2 = Pattern.compile("([^\\s\"']+\\.ts[^\\s\"']*)");
            Matcher m2 = p2.matcher(playlist);
            while (m2.find()) {
                String seg = m2.group(1).trim();
                seg = resolveUrl(seg, baseUrl, basePath);
                result.add(seg);
            }
        }
        return result;
    }

    private static String resolveUrl(String seg, String baseUrl, String basePath) {
        if (seg.startsWith("http://") || seg.startsWith("https://")) return seg;
        if (seg.startsWith("//")) return "https:" + seg;
        if (seg.startsWith("/")) {
            String host = getHost(baseUrl);
            return host + seg;
        }
        return basePath + seg;
    }

    private static String getBasePath(String url) {
        int slash = url.lastIndexOf('/');
        return slash > 0 ? url.substring(0, slash + 1) : url;
    }

    private static String getHost(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            return u.getProtocol() + "://" + u.getHost()
                    + (u.getPort() > 0 ? ":" + u.getPort() : "");
        } catch (Exception e) {
            return "";
        }
    }

    // ============================================================
    //  Склейка
    // ============================================================

    private boolean mergeTsFiles(List<File> segments, File output) {
        try (FileOutputStream fos = new FileOutputStream(output)) {
            byte[] buf = new byte[65536];
            for (File seg : segments) {
                try (InputStream in = new BufferedInputStream(new FileInputStream(seg))) {
                    int r;
                    while ((r = in.read(buf)) != -1) {
                        if (cancelled) return false;
                        fos.write(buf, 0, r);
                    }
                }
            }
            fos.flush();
            return true;
        } catch (IOException e) {
            log.logError("mergeTsFiles", e);
            return false;
        }
    }

    // ============================================================
    //  Вспомогательные
    // ============================================================

    private File getOutputDir() {
        File dir = new File(appContext.getFilesDir(), "downloads");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private void cleanup(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            if (f.isDirectory()) cleanup(f);
            else f.delete();
        }
        dir.delete();
    }

    private static String sanitizeName(String s) {
        if (s == null || s.isEmpty()) return "video";
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    // ============================================================
    //  Callback-постинг
    // ============================================================

    private void postStart() {
        mainHandler.post(() -> { if (callback != null) callback.onStart(); });
    }

    private void postProgress(int percent) {
        mainHandler.post(() -> { if (callback != null) callback.onProgress(percent); });
    }

    private void postSuccess(File file) {
        mainHandler.post(() -> { if (callback != null) callback.onSuccess(file); });
    }

    private void postError(String msg) {
        mainHandler.post(() -> { if (callback != null) callback.onError(msg); });
    }

    private void postCancelled(String msg) {
        mainHandler.post(() -> { if (callback != null) callback.onError(msg); });
    }
}