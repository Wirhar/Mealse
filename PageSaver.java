package com.my.mealse.browser.download;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import com.my.mealse.util.MealseLogger;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Сохранение страниц в MHTML через WebView.saveWebArchive().
 *
 * Файл кладётся в getFilesDir()/downloads/<host>_<timestamp>.mhtml
 * Открывается обратно через WebView.loadUrl("file://...").
 */
public class PageSaver {

    private static final String TAG = "PageSaver";

    private final Context appContext;
    private final MealseLogger log;
    private final WebView webView;
    private final Handler ui = new Handler(Looper.getMainLooper());

    public PageSaver(Context context, WebView webView) {
        this.appContext = context.getApplicationContext();
        this.log = MealseLogger.get(appContext, TAG);
        this.webView = webView;
    }

    public interface Callback {
        void onStart(String fileName);
        void onSuccess(File file);
        void onError(String error);
    }

    /**
     * Сохраняет текущую страницу как MHTML.
     *
     * @param pageUrl текущий URL (для формирования имени файла)
     * @param title   заголовок страницы
     * @param cb      результат
     */
    public void saveCurrentPage(String pageUrl, String title, Callback cb) {
        if (webView == null) {
            if (cb != null) cb.onError("WebView == null");
            return;
        }
        if (pageUrl == null || pageUrl.isEmpty()) {
            if (cb != null) cb.onError("URL пуст");
            return;
        }
        if (pageUrl.startsWith("about:") || pageUrl.startsWith("chrome:")
                || pageUrl.startsWith("file:")) {
            if (cb != null) cb.onError("Системную страницу нельзя сохранить");
            return;
        }

        String safeName = buildFileName(pageUrl, title);

        // Директория downloads
        File dir = new File(appContext.getFilesDir(), "downloads");
        if (!dir.exists()) dir.mkdirs();
        final File dest = new File(dir, safeName);

        if (cb != null) cb.onStart(safeName);

        try {
            webView.saveWebArchive(dest.getAbsolutePath(), false, new android.webkit.ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    ui.post(() -> {
                        if (value == null || value.equals("null")) {
                            log.warn("saveWebArchive вернул null для " + pageUrl);
                            if (cb != null) cb.onError("Не удалось сохранить");
                            return;
                        }
                        File f = new File(value);
                        if (f.exists() && f.length() > 0) {
                            log.log("MHTML сохранён: " + f.getAbsolutePath()
                                    + " (" + f.length() + " B)");
                            if (cb != null) cb.onSuccess(f);
                        } else {
                            log.warn("saveWebArchive вернул путь, но файла нет: " + value);
                            if (cb != null) cb.onError("Файл не создан");
                        }
                    });
                }
            });
        } catch (Throwable t) {
            log.logError("saveCurrentPage", t);
            if (cb != null) cb.onError(t.getMessage());
        }
    }

    private String buildFileName(String pageUrl, String title) {
        String host;
        try {
            android.net.Uri uri = android.net.Uri.parse(pageUrl);
            host = uri.getHost();
            if (host == null) host = "page";
            host = host.replaceFirst("^www\\.", "");
        } catch (Throwable t) {
            host = "page";
        }
        host = host.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (host.length() > 40) host = host.substring(0, 40);

        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date());

        String titlePart = "";
        if (title != null && !title.isEmpty()) {
            String clean = title.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_").trim();
            if (clean.length() > 40) clean = clean.substring(0, 40);
            if (!clean.isEmpty()) titlePart = "_" + clean;
        }

        return host + titlePart + "_" + ts + ".mhtml";
    }
}