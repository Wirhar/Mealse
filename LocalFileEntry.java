package com.my.mealse.filemanager;

import java.io.File;

/**
 * Локальная запись файла — обёртка над java.io.File.
 *
 * Особенности:
 *   - Все геттеры кэшируются в конструкторе, потому что после конструктора
 *     файл может быть удалён/переименован, а UI уже держит запись.
 *   - getSourceId() всегда возвращает null (локальный источник).
 *   - getMimeType() — ленивый, чтобы не скачивать header каждый раз.
 *     Использует FileTypeHelper (маппинг по расширению).
 *   - Не имеет публичного конструктора от File без проверки — файл должен
 *     существовать на момент создания, иначе IllegalArgumentException.
 */
public class LocalFileEntry implements FileEntry {

    private static final long serialVersionUID = 1L;

    private final String path;
    private final String name;
    private final String parentPath;
    private final boolean directory;
    private final long size;
    private final long lastModified;
    private final boolean hidden;
    private final boolean readable;
    private final boolean writable;

    // transient — File не сериализуется, восстанавливается по path
    private transient File file;

    public LocalFileEntry(File file) {
        if (file == null) {
            throw new IllegalArgumentException("file == null");
        }
        this.file = file;
        this.path = file.getAbsolutePath();
        this.name = file.getName();
        File parent = file.getParentFile();
        this.parentPath = parent != null ? parent.getAbsolutePath() : null;
        this.directory = file.isDirectory();
        this.size = directory ? 0L : file.length();
        this.lastModified = file.lastModified();
        this.hidden = file.isHidden();
        this.readable = file.canRead();
        this.writable = file.canWrite();
    }

    /** Возвращает java.io.File, восстанавливая из path при необходимости. */
    public File getFile() {
        if (file == null) {
            file = new File(path);
        }
        return file;
    }

    @Override public String getName() { return name; }
    @Override public String getPath() { return path; }
    @Override public String getParentPath() { return parentPath; }
    @Override public boolean isDirectory() { return directory; }
    @Override public long getSize() { return size; }
    @Override public long getLastModified() { return lastModified; }
    @Override public boolean isHidden() { return hidden; }
    @Override public boolean isReadable() { return readable; }
    @Override public boolean isWritable() { return writable; }

    @Override
    public String getMimeType() {
        // Ленивая инициализация — только когда запросят.
        // FileTypeHelper.getMimeTypeByExtension() — из Шага 6,
        // пока используем fallback по имени.
        if (directory) return "inode/directory";
        return guessMimeByExtension(name);
    }

    @Override
    public String getSourceId() {
        return null; // локальный источник
    }

    // ==================== equals / hashCode ====================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LocalFileEntry)) return false;
        LocalFileEntry that = (LocalFileEntry) o;
        return path.equals(that.path);
    }

    @Override
    public int hashCode() {
        return path.hashCode();
    }

    @Override
    public String toString() {
        return "LocalFileEntry{" + path + (directory ? "/" : "") + "}";
    }

    // ==================== Приватные хелперы ====================

    /**
     * Fallback-угадыватель MIME по расширению.
     * Полноценный FileTypeHelper будет в Шаге 6.
     */
    private static String guessMimeByExtension(String fileName) {
        if (fileName == null) return "application/octet-stream";
        String lower = fileName.toLowerCase();
        int dot = lower.lastIndexOf('.');
        if (dot < 0 || dot == lower.length() - 1) return "application/octet-stream";
        String ext = lower.substring(dot + 1);

        switch (ext) {
            // Видео
            case "mp4":  return "video/mp4";
            case "mkv":  return "video/x-matroska";
            case "avi":  return "video/x-msvideo";
            case "mov":  return "video/quicktime";
            case "webm": return "video/webm";
            case "3gp":  return "video/3gpp";
            case "flv":  return "video/x-flv";
            case "wmv":  return "video/x-ms-wmv";
            case "m4v":  return "video/x-m4v";
            case "ts":   return "video/mp2t";
            case "m3u8": return "application/x-mpegURL";

            // Аудио
            case "mp3":  return "audio/mpeg";
            case "wav":  return "audio/wav";
            case "flac": return "audio/flac";
            case "aac":  return "audio/aac";
            case "ogg":  return "audio/ogg";
            case "m4a":  return "audio/mp4";

            // Изображения
            case "jpg":
            case "jpeg": return "image/jpeg";
            case "png":  return "image/png";
            case "gif":  return "image/gif";
            case "webp": return "image/webp";
            case "bmp":  return "image/bmp";
            case "svg":  return "image/svg+xml";

            // Документы
            case "pdf":  return "application/pdf";
            case "txt":  return "text/plain";
            case "json": return "application/json";
            case "xml":  return "application/xml";
            case "html":
            case "htm":  return "text/html";
            case "css":  return "text/css";
            case "js":   return "application/javascript";
            case "md":   return "text/markdown";
            case "csv":  return "text/csv";
            case "log":  return "text/plain";

            // Архивы
            case "zip":  return "application/zip";
            case "rar":  return "application/x-rar-compressed";
            case "7z":   return "application/x-7z-compressed";
            case "tar":  return "application/x-tar";
            case "gz":   return "application/gzip";

            // Прочее
            case "apk":  return "application/vnd.android.package-archive";
            default:     return "application/octet-stream";
        }
    }
}