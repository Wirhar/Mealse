package com.my.mealse.browser.password;

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
 * Хранилище паролей.
 *  getFilesDir()/browser/passwords.json
 *
 *  ⚠ Шлюзу доверяем без шифрования (по требованию).
 *  Пароли хранятся в plaintext JSON.
 *  В будущем — шифрование через SecureAuthManager.
 */
public class PasswordsHelper {

    private static final String TAG = "PasswordsHelper";
    private static final String DIR_BROWSER = "browser";
    private static final String FILE_PASSWORDS = "passwords.json";

    private final Context appContext;
    private final MealseLogger log;
    private final Gson gson;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    public PasswordsHelper(Context context) {
        this.appContext = context.getApplicationContext();
        this.log = MealseLogger.get(appContext, TAG);
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        getBrowserDir();
    }

    public interface LoadCallback {
        void onLoaded(List<PasswordItem> items);
        void onError(String error);
    }

    public interface SaveCallback {
        void onSaved(PasswordItem item);
        void onError(String error);
    }

    public interface DeleteCallback {
        void onDeleted(boolean success);
        void onError(String error);
    }

    /** Добавляет или обновляет пароль. */
    public void save(PasswordItem item, SaveCallback callback) {
        if (item == null || item.login == null || item.login.isEmpty()
                || item.password == null || item.password.isEmpty()) {
            if (callback != null) callback.onError("Логин и пароль обязательны");
            return;
        }
        executor.execute(() -> {
            try {
                List<PasswordItem> list = loadSync();
                boolean found = false;
                for (int i = 0; i < list.size(); i++) {
                    if (item.id != null && item.id.equals(list.get(i).id)) {
                        item.updatedAt = System.currentTimeMillis();
                        list.set(i, item);
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    if (item.id == null || item.id.isEmpty()) {
                        item.id = java.util.UUID.randomUUID().toString();
                    }
                    item.createdAt = System.currentTimeMillis();
                    item.updatedAt = item.createdAt;
                    list.add(0, item);
                }
                saveSync(list);
                log.log("save: " + item.login + " (" + item.getDisplayTitle() + ")");
                final PasswordItem finalItem = item;
                ui.post(() -> { if (callback != null) callback.onSaved(finalItem); });
            } catch (Exception e) {
                log.logError("save", e);
                ui.post(() -> { if (callback != null) callback.onError(e.getMessage()); });
            }
        });
    }

    public void loadAll(LoadCallback callback) {
        executor.execute(() -> {
            try {
                List<PasswordItem> list = loadSync();
                ui.post(() -> callback.onLoaded(list));
            } catch (Exception e) {
                log.logError("loadAll", e);
                ui.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public void delete(String id, DeleteCallback callback) {
        if (id == null) {
            if (callback != null) callback.onError("id пуст");
            return;
        }
        executor.execute(() -> {
            try {
                List<PasswordItem> list = loadSync();
                int before = list.size();
                for (int i = 0; i < list.size(); i++) {
                    if (id.equals(list.get(i).id)) {
                        list.remove(i);
                        break;
                    }
                }
                saveSync(list);
                final boolean removed = list.size() < before;
                ui.post(() -> { if (callback != null) callback.onDeleted(removed); });
            } catch (Exception e) {
                log.logError("delete", e);
                ui.post(() -> { if (callback != null) callback.onError(e.getMessage()); });
            }
        });
    }

    private List<PasswordItem> loadSync() {
        File f = new File(getBrowserDir(), FILE_PASSWORDS);
        if (!f.exists()) return new ArrayList<>();
        try (FileReader r = new FileReader(f)) {
            Type t = new TypeToken<List<PasswordItem>>() {}.getType();
            List<PasswordItem> list = gson.fromJson(r, t);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            log.logError("loadSync", e);
            return new ArrayList<>();
        }
    }

    private void saveSync(List<PasswordItem> list) {
        File f = new File(getBrowserDir(), FILE_PASSWORDS);
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