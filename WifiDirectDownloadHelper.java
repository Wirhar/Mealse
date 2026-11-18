package com.my.mealse.browser.download;

import android.content.Context;
import android.net.Uri;
import android.webkit.URLUtil;

import com.my.mealse.filemanager.FileTransport;
import com.my.mealse.filemanager.FileTransportException;
import com.my.mealse.filemanager.util.MAS_FileDownloadManager;
import com.my.mealse.filemanager.wifidirect.WifiDirectFileTransport;
import com.my.mealse.model.TrustedNode;
import com.my.mealse.util.MealseLogger;
import com.my.mealse.util.WifiDirectManager;

import java.io.File;

/**
 * Скачивание через Wi-Fi Direct.
 *
 * Когда использовать:
 *  - Пир подключён (WifiDirectManager.isConnected()).
 *  - Файл находится в виртуальном корне пира (путь начинается с "/"):
 *      "/downloads/file.pdf", "/videos/movie.mp4" и т.д.
 *  - URL в браузере — это либо "mealse-fs://<path>", либо http(s) ссылка,
 *    которую пользователь явно выбрал «Скачать с пира».
 *
 * В отличие от обычного HTTP через WifiDirectFileTransport нельзя скачать
 * произвольный URL из интернета — только то, что уже есть на пире.
 */
public class WifiDirectDownloadHelper {

    private static final String TAG = "WifiDirectDownloadHelper";

    private final Context appContext;
    private final MealseLogger log;
    private final WifiDirectManager wdm;

    public WifiDirectDownloadHelper(Context context) {
        this.appContext = context.getApplicationContext();
        this.log = MealseLogger.get(appContext, TAG);
        this.wdm = ((com.my.mealse.MealseApp) appContext).getWifiDirectManager();
    }

    /**
     * Проверяет, можно ли скачать через Wi-Fi Direct:
     *  - пир подключён
     *  - URL начинается с "mealse-fs://"
     */
    public boolean canDownloadViaWifiDirect(String url) {
        if (url == null) return false;
        if (wdm == null || !wdm.isConnected()) return false;
        return url.startsWith("mealse-fs://");
    }

    /**
     * Скачивает файл через Wi-Fi Direct.
     *
     * @param mealseFsUrl строка вида "mealse-fs:///downloads/file.pdf"
     * @param storage     INTERNAL или EXTERNAL (для сохранения результата)
     * @param callback    результат
     */
    public void download(String mealseFsUrl, MAS_FileDownloadManager.Storage storage,
                         DownloadCallback callback) {
        if (mealseFsUrl == null || !mealseFsUrl.startsWith("mealse-fs://")) {
            if (callback != null) callback.onError("URL не начинается с mealse-fs://");
            return;
        }
        if (wdm == null || !wdm.isConnected()) {
            if (callback != null) callback.onError("Wi-Fi Direct не подключён");
            return;
        }

        String virtualPath = mealseFsUrl.substring("mealse-fs://".length());
        if (virtualPath.isEmpty()) virtualPath = "/";
        if (!virtualPath.startsWith("/")) virtualPath = "/" + virtualPath;

        String fileName = URLUtil.guessFileName(virtualPath, null, null);
        if (fileName == null || fileName.isEmpty()) {
            fileName = "wifidirect_" + System.currentTimeMillis();
        }

        // Куда сохранять — во внутреннее хранилище приложения/downloads
        File downloadsDir = new File(appContext.getFilesDir(), "downloads");
        if (!downloadsDir.exists()) downloadsDir.mkdirs();
        final File destFile = new File(downloadsDir, fileName);

        log.log("Wi-Fi Direct download: " + virtualPath + " → " + destFile.getAbsolutePath());

        final TrustedNode remoteNode = buildPseudoNode();

        try {
            WifiDirectFileTransport transport =
                    new WifiDirectFileTransport(appContext, remoteNode);

            if (callback != null) callback.onStart(fileName);

            transport.download(virtualPath, destFile, new FileTransport.TransferCallback() {
                @Override public void onStart(long total) {}

                @Override public void onProgress(long done, long total) {
                    if (callback != null && total > 0) {
                        callback.onProgress((int) (done * 100 / total));
                    }
                }

                @Override public void onSuccess(File f) {
                    log.log("Wi-Fi Direct download OK: " + f.getAbsolutePath());
                    if (callback != null) callback.onSuccess(f);
                }

                @Override public void onError(FileTransportException e) {
                    log.warn("Wi-Fi Direct download FAILED: " + e.getMessage());
                    if (callback != null) callback.onError(e.getMessage());
                }

                @Override public void onCancelled() {
                    if (callback != null) callback.onCancel();
                }
            });
        } catch (Throwable t) {
            log.logError("WifiDirectDownloadHelper", t);
            if (callback != null) callback.onError(t.getMessage());
        }
    }

    // ============ Callback ============

    public interface DownloadCallback {
        void onStart(String fileName);
        void onProgress(int percent);
        void onSuccess(File file);
        void onError(String error);
        void onCancel();
    }

    // ============ Утилиты ============

    private TrustedNode buildPseudoNode() {
        // Для WifiDirectFileTransport нужен TrustedNode.
        // Берём данные remotePeer + пустыми ip/port — транспорт сам знает, что говорит по WD.
        WifiDirectManager.Peer rp = wdm.getRemotePeer();
        String id = "wd_" + (rp != null && rp.deviceAddress != null ? rp.deviceAddress : "peer");
        String name = (rp != null && rp.deviceName != null) ? rp.deviceName : "Wi-Fi Direct пир";
        TrustedNode n = new TrustedNode(id, name, "", 0, "", "");
        n.setMode(TrustedNode.Mode.WIFI_DIRECT);
        return n;
    }
}