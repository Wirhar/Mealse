package com.my.mealse.messenger.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.Objects;

/**
 * Информация о вложении.
 *
 * Используется:
 *   - MessageCommands.sendFile(ctx, convId, AttachmentInfo) — вход
 *   - MessageReceiver — при приёме payload.kind == FILE
 *   - UI — для отображения имени/размера/иконки
 *
 * Поля:
 *   localPath  — физический путь к файлу в getFilesDir()/messenger/attachments/<convId>/
 *                (для исходящих — уже сохранён в AttachmentStorage;
 *                 для входящих — сохранён после приёма base64)
 *   fileName   — отображаемое имя (например, "photo.jpg")
 *   mimeType   — MIME (например, "image/jpeg"); если null — угадывается по расширению
 *   sizeBytes  — размер в байтах
 *
 * Не содержит сам контент — только метаданные + путь. Для отправки
 * MessageCommands читает File по localPath и кодирует в base64.
 */
public class AttachmentInfo {

    private String localPath;
    private String fileName;
    private String mimeType;
    private long sizeBytes;

    public AttachmentInfo() {
    }

    public AttachmentInfo(@Nullable String localPath,
                          @Nullable String fileName,
                          @Nullable String mimeType,
                          long sizeBytes) {
        this.localPath = localPath;
        this.fileName = fileName;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
    }

    /**
     * Удобная фабрика: из File. Имя/MIME/размер берутся из файла.
     */
    @NonNull
    public static AttachmentInfo fromFile(@NonNull File file) {
        String name = file.getName();
        String mime = guessMimeByName(name);
        long size = file.isFile() ? file.length() : 0L;
        return new AttachmentInfo(file.getAbsolutePath(), name, mime, size);
    }

    /**
     * Удобная фабрика: из пути (String).
     */
    @NonNull
    public static AttachmentInfo fromPath(@NonNull String path) {
        return fromFile(new File(path));
    }

    // ============================================================
    // Геттеры / сеттеры
    // ============================================================

    @Nullable
    public String getLocalPath() {
        return localPath;
    }

    public void setLocalPath(@Nullable String localPath) {
        this.localPath = localPath;
    }

    @Nullable
    public String getFileName() {
        return fileName;
    }

    public void setFileName(@Nullable String fileName) {
        this.fileName = fileName;
    }

    @Nullable
    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(@Nullable String mimeType) {
        this.mimeType = mimeType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    // ============================================================
    // Утилиты
    // ============================================================

    /** Возвращает File или null, если localPath пуст/не существует. */
    @Nullable
    public File getLocalFile() {
        if (localPath == null || localPath.isEmpty()) return null;
        File f = new File(localPath);
        return f.exists() ? f : null;
    }

    /** Проверка, что вложение готово к отправке (есть файл и размер > 0). */
    public boolean isReady() {
        File f = getLocalFile();
        return f != null && f.isFile() && f.length() > 0;
    }

    /** Человекочитаемый размер (для UI). */
    @NonNull
    public String getSizeHuman() {
        return formatSize(sizeBytes);
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " Б";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.getDefault(),
            "%.1f КБ", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(java.util.Locale.getDefault(),
            "%.1f МБ", bytes / (1024.0 * 1024));
        return String.format(java.util.Locale.getDefault(),
            "%.2f ГБ", bytes / (1024.0 * 1024 * 1024));
    }

    /** Иконка по MIME/расширению (для UI). */
    @NonNull
    public String getIcon() {
        String mime = mimeType;
        if (mime == null) mime = guessMimeByName(fileName);
        if (mime == null) return "📎";
        if (mime.startsWith("image/")) return "🖼";
        if (mime.startsWith("video/")) return "🎬";
        if (mime.startsWith("audio/")) return "🎵";
        if (mime.equals("application/pdf")) return "📄";
        if (mime.startsWith("text/")) return "📝";
        if (mime.contains("zip") || mime.contains("rar") || mime.contains("7z")
            || mime.contains("tar") || mime.contains("gzip")) return "📦";
        if (mime.startsWith("application/vnd.android.package")) return "📱";
        return "📎";
    }

    /**
     * Угадывание MIME по расширению (то же, что LocalFileEntry).
     * Дублируется здесь, чтобы AttachmentInfo был самодостаточным.
     */
    @Nullable
    public static String guessMimeByName(@Nullable String name) {
        if (name == null) return "application/octet-stream";
        String lower = name.toLowerCase(java.util.Locale.ROOT);
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
            case "m4v":  return "video/x-m4v";
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AttachmentInfo that = (AttachmentInfo) o;
        return sizeBytes == that.sizeBytes
            && Objects.equals(localPath, that.localPath)
            && Objects.equals(fileName, that.fileName)
            && Objects.equals(mimeType, that.mimeType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(localPath, fileName, mimeType, sizeBytes);
    }

    @Override
    public String toString() {
        return "AttachmentInfo{" + "name='" + fileName + '\''
            + ", mime='" + mimeType + '\''
            + ", size=" + getSizeHuman()
            + ", path='" + localPath + '\'' + '}';
    }
}