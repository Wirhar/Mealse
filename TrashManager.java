package com.my.mealse.filemanager.util;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.my.mealse.util.MealseLogger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * TrashManager — корзина для файлового менеджера Mealse.
 *
 * Логика:
 *   - Удаляемый файл/папка переезжает в getFilesDir()/Trash/ под
 *     уникальным именем (timestamp_originalname).
 *   - Метаданные сохраняются в trash_metadata.json: оригинальный путь,
 *     имя, время удаления, был ли это каталог.
 *   - При cleanupOldFiles() удаляются записи старше 10 дней.
 *   - Восстановление — обратный rename, при конфликте добавляем
 *     "_restored" к имени.
 *
 * Поддерживает перемещение из любой точки ФС, к которой у приложения
 * есть доступ (для внешней памяти — благодаря MANAGE_EXTERNAL_STORAGE).
 *
 * Единый экземпляр на приложение (singleton).
 */
public class TrashManager {

    private static final String TAG = "TrashManager";
    private static final String TRASH_DIR_NAME = "Trash";
    private static final String METADATA_FILE = "trash_metadata.json";
    private static final long AUTO_DELETE_DAYS = 10;
    private static final long AUTO_DELETE_MS = TimeUnit.DAYS.toMillis(AUTO_DELETE_DAYS);

    private static volatile TrashManager instance;

    private final Context appContext;
    private final File trashDir;
    private final File metadataFile;
    private final Gson gson;
    private final MealseLogger log;

    // ============================================================
    //  Модель
    // ============================================================

    public static class TrashItem {
        public String originalPath;
        public String trashFileName;
        public long deletionTime;
        public boolean isDirectory;
        public String originalName;

        public TrashItem() {}

        public TrashItem(String originalPath, String trashFileName,
                         boolean isDirectory, String originalName) {
            this.originalPath = originalPath;
            this.trashFileName = trashFileName;
            this.deletionTime = System.currentTimeMillis();
            this.isDirectory = isDirectory;
            this.originalName = originalName;
        }
    }

    // ============================================================
    //  Singleton
    // ============================================================

