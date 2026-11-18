package com.my.mealse.browser.history;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.my.mealse.util.MealseLogger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * История браузера.
 *
 *  ★ Модель записи:
 *   {
 *     id: UUID,
 *     title: "Заголовок",
 *     url:   "https://...",
 *     timestamp: 1234567890
 *   }
 *
 *  ★ Хранение:
 *   getFilesDir()/browser/history.json         — активный список (макс 1000)
 *   getFilesDir()/browser/history_archive/     — старые записи по 500 шт
 *
 *  ★ Логика:
 *   - При добавлении, если записей > 500 — самые старые 500 шт
 *     переносятся в history_archive/history_<ts>.json и удаляются из активного.
 *   - В активном хранится до 1000 (в реальности — 500-999 после архивации).
 */
public class HistoryHelper {

    private static final String TAG = "HistoryHelper";

    private static final String DIR_BROWSER = "browser";
    private static final String FILE_HISTORY = "history.json";
    private static final String DIR_ARCHIVE = "history_archive";

    private static final int MAX_ACTIVE = 1000;
    private static final int ARCHIVE_THRESHOLD = 500;

    private final Context appContext;
    private final MealseLogger log;
    private final Gson gson;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    public HistoryHelper(Context context) {
        this.appContext = context.getApplicationContext();
        this.log = MealseLogger.get(appContext, TAG);
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        ensureDirs();
    }

    // ============ Модель ============

    public static class Entry {
        public String id;
        public String title;
        public String url;
        public long timestamp;

        public Entry() {}

        public Entry(String title, String url) {
            this.id = java.util.UUID.randomUUID().toString();
            this.title = title;
            this.url = url;
            this.timestamp = System.currentTimeMillis();
        }

        public String getShortTitle() {
            if (title != null && !title.isEmpty() && !title.equals(url)) {
                if (title.length() > 60) return title.substring(0, 57) + "...";
                return title;
            }
            // Если title пустой или равен URL — выводим короткое имя домена
            String d = extractDomain(url);
            return d.isEmpty() ? url : d;
        }

        public String getShortUrl() {
            if (url == null) return "";
            if (url.length() > 60) return url.substring(0, 57) + "...";
            return url;
        }

        private static String extractDomain(String u) {
            try {
                android.net.Uri uri = android.net.Uri.parse(u);
                String host = uri.getHost();
                if (host == null) return "";
                return host.replaceFirst("^www\\.", "");
            } catch (Throwable t) {
                return "";
            }
        }
    }

    // ============ Callback ============

    public interface LoadCallback {
        void onLoaded(List<Entry> entries);
        void onError(String error);
    }

    public interface SaveCallback {
        void onSaved(int totalCount);
        void onError(String error);
    }

    // ============ Публичный API ============

    /** Асинхронно добавляет запись. */
    public void addEntry(String title, String url, SaveCallback callback) {
        if (url == null || url.isEmpty()) {
            if (callback != null) callback.onError("URL пуст");
            return;
        }
        executor.execute(() -> {
            try {
                List<Entry> list = loadSync();

                // Удаляем дубликат по URL (перемещаем в начало)
                for (int i = 0; i < list.size(); i++) {
                    if (url.equals(list.get(i).url)) {
                        list.remove(i);
                        break;
                    }
                }

                Entry e = new Entry(title, url);
                list.add(0, e);

                // Архивация, если больше ARCHIVE_THRESHOLD
                if (list.size() > ARCHIVE_THRESHOLD) {
                    archiveOldSync(list);
                }

                saveSync(list);
                final int total = list.size();
                ui.post(() -> {
                    if (callback != null) callback.onSaved(total);
                });
            } catch (Exception ex) {
                log.logError("addEntry", ex);
                ui.post(() -> {
                    if (callback != null) callback.onError(ex.getMessage());
                });
            }
        });
    }

    /** Асинхронно загружает активную историю (новые сверху). */
    public void loadAll(LoadCallback callback) {
        executor.execute(() -> {
            try {
                List<Entry> list = loadSync();
                ui.post(() -> callback.onLoaded(list));
            } catch (Exception ex) {
                log.logError("loadAll", ex);
                ui.post(() -> callback.onError(ex.getMessage()));
            }
        });
    }

    /** Синхронно — для отладки. Не вызывать в UI-потоке. */
    public List<Entry> loadSync() {
        File f = new File(getBrowserDir(), FILE_HISTORY);
        if (!f.exists()) return new ArrayList<>();
        try (FileReader r = new FileReader(f)) {
            Type t = new TypeToken<List<Entry>>() {}.getType();
            List<Entry> list = gson.fromJson(r, t);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            log.logError("loadSync", e);
            return new ArrayList<>();
        }
    }

    /** Удалить одну запись по id. */
    public void deleteEntry(String id, SaveCallback callback) {
        if (id == null) {
            if (callback != null) callback.onError("id пуст");
            return;
        }
        executor.execute(() -> {
            try {
                List<Entry> list = loadSync();
                int before = list.size();
                for (int i = 0; i < list.size(); i++) {
                    if (id.equals(list.get(i).id)) {
                        list.remove(i);
                        break;
                    }
                }
                saveSync(list);
                final int total = list.size();
                log.log("deleteEntry: " + (before - total) + " удалено");
                ui.post(() -> { if (callback != null) callback.onSaved(total); });
            } catch (Exception ex) {
                log.logError("deleteEntry", ex);
                ui.post(() -> { if (callback != null) callback.onError(ex.getMessage()); });
            }
        });
    }

    /** Полностью очистить активную историю (архив остаётся). */
    public void clearAll(SaveCallback callback) {
        executor.execute(() -> {
            try {
                saveSync(new ArrayList<>());
                ui.post(() -> { if (callback != null) callback.onSaved(0); });
            } catch (Exception ex) {
                ui.post(() -> { if (callback != null) callback.onError(ex.getMessage()); });
            }
        });
    }

    // ============ Внутренняя логика ============

    private void archiveOldSync(List<Entry> list) {
        if (list.size() <= ARCHIVE_THRESHOLD) return;

        // Берём ARCHIVE_THRESHOLD самых старых (в конце списка)
        int archiveCount = ARCHIVE_THRESHOLD;
        List<Entry> toArchive = new ArrayList<>(archiveCount);
        for (int i = list.size() - archiveCount; i < list.size(); i++) {
            toArchive.add(list.get(i));
        }

        // Удаляем их из активного
        list.subList(list.size() - archiveCount, list.size()).clear();

        // Сохраняем в архив
        try {
            File dir = getArchiveDir();
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                    .format(new Date());
            File f = new File(dir, "history_" + ts + ".json");
            try (FileWriter w = new FileWriter(f)) {
                gson.toJson(toArchive, w);
            }
            log.log("archiveOldSync: " + toArchive.size() + " → " + f.getName());
        } catch (Exception e) {
            log.logError("archiveOldSync", e);
        }
    }

    private void saveSync(List<Entry> list) {
        // Защита: не более MAX_ACTIVE
        while (list.size() > MAX_ACTIVE) {
            list.remove(list.size() - 1);
        }
        File f = new File(getBrowserDir(), FILE_HISTORY);
        try (FileWriter w = new FileWriter(f)) {
            gson.toJson(list, w);
        } catch (Exception e) {
            log.logError("saveSync", e);
        }
    }

    private File getBrowserDir() {
        File dir = new File(appContext.getFilesDir(), DIR_BROWSER);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private File getArchiveDir() {
        File dir = new File(getBrowserDir(), DIR_ARCHIVE);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private void ensureDirs() {
        getBrowserDir();
        getArchiveDir();
    }
}