package com.my.mealse.filemanager.server;

import android.content.Context;

import com.my.mealse.util.MealseLogger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * RemoteFsPathGuard — защита путей для RemoteFsService.
 *
 * ★ РЕВИЗИЯ (internal-only):
 *   Раньше сервис давал доступ ко всему внешнему хранилищу
 *   (/storage/emulated/0/). Это было слишком широко — удалённый узел
 *   видел все фото, документы, скачанные файлы пользователя.
 *
 *   Теперь удалённый узел видит ТОЛЬКО внутреннее хранилище приложения:
 *       /data/data/com.my.mealse/files/    (getFilesDir)
 *   Внутри него — пользовательские подпапки по категориям:
 *       downloads/, received/, documents/, pictures/, videos/,
 *       audio/, archives/, other/
 *
 * ★ НОВОЕ (Шаг 15): приватность мессенджера.
 *   Папка messenger/ (вложения чатов) СКРЫТА от удалённых узлов:
 *     - resolveAbsolute("/messenger")       → null (403)
 *     - resolveAbsolute("/messenger/...")   → null (403)
 *     - toVirtualPath(getFilesDir()/messenger/...) → null
 *     - LIST /                              → messenger НЕ в списке
 *     - SEARCH                              → файлы messenger/ НЕ в результатах
 *     - DOWNLOAD / UPLOAD / DELETE / MKDIR / RENAME / MOVE / COPY
 *                                           → 403 для messenger/...
 *
 *   Локально (FileManagerActivity, 📦 Приложение) папка видна —
 *   защита работает только на уровне RemoteFsPathGuard, который
 *   используется RemoteFsService (удалённый доступ).
 *
 *   Вложения мессенджера (AttachmentStorage) физически лежат в
 *   getFilesDir()/messenger/attachments/<convId>/, но снаружи (по HTTP
 *   от другого узла) недостижимы.
 *
 * ★ Виртуальные пути:
 *   Клиент (FileManagerActivity) работает с ОТНОСИТЕЛЬНЫМИ путями:
 *       "/"                       → корень, т.е. getFilesDir()
 *       "/downloads"              → getFilesDir()/downloads
 *       "/downloads/file.mp4"     → getFilesDir()/downloads/file.mp4
 *   Абсолютные пути наружу (в JSON) НЕ уходят — клиент не знает,
 *   где именно на диске живёт приложение.
 *
 * Использование:
 *   RemoteFsPathGuard guard = new RemoteFsPathGuard(context);
 *   File real = guard.resolveAbsolute("/downloads/file.mp4");
 *   // real != null → работаем, real == null → 403 PATH_FORBIDDEN
 *
 *   String virtualPath = guard.toVirtualPath(realFile);
 *   // virtualPath == "/downloads/file.mp4"
 */
public class RemoteFsPathGuard {

    private static final String TAG = "RemoteFsPathGuard";

    /** Стандартные подпапки, которые создаются при инициализации. */
    public static final String DIR_DOWNLOADS  = "downloads";
    public static final String DIR_RECEIVED   = "received";
    public static final String DIR_DOCUMENTS  = "documents";
    public static final String DIR_PICTURES   = "pictures";
    public static final String DIR_VIDEOS     = "videos";
    public static final String DIR_AUDIO      = "audio";
    public static final String DIR_ARCHIVES   = "archives";
    public static final String DIR_OTHER      = "other";

    /**
     * ★ НОВОЕ (Шаг 15): папки, которые НЕ видны и НЕ доступны удалённым узлам.
     * Сравнение — по сегменту пути относительно getFilesDir().
     */
    private static final List<String> HIDDEN_FROM_REMOTE = Arrays.asList(
        "messenger"   // вложения мессенджера (AttachmentStorage)
    );

    private static final List<String> DEFAULT_SUBDIRS = Arrays.asList(
            DIR_DOWNLOADS,
            DIR_RECEIVED,
            DIR_DOCUMENTS,
            DIR_PICTURES,
            DIR_VIDEOS,
            DIR_AUDIO,
            DIR_ARCHIVES,
            DIR_OTHER
    );

    private final Context appContext;
    private final MealseLogger log;

    /** Единственный разрешённый физический корень — getFilesDir(). */
    private final File rootDir;
    private final String rootCanonical;

