package com.my.mealse.filemanager;

import java.io.Serializable;

/**
 * Абстрактная запись файла/папки.
 *
 * Работает одинаково для:
 *   - локальных файлов (LocalFileEntry — обёртка над java.io.File)
 *   - удалённых файлов (RemoteFileEntry — данные из REST API или
 *     бинарного протокола Wi-Fi Direct)
 *
 * Ключевое отличие от прямого java.io.File:
 *   - getPath() возвращает путь в терминах источника (для локального — 
 *     абсолютный путь на диске, для удалённого — путь на удалённом узле)
 *   - нельзя напрямую открыть File — нужно сначала «материализовать»
 *     через FileTransport.download(), если это удалённый файл
 *   - нет прямой мутации (rename/delete/mkdir) — только через транспорт
 *
 * equals/hashCode — по (sourceId, path).
 *   sourceId == null  → локальный источник (это устройство)
 *   sourceId != null  → идентификатор удалённого узла (TrustedNode.getKey())
 *
 * Serializable — на случай передачи между активностями через Intent.
 */
public interface FileEntry extends Serializable {

    /** Имя файла или папки (последний сегмент пути). */
    String getName();

    /**
     * Полный путь в терминах источника.
     * Локально: /storage/emulated/0/Download/file.mp4
     * Удалённо: /storage/emulated/0/Download/file.mp4 (на удалённом узле)
     */
    String getPath();

    /** Родительский путь. null, если это корень. */
    String getParentPath();

    /** Это папка? */
    boolean isDirectory();

    /** Размер в байтах. Для папок — 0 или суммарный (зависит от источника). */
    long getSize();

    /** Время последнего изменения (мс с epoch). */
    long getLastModified();

    /** MIME-тип (например, video/mp4). Может быть null, если неизвестен. */
    String getMimeType();

    /** Скрытый файл/папка? */
    boolean isHidden();

    /** Файл доступен для чтения? */
    boolean isReadable();

    /** Файл доступен для записи (можно удалить/переименовать)? */
    boolean isWritable();

    /**
     * Идентификатор источника.
     * null       → локальное устройство (это устройство)
     * не null    → TrustedNode.getKey() удалённого узла: "ip:port"
     */
    String getSourceId();

    /** Это удалённая запись? */
    default boolean isRemote() {
        return getSourceId() != null;
    }

    /**
     * Раскрываемая папка? Для локального — true для папок.
     * Для удалённого — тоже true, если сервер сказал, что это папка.
     */
    default boolean isExpandable() {
        return isDirectory();
    }
}