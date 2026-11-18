package com.my.mealse.filemanager.util;

import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.widget.Toast;

import com.my.mealse.util.MealseLogger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Единый менеджер скачивания по прямой ссылке (не путать с
 * FileTransport, который качает с удалённого узла Mealse).
 *
 * Что умеет:
 *   1. Прямой файл по HTTP/HTTPS → DownloadManager (внешняя) или
 *      стрим в files/ (внутренняя).
 *   2. HLS .m3u8 → HlsDownloader.
 *   3. data: URL → декодирует base64 → файл.
 *   4. blob: URL — обрабатывается через JS-мост (не входит сюда).
 *
 * Использование:
 *   MAS_FileDownloadManager mgr = new MAS_FileDownloadManager(ctx);
 *   mgr.setCallback(...);
 *   mgr.download(url, fileName, Storage.INTERNAL);
 */
public class MAS_FileDownloadManager {

    private static final String TAG = "FileDownloadManager";
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    public enum Storage {
        /** Внешняя публичная папка Downloads/Mealse/. */
        EXTERNAL,
        /** Внутренняя files/downloads/ приложения. */
        INTERNAL
    }

    public interface DownloadCallback {
        void onStart(String fileName, long total);
        void onProgress(int percent);
        void onSuccess(File file);
        void onError(String error);
        void onCancel();
    }

    private final Context appContext;
    private final MealseLogger log;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean busy = new AtomicBoolean(false);

    private DownloadCallback callback;
    private HlsDownloader hlsDownloader;
    private volatile HttpURLConnection activeConn;