    public RemoteFsPathGuard(Context context) {
        this.appContext = context.getApplicationContext();
        this.log = MealseLogger.get(appContext, TAG);

        File files = appContext.getFilesDir();
        if (files == null) {
            // На практике не бывает, но на всякий случай
            files = new File(appContext.getCacheDir(), "files_fallback");
        }
        this.rootDir = files;

        String canonical = null;
        try {
            canonical = rootDir.getCanonicalPath();
        } catch (IOException e) {
            log.logError("Не удалось получить canonical rootDir", e);
            canonical = rootDir.getAbsolutePath();
        }
        this.rootCanonical = canonical;

        ensureRootExists();
        ensureDefaultSubdirs();

        log.log("RemoteFsPathGuard создан, root=" + rootCanonical);
        log.log("HIDDEN_FROM_REMOTE=" + HIDDEN_FROM_REMOTE);
    }

    // ============================================================
    //  Публичные методы
    // ============================================================

    /**
     * Возвращает физический корень (getFilesDir()).
     * Для логирования и отладки. Клиентам наружу не отдавать.
     */
    public File getRootDir() {
        return rootDir;
    }

    /**
     * Виртуальный корень, как его видит клиент. Всегда "/".
     */
    public String getVirtualRoot() {
        return "/";
    }

    /**
     * Список стандартных подпапок (для отображения «быстрого доступа» на клиенте).
     * ★ НЕ включает messenger/ — он скрыт от удалённых узлов.
     */
    public List<String> getDefaultSubdirs() {
        return new ArrayList<>(DEFAULT_SUBDIRS);
    }

    /**
     * Разрешает виртуальный путь в физический File.
     *
     * ★ Шаг 15: если путь попадает в HIDDEN_FROM_REMOTE (messenger/...),
     *   возвращает null — RemoteFsService ответит 403.
     *
     * @param virtualPath виртуальный путь от корня: "/", "/downloads",
     *                    "/downloads/file.mp4". Может содержать "..",
     *                    но traversal заблокируется.
     * @return File внутри getFilesDir(), либо null если путь запрещён.
     */
    public File resolveAbsolute(String virtualPath) {
        if (virtualPath == null) return null;

        // Нормализуем строку: "/a/b/../c" → "/a/c", убираем лишние слеши
        String normalized = normalizeVirtualPath(virtualPath);
        if (normalized == null) {
            log.warn("resolveAbsolute: traversal или некорректный путь: " + virtualPath);
            return null;
        }

        // ★ Шаг 15: блокируем hidden-папки
        if (isHiddenFromRemote(normalized)) {
            log.warn("resolveAbsolute: путь скрыт от удалённых узлов: " + normalized);
            return null;
        }

        // Собираем физический путь: rootCanonical + normalized
        // normalized гарантированно начинается с "/"
        File candidate = new File(rootCanonical + normalized);

        // Двойная защита: проверяем canonical после сборки
        try {
            String canonical = candidate.getCanonicalPath();
            if (canonical.equals(rootCanonical)
                    || canonical.startsWith(rootCanonical + File.separator)) {
                return new File(canonical);
            } else {
                log.warn("resolveAbsolute: canonical вне root: " + canonical);
                return null;
            }
        } catch (IOException e) {
            log.logError("resolveAbsolute: canonical failed", e);
            return null;
        }
    }

    /**
     * Преобразует физический File в виртуальный путь для клиента.
     * Если File вне root — возвращает null (не должно случаться,
     * но подстрахуемся).
     *
     * ★ Шаг 15: если File внутри hidden-папки (messenger/...) —
     *   возвращает null. Это автоматически исключает messenger/ из
     *   handleList, handleSearch, handleStat и любых JSON-ответов.
     */
    public String toVirtualPath(File file) {
        if (file == null) return null;
        try {
            String canonical = file.getCanonicalPath();
            if (canonical.equals(rootCanonical)) {
                return "/";
            }
            if (canonical.startsWith(rootCanonical + File.separator)) {
                String relative = canonical.substring(rootCanonical.length());
                // relative начинается с File.separator
                String virtual = relative.replace(File.separatorChar, '/');

                // ★ Шаг 15: скрываем hidden-папки
                if (isHiddenFromRemote(virtual)) {
                    // Не логируем каждый файл из messenger/ — это спам
                    // (handleList обходит все файлы). Просто возвращаем null.
                    return null;
                }

                return virtual;
            }
            log.warn("toVirtualPath: файл вне root: " + canonical);
            return null;
        } catch (IOException e) {
            log.logError("toVirtualPath: canonical failed", e);
            return null;
        }
    }

