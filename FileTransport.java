package com.my.mealse.filemanager;

import java.io.File;
import java.util.List;

/**
 * Абстракция транспорта для файловых операций.
 *
 * Единый интерфейс для HTTP-режимов (Wi-Fi / IPv6 / Gateway) и
 * для бинарного протокола Wi-Fi Direct.
 *
 * Все методы асинхронные. Колбэки вызываются в главном потоке.
 *
 * ★ РЕВИЗИЯ (Шаг 6): добавлен info() — запрос корня и списка
 * стандартных подпапок на удалённом узле.
 */
public interface FileTransport {

    // ============================================================
    //  Публичные enum'ы для опций
    // ============================================================

    enum ConflictPolicy {
        FAIL,
        OVERWRITE,
        RENAME
    }

    // ============================================================
    //  Основные операции
    // ============================================================

    /**
     * Запрашивает у удалённого узла корень и список стандартных
     * подпапок. Клиент вызывает один раз при выборе узла.
     */
    void info(InfoCallback cb);

    /** Список файлов в папке. */
    void list(String path, ListCallback cb);

    /** Метаданные файла/папки. */
    void stat(String path, StatCallback cb);

    /** Скачать файл с удалённого узла в локальный файл dest. */
    void download(String remotePath, File dest, TransferCallback cb);

    /** Загрузить файл на удалённый узел. */
    void upload(File src, String remoteDir, String remoteName,
                ConflictPolicy policy, TransferCallback cb);

    /** Создать папку. */
    void mkdir(String parentPath, String folderName, OpCallback cb);

    /** Переименовать файл/папку. */
    void rename(String path, String newName, OpCallback cb);

    /** Удалить файл/папку. */
    void delete(String path, boolean recursive, OpCallback cb);

    /** Переместить файл/папку. */
    void move(String srcPath, String dstDirPath, OpCallback cb);

    /** Копировать файл/папку. */
    void copy(String srcPath, String dstDirPath, OpCallback cb);

    /** Поиск файлов по имени (подстрока). */
    void search(String startPath, String query, ListCallback cb);

    /** Отменить текущую операцию (download/upload). */
    void cancel();

    /** Закрыть транспорт. */
    void close();

    /** Идентификатор источника: null — локальный, "ip:port" — удалённый. */
    String getSourceId();

    /** Описание транспорта для логов/UI. */
    String describe();

    // ============================================================
    //  Колбэки
    // ============================================================

    interface InfoCallback {
        void onResult(RemoteFsProtocol.RemoteRootInfo info);
        void onError(FileTransportException error);
    }

    interface ListCallback {
        void onResult(String path, List<FileEntry> entries);
        void onError(FileTransportException error);
    }

    interface StatCallback {
        void onResult(FileEntry entry);
        void onError(FileTransportException error);
    }

    interface OpCallback {
        void onSuccess();
        void onError(FileTransportException error);
    }

    interface TransferCallback {
        void onStart(long totalBytes);
        void onProgress(long bytesTransferred, long totalBytes);
        void onSuccess(File destFile);
        void onError(FileTransportException error);
        void onCancelled();
    }
}, если есть).
     */
    void close();

    /**
     * Идентификатор источника: null — локальный (это устройство),
     * "ip:port" — удалённый узел.
     */
    String getSourceId();

    /**
     * Описание транспорта для логов/UI: "Wi-Fi 192.168.1.10:8084",
     * "Wi-Fi Direct 192.168.49.1:8090" и т.п.
     */
    String describe();

    // ============================================================
    //  Колбэки
    // ============================================================

    interface ListCallback {
        /**
         * @param path   путь, для которого получен список
         * @param entries список файлов
         */
        void onResult(String path, List<FileEntry> entries);
        void onError(FileTransportException error);
    }

    interface StatCallback {
        void onResult(FileEntry entry);
        void onError(FileTransportException error);
    }

    interface OpCallback {
        void onSuccess();
        void onError(FileTransportException error);
    }

    interface TransferCallback {
        /** Вызывается один раз в начале. totalBytes == -1, если размер неизвестен. */
        void onStart(long totalBytes);

        /** Прогресс. bytesTransferred — общее число переданных байт. */
        void onProgress(long bytesTransferred, long totalBytes);

        /** Успешное завершение. destFile — итоговый файл. */
        void onSuccess(File destFile);

        /** Ошибка. */
        void onError(FileTransportException error);

        /** Отменено пользователем. */
        void onCancelled();
    }
}