package com.my.mealse.browser.download;

import android.content.Context;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.my.mealse.filemanager.util.MAS_FileDownloadManager;
import com.my.mealse.util.MealseLogger;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Скачивание blob: URL.
 *
 * WebView не даёт телу blob-ответа через DownloadListener, поэтому:
 *  - Инжектим JS, читающий blob через FileReader.readAsDataURL()
 *  - JS возвращает data URL через @JavascriptInterface
 *  - Декодируем base64 → пишем файл
 *
 * Лимит: 50 МБ (иначе WebView падает по OOM при чтении целиком).
 */
public class BlobDownloadHelper {

    private static final String TAG = "BlobDownloadHelper";
    private static final long MAX_BLOB_SIZE = 50L * 1024 * 1024;

    private final Context appContext;
    private final MealseLogger log;
    private final WebView webView;

    public BlobDownloadHelper(Context context, WebView webView) {
        this.appContext = context.getApplicationContext();
        this.log = MealseLogger.get(appContext, TAG);
        this.webView = webView;
    }

    public interface BlobCallback {
        void onStart(String fileName);
        void onSuccess(File file);
        void onError(String error);
    }

    private BlobCallback currentCallback;
    private String currentFileName;

    /**
     * Инициирует скачивание blob URL.
     *
     * @param blobUrl  "blob:https://example.com/uuid"
     * @param suggestedName желаемое имя файла (или null)
     * @param callback результат
     */
    public void download(String blobUrl, String suggestedName, BlobCallback callback) {
        if (blobUrl == null || !blobUrl.startsWith("blob:")) {
            if (callback != null) callback.onError("Не blob URL");
            return;
        }
        if (webView == null) {
            if (callback != null) callback.onError("WebView == null");
            return;
        }

        this.currentCallback = callback;
        this.currentFileName = (suggestedName == null || suggestedName.isEmpty())
                ? "blob_" + System.currentTimeMillis() + ".bin"
                : suggestedName;

        if (callback != null) callback.onStart(currentFileName);

        // Регистрируем JS-мост
        webView.addJavascriptInterface(new Bridge(), "MealseBlobBridge");

        String js =
                "(function(){" +
                        "  var url = " + jsString(blobUrl) + ";" +
                        "  fetch(url).then(function(r){" +
                        "    if (!r.ok) throw new Error('fetch blob failed: ' + r.status);" +
                        "    return r.blob();" +
                        "  }).then(function(blob){" +
                        "    if (blob.size > " + MAX_BLOB_SIZE + ") {" +
                        "      MealseBlobBridge.onError('Blob слишком большой: ' + blob.size);" +
                        "      return;" +
                        "    }" +
                        "    var reader = new FileReader();" +
                        "    reader.onloadend = function(){" +
                        "      MealseBlobBridge.onDataUrl(reader.result, blob.type || '');" +
                        "    };" +
                        "    reader.onerror = function(e){ MealseBlobBridge.onError('FileReader error'); };" +
                        "    reader.readAsDataURL(blob);" +
                        "  }).catch(function(e){" +
                        "    MealseBlobBridge.onError(String(e));" +
                        "  });" +
                        "})();";

        webView.post(() -> {
            try {
                webView.evaluateJavascript(js, null);
            } catch (Throwable t) {
                log.logError("inject blob js", t);
                if (callback != null) callback.onError(t.getMessage());
            }
        });
    }

    // ============ JS-мост ============

    public class Bridge {

        @JavascriptInterface
        public void onDataUrl(String dataUrl, String mimeType) {
            log.log("Blob: data URL получен (mime=" + mimeType + ", len=" +
                    (dataUrl != null ? dataUrl.length() : 0) + ")");

            if (dataUrl == null || !dataUrl.startsWith("data:")) {
                if (currentCallback != null) currentCallback.onError("Некорректный data URL");
                return;
            }
            int comma = dataUrl.indexOf(',');
            if (comma < 0) {
                if (currentCallback != null) currentCallback.onError("Нет base64 в data URL");
                return;
            }
            try {
                String base64 = dataUrl.substring(comma + 1);
                byte[] bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);

                String safeName = currentFileName;
                if (safeName == null || safeName.isEmpty()) {
                    safeName = "blob_" + System.currentTimeMillis();
                    if (mimeType != null) {
                        String ext = android.webkit.MimeTypeMap.getSingleton()
                                .getExtensionFromMimeType(mimeType);
                        if (ext != null) safeName += "." + ext;
                    }
                }
                // Защита от недопустимых символов
                safeName = safeName.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_");

                File dir = new File(appContext.getFilesDir(), "downloads");
                if (!dir.exists()) dir.mkdirs();
                File dest = new File(dir, safeName);
                if (dest.exists()) {
                    String base = safeName;
                    String ext = "";
                    int dot = safeName.lastIndexOf('.');
                    if (dot > 0) { base = safeName.substring(0, dot); ext = safeName.substring(dot); }
                    for (int i = 1; i < 1000; i++) {
                        dest = new File(dir, base + " (" + i + ")" + ext);
                        if (!dest.exists()) break;
                    }
                }

                try (FileOutputStream fos = new FileOutputStream(dest)) {
                    fos.write(bytes);
                }

                log.log("Blob saved: " + dest.getAbsolutePath() + " (" + bytes.length + " B)");
                if (currentCallback != null) currentCallback.onSuccess(dest);
            } catch (Throwable t) {
                log.logError("blob save", t);
                if (currentCallback != null) currentCallback.onError(t.getMessage());
            }
        }

        @JavascriptInterface
        public void onError(String error) {
            log.warn("Blob error: " + error);
            if (currentCallback != null) currentCallback.onError(error);
        }
    }

    private static String jsString(String s) {
        if (s == null) return "''";
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}