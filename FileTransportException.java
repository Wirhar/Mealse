package com.my.mealse.filemanager;

/**
 * Типизированное исключение для операций файлового транспорта.
 *
 * code — машинно-читаемый код ошибки (для UI и логов).
 * message — человекочитаемое описание.
 * cause — вложенная причина (IOException, timeout и т.п.).
 */
public class FileTransportException extends Exception {

    private static final long serialVersionUID = 1L;

    // Коды ошибок
    public static final int CODE_UNKNOWN            = 0;
    public static final int CODE_NETWORK            = 1;   // нет сети / таймаут / connect failed
    public static final int CODE_NOT_FOUND          = 2;   // 404 — файл/папка не найдены
    public static final int CODE_ACCESS_DENIED      = 3;   // 403 — узел не разрешил доступ
    public static final int CODE_PATH_FORBIDDEN     = 4;   // сервер отказал (path traversal, вне root)
    public static final int CODE_ALREADY_EXISTS     = 5;   // 409 — файл уже есть
    public static final int CODE_INVALID_REQUEST    = 6;   // 400 — некорректный запрос
    public static final int CODE_SERVER_ERROR       = 7;   // 500
    public static final int CODE_CANCELLED          = 8;   // отменено пользователем
    public static final int CODE_NOT_CONNECTED      = 9;   // Wi-Fi Direct не подключён и т.п.
    public static final int CODE_PROTOCOL           = 10;  // нарушение протокола
    public static final int CODE_IO                 = 11;  // локальный I/O

    private final int code;

    public FileTransportException(int code, String message) {
        super(message);
        this.code = code;
    }

    public FileTransportException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    /**
     * Маппит HTTP-код в наш код ошибки.
     */
    public static int fromHttpStatus(int httpStatus) {
        if (httpStatus == 400) return CODE_INVALID_REQUEST;
        if (httpStatus == 403) return CODE_ACCESS_DENIED;
        if (httpStatus == 404) return CODE_NOT_FOUND;
        if (httpStatus == 409) return CODE_ALREADY_EXISTS;
        if (httpStatus >= 500) return CODE_SERVER_ERROR;
        return CODE_UNKNOWN;
    }
}