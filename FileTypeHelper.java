package com.my.mealse.filemanager.util;

import java.util.Locale;

/**
 * Утилита для работы с типами файлов.
 *
 * Отвечает за:
 *   1. Определение MIME по расширению (быстрее, чем ходить в ФС).
 *   2. Категоризацию файлов — куда складывать (downloads/pictures/videos/...).
 *   3. Отображение категории в человекочитаемый вид.
 *
 * Используется FileManagerActivity при копировании во внутренние
 * папки приложения и для иконок в списке.
 */
public final class FileTypeHelper {

    private FileTypeHelper() {}

    // ============================================================
    //  Категории
    // ============================================================

    public enum Category {
        PICTURE,   // → files/pictures/
        VIDEO,     // → files/videos/
        AUDIO,     // → files/audio/
        DOCUMENT,  // → files/documents/
        ARCHIVE,   // → files/archives/
        APK,       // → files/other/  (или отдельно, если захотим)
        OTHER      // → files/other/
    }

    // ============================================================
    //  Категория по MIME или имени
    // ============================================================

    public static Category getCategoryByMimeType(String mime) {
        if (mime == null || mime.isEmpty()) return Category.OTHER;
        String m = mime.toLowerCase(Locale.ROOT);
        if (m.startsWith("image/"))  return Category.PICTURE;
        if (m.startsWith("video/"))  return Category.VIDEO;
        if (m.startsWith("audio/"))  return Category.AUDIO;
        if (m.equals("application/pdf")) return Category.DOCUMENT;
        if (m.startsWith("text/")) return Category.DOCUMENT;
        if (m.equals("application/json") || m.equals("application/xml")) return Category.DOCUMENT;
        if (m.equals("application/vnd.android.package-archive")) return Category.APK;
        if (m.contains("zip") || m.contains("rar") || m.contains("tar")
                || m.contains("7z") || m.contains("gzip")) return Category.ARCHIVE;
        return Category.OTHER;
    }

    public static Category getCategoryByFileName(String fileName) {
        if (fileName == null) return Category.OTHER;
        String ext = getExtension(fileName);
        switch (ext) {
            case "jpg": case "jpeg": case "png": case "gif":
            case "webp": case "bmp": case "svg": case "heic": case "heif":
                return Category.PICTURE;
            case "mp4": case "mkv": case "avi": case "mov": case "webm":
            case "3gp": case "flv": case "wmv": case "m4v": case "ts":
                return Category.VIDEO;
            case "mp3": case "wav": case "flac": case "aac":
            case "ogg": case "m4a": case "opus":
                return Category.AUDIO;
            case "pdf": case "txt": case "json": case "xml":
            case "html": case "htm": case "md": case "csv":
            case "log": case "doc": case "docx": case "xls":
            case "xlsx": case "ppt": case "pptx":
                return Category.DOCUMENT;
            case "zip": case "rar": case "7z": case "tar": case "gz":
                return Category.ARCHIVE;
            case "apk":
                return Category.APK;
            default:
                return Category.OTHER;
        }
    }

    // ============================================================
    //  Имя папки для категории
    // ============================================================

    /**
     * Возвращает имя подпапки внутри files/, в которую класть
     * файл этой категории.
     */
    public static String getCategoryFolder(Category cat) {
        if (cat == null) return "other";
        switch (cat) {
            case PICTURE:  return "pictures";
            case VIDEO:    return "videos";
            case AUDIO:    return "audio";
            case DOCUMENT: return "documents";
            case ARCHIVE:  return "archives";
            case APK:      return "other";   // можно сделать "apk", если захотим
            case OTHER:
            default:       return "other";
        }
    }

    // ============================================================
    //  MIME по расширению (без обращения к ОС)
    // ============================================================

    /**
     * Возвращает MIME-тип по имени файла. Если расширение
     * неизвестно — "application/octet-stream".
     */
    public static String getMimeTypeByFileName(String fileName) {
        if (fileName == null) return "application/octet-stream";
        String ext = getExtension(fileName);
        switch (ext) {
            // PICTURE
            case "jpg":
            case "jpeg": return "image/jpeg";
            case "png":  return "image/png";
            case "gif":  return "image/gif";
            case "webp": return "image/webp";
            case "bmp":  return "image/bmp";
            case "svg":  return "image/svg+xml";
            case "heic": return "image/heic";
            case "heif": return "image/heif";

            // VIDEO
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

            // AUDIO
            case "mp3":  return "audio/mpeg";
            case "wav":  return "audio/wav";
            case "flac": return "audio/flac";
            case "aac":  return "audio/aac";
            case "ogg":  return "audio/ogg";
            case "m4a":  return "audio/mp4";
            case "opus": return "audio/opus";

            // DOCUMENT
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

            // ARCHIVE
            case "zip":  return "application/zip";
            case "rar":  return "application/x-rar-compressed";
            case "7z":   return "application/x-7z-compressed";
            case "tar":  return "application/x-tar";
            case "gz":   return "application/gzip";

            // APK
            case "apk":  return "application/vnd.android.package-archive";

            default:     return "application/octet-stream";
        }
    }

    // ============================================================
    //  Хелперы
    // ============================================================

    /**
     * Возвращает расширение без точки, в нижнем регистре.
     * "" — если расширения нет.
     */
    public static String getExtension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "";
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Человекочитаемое имя категории (для логов/UI).
     */
    public static String getCategoryDisplayName(Category cat) {
        if (cat == null) return "Прочее";
        switch (cat) {
            case PICTURE:  return "Изображения";
            case VIDEO:    return "Видео";
            case AUDIO:    return "Аудио";
            case DOCUMENT: return "Документы";
            case ARCHIVE:  return "Архивы";
            case APK:      return "Приложения";
            case OTHER:
            default:       return "Прочее";
        }
    }
}