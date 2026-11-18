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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Хранилище закладок.
 *  getFilesDir()/browser/bookmarks.json
 *
 *  Асинхронные операции.
 */
public class BookmarksHelper {

    private static final String TAG = "BookmarksHelper";
    private static final String DIR_BROWSER = "browser";
    private static final String FILE_BOOKMARKS = "bookmarks.json";

    private final Context appContext;
    private final MealseLogger log;
    private final Gson gson;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    public BookmarksHelper(Context context) {
        this.appContext = context.getApplicationContext();
        this.log = MealseLogger.get(appContext, TAG);
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        getBrowserDir();
    }

    public interface LoadCallback {
        void onLoaded(List<BookmarkItem> items);
        void onError(String error);
    }

    public interface SaveCallback {
        void onSaved(int total);
        void onError(String error);
    }

    /** Асинхронно добавляет закладку. */
    public void add(String title, String url, SaveCallback callback) {
        if (url == null || url.isEmpty()) {
            if (callback != null) callback.onError("URL пуст");
            return;
        }
        executor.execute(() -> {
            try {
                List<BookmarkItem> list = loadSync();

                // Если уже есть — не дублируем
                for (BookmarkItem b : list) {
                    if (url.equals(b.url)) {
                        final int total = list.size();
                        ui.post(() -> { if (callback != null) callback.onSaved(total); });
                        return;
                    }
                }

                list.add(0, new BookmarkItem(title, url));
                saveSync(list);
                final int total = list.size();
                log.log("add: " + url);
                ui.post(() -> { if (callback != null) callback.onSaved(total); });
            } catch (Exception e) {
                log.logError("add", e);
                ui.post(() -> { if (callback != null) callback.onError(e.getMessage()); });
            }
        });
    }

    /** Асинхронно загружает все закладки. */
    public void loadAll(LoadCallback callback) {
        executor.execute(() -> {
            try {
                List<BookmarkItem> list = loadSync();
                ui.post(() -> callback.onLoaded(list));
            } catch (Exception e) {
                log.logError("loadAll", e);
                ui.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    /** Удаляет закладку по id. */
    public void delete(String id, SaveCallback callback) {
        if (id == null) {
            if (callback != null) callback.onError("id пуст");
            return;
        }
        executor.execute(() -> {
            try {
                List<BookmarkItem> list = loadSync();
                for (int i = 0; i < list.size(); i++) {
                    if (id.equals(list.get(i).id)) {
                        list.remove(i);
                        break;
                    }
                }
                saveSync(list);
                final int total = list.size();
                ui.post(() -> { if (callback != null) callback.onSaved(total); });
            } catch (Exception e) {
                log.logError("delete", e);
                ui.post(() -> { if (callback != null) callback.onError(e.getMessage()); });
            }
        });
    }

    /** Удаляет по URL (если есть). */
    public void deleteByUrl(String url, SaveCallback callback) {
        if (url == null) {
            if (callback != null) callback.onError("url пуст");
            return;
        }
        executor.execute(() -> {
            try {
                List<BookmarkItem> list = loadSync();
                for (int i = 0; i < list.size(); i++) {
                    if (url.equals(list.get(i).url)) {
                        list.remove(i);
                        break;
                    }
                }
                saveSync(list);
                final int total = list.size();
                ui.post(() -> { if (callback != null) callback.onSaved(total); });
            } catch (Exception e) {
                log.logError("deleteByUrl", e);
                ui.post(() -> { if (callback != null) callback.onError(e.getMessage()); });
            }
        });
    }

    /** Проверяет, есть ли URL в закладках. */
    public void containsUrl(String url, ContainsCallback cb) {
        executor.execute(() -> {
            try {
                List<BookmarkItem> list = loadSync();
                boolean found = false;
                for (BookmarkItem b : list) {
                    if (url != null && url.equals(b.url)) {
                        found = true;
                        break;
                    }
                }
                final boolean f = found;
                ui.post(() -> cb.onResult(f));
            } catch (Exception e) {
                ui.post(() -> cb.onResult(false));
            }
        });
    }

    public interface ContainsCallback {
        void onResult(boolean contains);
    }

    // ============ Sync ============

    private List<BookmarkItem> loadSync() {
        File f = new File(getBrowserDir(), FILE_BOOKMARKS);
        if (!f.exists()) return new ArrayList<>();
        try (FileReader r = new FileReader(f)) {
            Type t = new TypeToken<List<BookmarkItem>>() {}.getType();
            List<BookmarkItem> list = gson.fromJson(r, t);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            log.logError("loadSync", e);
            return new ArrayList<>();
        }
    }

    private void saveSync(List<BookmarkItem> list) {
        File f = new File(getBrowserDir(), FILE_BOOKMARKS);
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
}