    /**
     * Проверка: можно ли читать файл (внутри root и readable).
     */
    public boolean canRead(File f) {
        if (f == null) return false;
        String v = toVirtualPath(f);
        return v != null && f.canRead();
    }

    /**
     * Проверка: можно ли писать.
     * Для новых файлов (ещё не существующих) проверяет родителя.
     */
    public boolean canWrite(File f) {
        if (f == null) return false;
        String v = toVirtualPath(f);
        if (v == null) return false;

        if (f.exists()) {
            return f.canWrite();
        }
        File parent = f.getParentFile();
        return parent != null && parent.exists() && parent.canWrite();
    }

    // ============================================================
    //  Внутренние
    // ============================================================

    /**
     * ★ Шаг 15: проверяет, попадает ли виртуальный путь в HIDDEN_FROM_REMOTE.
     *
     * Правила:
     *   "/messenger"         → true
     *   "/messenger/"        → true
     *   "/messenger/foo"     → true
     *   "/messenger/foo/bar" → true
     *   "/messengerX"        → false  (не путать с messenger/)
     *   "/Downloads/..."     → false
     *
     * Сравнение — по сегменту: split("/") → первый непустой сегмент
     * должен быть в HIDDEN_FROM_REMOTE.
     *
     * @param normalizedPath путь после normalizeVirtualPath, начинается с "/"
     */
    private boolean isHiddenFromRemote(String normalizedPath) {
        if (normalizedPath == null || normalizedPath.isEmpty()) return false;
        // "/messenger/foo" → ["", "messenger", "foo"]
        // "/" → [""] → нет сегментов → false
        String[] segments = normalizedPath.split("/");
        for (String seg : segments) {
            if (seg.isEmpty()) continue;
            for (String hidden : HIDDEN_FROM_REMOTE) {
                if (hidden.equals(seg)) {
                    return true;
                }
            }
            // Проверяем только первый непустой сегмент — вложенность
            // не важна, если корень скрыт.
            return false;
        }
        return false;
    }

    private void ensureRootExists() {
        if (!rootDir.exists()) {
            boolean ok = rootDir.mkdirs();
            log.log("Создание rootDir: " + rootCanonical + " → " + ok);
        }
        if (!rootDir.isDirectory()) {
            log.error("rootDir не директория: " + rootCanonical);
        }
    }

    private void ensureDefaultSubdirs() {
        for (String name : DEFAULT_SUBDIRS) {
            File sub = new File(rootDir, name);
            if (!sub.exists()) {
                boolean ok = sub.mkdirs();
                log.log("Создание подпапки " + name + ": " + ok);
            }
        }
        // ★ Шаг 15: messenger/ НЕ создаём здесь — он появится при первом
        // сохранении вложения (AttachmentStorage.getConversationDir).
    }

    /**
     * Нормализует виртуальный путь:
     *   - приводит разделители к "/",
     *   - схлопывает "//",
     *   - сворачивает "." и "..",
     *   - гарантирует, что результат начинается с "/",
     *   - возвращает null, если ".." выводит за корень.
     *
     * Примеры:
     *   "/downloads/../received" → "/received"
     *   "downloads/file.mp4"     → "/downloads/file.mp4"
     *   "/../etc/passwd"         → null
     *   ""                       → "/"
     */
    static String normalizeVirtualPath(String path) {
        if (path == null) return null;
        String p = path.replace('\\', '/');
        String[] segs = p.split("/+");

        List<String> stack = new ArrayList<>();
        for (String s : segs) {
            if (s.isEmpty() || ".".equals(s)) continue;
            if ("..".equals(s)) {
                if (stack.isEmpty()) {
                    // попытка вылезти выше корня
                    return null;
                }
                stack.remove(stack.size() - 1);
            } else {
                // запрещаем символы, которые в Linux могут содержать
                // NUL или разделитель
                if (s.indexOf('\0') >= 0) return null;
                stack.add(s);
            }
        }

        if (stack.isEmpty()) return "/";
        StringBuilder sb = new StringBuilder();
        for (String s : stack) {
            sb.append('/').append(s);
        }
        return sb.toString();
    }
}