    private TrashManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.log = MealseLogger.get(appContext, TAG);
        this.trashDir = new File(appContext.getFilesDir(), TRASH_DIR_NAME);
        this.metadataFile = new File(trashDir, METADATA_FILE);
        ensureTrashDirExists();
        log.log("TrashManager создан, dir=" + trashDir.getAbsolutePath());
    }

    public static TrashManager get(Context context) {
        if (instance == null) {
            synchronized (TrashManager.class) {
                if (instance == null) instance = new TrashManager(context);
            }
        }
        return instance;
    }

    // ============================================================
    //  Основные методы
    // ============================================================

    /**
     * Перемещает файл/папку в корзину.
     *
     * @return true если успешно
     */
    public boolean moveToTrash(File source) {
        if (source == null || !source.exists()) {
            log.warn("moveToTrash: source не существует: " + source);
            return false;
        }

        // Если источник уже внутри корзины — не трогаем
        if (isInsideTrash(source)) {
            log.warn("moveToTrash: файл уже в корзине: " + source);
            return false;
        }

        ensureTrashDirExists();

        String uniqueName = System.currentTimeMillis() + "_" + source.getName();
        File dest = new File(trashDir, uniqueName);

        boolean moved = source.renameTo(dest);
        if (!moved) {
            // Fallback: скопировать и удалить (cross-device)
            moved = copyRecursive(source, dest);
            if (moved) {
                deleteRecursive(source);
            } else {
                log.logError("moveToTrash: rename+copy failed для " + source, null);
                return false;
            }
        }

        TrashItem item = new TrashItem(
                source.getAbsolutePath(),
                uniqueName,
                source.isDirectory(),
                source.getName());

        List<TrashItem> items = loadMetadata();
        items.add(item);
        saveMetadata(items);

        log.log("moveToTrash: " + source.getAbsolutePath() + " → " + dest.getName());
        return true;
    }

    /**
     * Восстанавливает файл из корзины.
     */
    public boolean restoreFromTrash(String trashFileName) {
        File trashFile = new File(trashDir, trashFileName);
        if (!trashFile.exists()) {
            log.warn("restoreFromTrash: файл не найден: " + trashFileName);
            return false;
        }

        List<TrashItem> items = loadMetadata();
        TrashItem target = null;
        for (TrashItem item : items) {
            if (item.trashFileName.equals(trashFileName)) {
                target = item;
                break;
            }
        }
        if (target == null) {
            log.warn("restoreFromTrash: нет метаданных для " + trashFileName);
            return false;
        }

        File original = new File(target.originalPath);
        if (original.exists()) {
            // Конфликт: имя занято, добавим "_restored"
            String parent = original.getParent();
            String name = original.getName();
            int dot = name.lastIndexOf('.');
            String base = dot > 0 ? name.substring(0, dot) : name;
            String ext = dot > 0 ? name.substring(dot) : "";
            int i = 1;
            do {
                original = new File(parent, base + "_restored" + (i > 1 ? "_" + i : "") + ext);
                i++;
            } while (original.exists() && i < 1000);
            log.log("restoreFromTrash: конфликт, восстановим как " + original.getName());
        }

        // Гарантируем, что родительская папка существует
        File parent = original.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        boolean restored = trashFile.renameTo(original);
        if (!restored) {
            restored = copyRecursive(trashFile, original);
            if (restored) deleteRecursive(trashFile);
        }

        if (restored) {
            items.remove(target);
            saveMetadata(items);
            log.log("restoreFromTrash: " + trashFileName + " → " + original.getAbsolutePath());
        } else {
            log.logError("restoreFromTrash failed: " + trashFileName, null);
        }
        return restored;
    }

    /**
     * Удаляет файл из корзины навсегда.
     */
    public boolean deletePermanently(String trashFileName) {
        File trashFile = new File(trashDir, trashFileName);
        boolean deleted = deleteRecursive(trashFile);
        if (deleted) {
            List<TrashItem> items = loadMetadata();
            items.removeIf(item -> item.trashFileName.equals(trashFileName));
            saveMetadata(items);
            log.log("deletePermanently: " + trashFileName);
        } else {
            log.logError("deletePermanently failed: " + trashFileName, null);
        }
        return deleted;
    }

    /**
     * Удаляет все элементы корзины старше AUTO_DELETE_DAYS.
     * Вызывать при старте FileManagerActivity или периодически.
     */
    public void cleanupOldFiles() {
        List<TrashItem> items = loadMetadata();
        long now = System.currentTimeMillis();
        List<String> toDelete = new ArrayList<>();
        for (TrashItem item : items) {
            if (now - item.deletionTime > AUTO_DELETE_MS) {
                toDelete.add(item.trashFileName);
            }
        }
        for (String fileName : toDelete) deletePermanently(fileName);
        if (!toDelete.isEmpty()) log.log("cleanupOldFiles: удалено " + toDelete.size());
    }

    /**
     * Очищает корзину полностью.
     */
    public void emptyTrash() {
        File[] files = trashDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (METADATA_FILE.equals(f.getName())) continue;
                deleteRecursive(f);
            }
        }
        saveMetadata(new ArrayList<>());
        log.log("emptyTrash: корзина очищена");
    }

    /**
     * Возвращает список элементов в корзине.
     */
    public List<TrashItem> getTrashContents() {
        return loadMetadata();
    }

    // ============================================================
    //  Внутренние
    // ============================================================

    private void ensureTrashDirExists() {
        if (!trashDir.exists()) {
            boolean ok = trashDir.mkdirs();
            log.log("ensureTrashDirExists: " + ok);
        }
    }

    private boolean isInsideTrash(File f) {
        try {
            String canonical = f.getCanonicalPath();
            String trashCanonical = trashDir.getCanonicalPath();
            return canonical.startsWith(trashCanonical + File.separator)
                    || canonical.equals(trashCanonical);
        } catch (IOException e) {
            return false;
        }
    }

    private List<TrashItem> loadMetadata() {
        if (!metadataFile.exists()) return new ArrayList<>();
        try (FileReader reader = new FileReader(metadataFile)) {
            Type type = new TypeToken<List<TrashItem>>() {}.getType();
            List<TrashItem> items = gson.fromJson(reader, type);
            return items != null ? items : new ArrayList<>();
        } catch (IOException e) {
            log.logError("loadMetadata: IO", e);
            return new ArrayList<>();
        } catch (com.google.gson.JsonSyntaxException e) {
            log.logError("loadMetadata: JSON повреждён, сбрасываю", e);
            if (metadataFile.exists()) metadataFile.delete();
            return new ArrayList<>();
        }
    }

    private void saveMetadata(List<TrashItem> items) {
        try (FileWriter writer = new FileWriter(metadataFile)) {
            gson.toJson(items, writer);
        } catch (IOException e) {
            log.logError("saveMetadata", e);
        }
    }

    private static boolean deleteRecursive(File f) {
        if (f == null) return false;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        return f.delete();
    }

    private static boolean copyRecursive(File src, File dst) {
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) return false;
            File[] kids = src.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    if (!copyRecursive(k, new File(dst, k.getName()))) return false;
                }
            }
            return true;
        }
        try (java.io.InputStream in = new java.io.BufferedInputStream(
                        new java.io.FileInputStream(src));
             java.io.OutputStream out = new java.io.BufferedOutputStream(
                     new java.io.FileOutputStream(dst))) {
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
            out.flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}