    public MAS_FileDownloadManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.log = MealseLogger.get(appContext, TAG);
    }

    public void setCallback(DownloadCallback cb) {
        this.callback = cb;
    }

    public boolean isBusy() {
        return busy.get();
    }

    public void cancel() {
        if (hlsDownloader != null) hlsDownloader.cancel();
        HttpURLConnection c = activeConn;
        if (c != null) {
            try { c.disconnect(); } catch (Throwable ignored) {}
        }
        busy.set(false);
        mainHandler.post(() -> { if (callback != null) callback.onCancel(); });
    }

    // ============================================================
    //  Основной вход
    // ============================================================

    public void download(String url, String fileName, Storage storage) {
        if (busy.getAndSet(true)) {
            mainHandler.post(() -> { if (callback != null) callback.onError("Уже идёт загрузка"); });
            return;
        }
        if (url == null || url.isEmpty()) {
            busy.set(false);
            mainHandler.post(() -> { if (callback != null) callback.onError("URL пуст"); });
            return;
        }
        if (fileName == null || fileName.isEmpty()) {
            fileName = "file_" + System.currentTimeMillis();
        }

        final String fUrl = url;
        final String fName = fileName;
        final Storage fStorage = storage;

        // 1. HLS
        if (isHls(fUrl)) {
            downloadHls(fUrl, fName);
            return;
        }
        // 2. data: URL
        if (fUrl.startsWith("data:")) {
            downloadDataUrl(fUrl, fName, fStorage);
            return;
        }
        // 3. Обычная ссылка
        if (fStorage == Storage.EXTERNAL) {
            downloadExternalWithManager(fUrl, fName);
        } else {
            downloadInternalStream(fUrl, fName);
        }
    }

    private static boolean isHls(String url) {
        String lower = url.toLowerCase();
        return lower.contains(".m3u8");
    }

    // ============================================================
    //  HLS
    // ============================================================

    private void downloadHls(String url, String fileName) {
        if (hlsDownloader == null) hlsDownloader = new HlsDownloader(appContext);
        hlsDownloader.setSourceName(stripExt(fileName));
        hlsDownloader.setCallback(new HlsDownloader.HlsCallback() {
            @Override public void onStart() {
                mainHandler.post(() -> {
                    if (callback != null) callback.onStart(fileName, -1);
                });
            }
            @Override public void onProgress(int percent) {
                mainHandler.post(() -> {
                    if (callback != null) callback.onProgress(percent);
                });
            }
            @Override public void onSuccess(File outputFile) {
                busy.set(false);
                mainHandler.post(() -> {
                    if (callback != null) callback.onSuccess(outputFile);
                });
            }
            @Override public void onError(String error) {
                busy.set(false);
                mainHandler.post(() -> {
                    if (callback != null) callback.onError(error);
                });
            }
        });
        hlsDownloader.downloadAndConvert(url, stripExt(fileName));
    }

    // ============================================================
    //  data: URL
    // ============================================================

    private void downloadDataUrl(String dataUrl, String fileName, Storage storage) {
        executor.execute(() -> {
            try {
                int comma = dataUrl.indexOf(',');
                if (comma < 0) throw new IOException("нет запятой в data: URL");
                String meta = dataUrl.substring(5, comma);
                boolean base64 = meta.endsWith(";base64");
                String payload = dataUrl.substring(comma + 1);

                byte[] bytes = base64
                        ? Base64.decode(payload, Base64.DEFAULT)
                        : payload.getBytes("UTF-8");

                File dest = pickDestination(fileName, storage);
                if (dest == null) throw new IOException("нет директории");

                try (OutputStream out = new BufferedOutputStream(
                        new FileOutputStream(dest), BUFFER_SIZE)) {
                    out.write(bytes);
                    out.flush();
                }

                busy.set(false);
                final File finalDest = dest;
                mainHandler.post(() -> {
                    if (callback != null) callback.onSuccess(finalDest);
                });

            } catch (Exception e) {
                busy.set(false);
                log.logError("downloadDataUrl", e);
                final String msg = e.getMessage();
                mainHandler.post(() -> {
                    if (callback != null) callback.onError("Ошибка: " + msg);
                });
            }
        });
    }

    // ============================================================
    //  Прямая HTTP — внешняя память через DownloadManager
    // ============================================================

    private void downloadExternalWithManager(String url, String fileName) {
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle(fileName);
            req.setDescription("Загрузка...");
            req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, "Mealse/" + fileName);
            req.setAllowedNetworkTypes(
                    DownloadManager.Request.NETWORK_WIFI
                            | DownloadManager.Request.NETWORK_MOBILE);
            req.addRequestHeader("User-Agent",
                    "Mozilla/5.0 (Linux; Android 12)");

            DownloadManager dm = (DownloadManager)
                    appContext.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) throw new IOException("DownloadManager недоступен");

            dm.enqueue(req);
            busy.set(false);
            mainHandler.post(() -> {
                if (callback != null) {
                    callback.onStart(fileName, -1);
                    // DownloadManager не даёт прогресс — сразу «успех» после enqueue
                    File dst = new File(
                            Environment.getExternalStoragePublicDirectory(
                                    Environment.DIRECTORY_DOWNLOADS),
                            "Mealse/" + fileName);
                    callback.onSuccess(dst);
                }
            });
        } catch (Exception e) {
            busy.set(false);
            log.logError("downloadExternalWithManager", e);
            final String msg = e.getMessage();
            mainHandler.post(() -> {
                if (callback != null) callback.onError("Ошибка: " + msg);
            });
        }
    }

    // ============================================================
    //  Прямая HTTP — внутренняя память (стрим с прогрессом)
    // ============================================================

    private void downloadInternalStream(String url, String fileName) {
        executor.execute(() -> {
            HttpURLConnection conn = null;
            InputStream in = null;
            OutputStream out = null;

            try {
                File dir = new File(appContext.getFilesDir(), "downloads");
                if (!dir.exists()) dir.mkdirs();
                File dest = new File(dir, fileName);

                URL u = new URL(url);
                conn = (HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12)");
                activeConn = conn;

                int code = conn.getResponseCode();
                if (code != 200) {
                    throw new IOException("HTTP " + code);
                }

                long total = conn.getContentLengthLong();
                postStart(fileName, total);

                in = new BufferedInputStream(conn.getInputStream(), BUFFER_SIZE);
                out = new BufferedOutputStream(new FileOutputStream(dest), BUFFER_SIZE);

                byte[] buf = new byte[BUFFER_SIZE];
                long done = 0;
                int r;
                long lastReport = 0;
                while ((r = in.read(buf)) != -1) {
                    out.write(buf, 0, r);
                    done += r;
                    long now = System.currentTimeMillis();
                    if (now - lastReport > 100 && total > 0) {
                        int p = (int) ((done * 100) / total);
                        postProgress(p);
                        lastReport = now;
                    }
                }
                out.flush();

                busy.set(false);
                final File finalDest = dest;
                mainHandler.post(() -> {
                    if (callback != null) callback.onSuccess(finalDest);
                });

            } catch (IOException e) {
                busy.set(false);
                log.logError("downloadInternalStream", e);
                final String msg = e.getMessage();
                mainHandler.post(() -> {
                    if (callback != null) callback.onError("Ошибка: " + msg);
                });
            } finally {
                activeConn = null;
                closeQuietly(in);
                closeQuietly(out);
                if (conn != null) conn.disconnect();
            }
        });
    }

    // ============================================================
    //  Вспомогательные
    // ============================================================

    private File pickDestination(String fileName, Storage storage) {
        File dir;
        if (storage == Storage.EXTERNAL) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Для DownloadManager-совместимого места — вернём путь,
                // но запишем через MediaStore; здесь упрощённо в public dir.
                dir = new File(
                        Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS),
                        "Mealse");
            } else {
                dir = new File(
                        Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS),
                        "Mealse");
            }
        } else {
            dir = new File(appContext.getFilesDir(), "downloads");
        }
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, fileName);
    }

    private void postStart(String fileName, long total) {
        mainHandler.post(() -> {
            if (callback != null) callback.onStart(fileName, total);
        });
    }

    private void postProgress(int percent) {
        mainHandler.post(() -> {
            if (callback != null) callback.onProgress(percent);
        });
    }

    private static String stripExt(String name) {
        if (name == null) return "file";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (Throwable ignored) {}
    }

    // ============================================================
    //  Lifecycle
    // ============================================================

    public void cleanup() {
        cancel();
        try { executor.shutdownNow(); } catch (Throwable ignored) {}
        hlsDownloader = null;
    }
}