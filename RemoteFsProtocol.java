package com.my.mealse.filemanager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Общий формат JSON-сообщений для удалённого файлового API.
 *
 * ★ РЕВИЗИЯ (Шаг 6):
 *   - Добавлена операция OP_INFO ("info") — запрос корня и списка
 *     стандартных подпапок на удалённом узле.
 *   - Добавлен метод paramsInfo().
 *   - Добавлен класс RemoteRootInfo — модель ответа /fs/info.
 *   - Добавлен парсер parseInfo().
 *
 * Формат запроса:
 * {
 *   "v": 1,
 *   "req_id": 42,
 *   "op": "list",
 *   "path": "/downloads",
 *   "params": { ... }
 * }
 *
 * Формат успешного ответа:
 * { "v": 1, "req_id": 42, "status": "ok", "data": { ... } }
 *
 * Формат ошибки:
 * { "v": 1, "req_id": 42, "status": "error", "code": 4, "message": "..." }
 *
 * Все методы, которые могут бросить JSONException, заворачивают его
 * в FileTransportException, чтобы вызывающий код не страдал.
 */
public final class RemoteFsProtocol {

    private RemoteFsProtocol() {}

    public static final int PROTOCOL_VERSION = 1;

    // ===== Операции =====
    public static final String OP_INFO     = "info";
    public static final String OP_LIST     = "list";
    public static final String OP_STAT     = "stat";
    public static final String OP_MKDIR    = "mkdir";
    public static final String OP_RENAME   = "rename";
    public static final String OP_DELETE   = "delete";
    public static final String OP_MOVE     = "move";
    public static final String OP_COPY     = "copy";
    public static final String OP_SEARCH   = "search";
    public static final String OP_DOWNLOAD = "download";
    public static final String OP_UPLOAD   = "upload";

    // ===== Статусы =====
    public static final String STATUS_OK    = "ok";
    public static final String STATUS_ERROR = "error";

    // ============================================================
    //  Запросы
    // ============================================================

    public static JSONObject buildRequest(long reqId, String op, String path,
                                          JSONObject params) throws FileTransportException {
        try {
            JSONObject o = new JSONObject();
            o.put("v", PROTOCOL_VERSION);
            o.put("req_id", reqId);
            o.put("op", op);
            if (path != null) o.put("path", path);
            if (params != null) o.put("params", params);
            return o;
        } catch (JSONException e) {
            throw new FileTransportException(
                    FileTransportException.CODE_INVALID_REQUEST,
                    "buildRequest: " + e.getMessage(), e);
        }
    }

    public static JSONObject paramsInfo() throws FileTransportException {
        return new JSONObject();
    }

    public static JSONObject paramsList() throws FileTransportException {
        return new JSONObject();
    }

    public static JSONObject paramsMkdir(String folderName) throws FileTransportException {
        try {
            JSONObject p = new JSONObject();
            p.put("name", folderName);
            return p;
        } catch (JSONException e) {
            throw new FileTransportException(
                    FileTransportException.CODE_INVALID_REQUEST,
                    "paramsMkdir: " + e.getMessage(), e);
        }
    }

    public static JSONObject paramsRename(String newName) throws FileTransportException {
        try {
            JSONObject p = new JSONObject();
            p.put("new_name", newName);
            return p;
        } catch (JSONException e) {
            throw new FileTransportException(
                    FileTransportException.CODE_INVALID_REQUEST,
                    "paramsRename: " + e.getMessage(), e);
        }
    }

    public static JSONObject paramsDelete(boolean recursive) throws FileTransportException {
        try {
            JSONObject p = new JSONObject();
            p.put("recursive", recursive);
            return p;
        } catch (JSONException e) {
            throw new FileTransportException(
                    FileTransportException.CODE_INVALID_REQUEST,
                    "paramsDelete: " + e.getMessage(), e);
        }
    }

    public static JSONObject paramsMoveOrCopy(String dstDirPath) throws FileTransportException {
        try {
            JSONObject p = new JSONObject();
            p.put("dst_dir", dstDirPath);
            return p;
        } catch (JSONException e) {
            throw new FileTransportException(
                    FileTransportException.CODE_INVALID_REQUEST,
                    "paramsMoveOrCopy: " + e.getMessage(), e);
        }
    }

    public static JSONObject paramsSearch(String query) throws FileTransportException {
        try {
            JSONObject p = new JSONObject();
            p.put("q", query);
            return p;
        } catch (JSONException e) {
            throw new FileTransportException(
                    FileTransportException.CODE_INVALID_REQUEST,
                    "paramsSearch: " + e.getMessage(), e);
        }
    }

    public static JSONObject paramsUpload(String fileName, FileTransport.ConflictPolicy policy)
            throws FileTransportException {
        try {
            JSONObject p = new JSONObject();
            p.put("name", fileName);
            p.put("policy", policyToString(policy));
            return p;
        } catch (JSONException e) {
            throw new FileTransportException(
                    FileTransportException.CODE_INVALID_REQUEST,
                    "paramsUpload: " + e.getMessage(), e);
        }
    }

    // ============================================================
    //  Разбор ответов
    // ============================================================

    public static boolean isOk(JSONObject resp) {
        return resp != null && STATUS_OK.equals(resp.optString("status"));
    }

    public static boolean isError(JSONObject resp) {
        return resp != null && STATUS_ERROR.equals(resp.optString("status"));
    }

    public static int errorCode(JSONObject resp) {
        return resp != null ? resp.optInt("code", FileTransportException.CODE_UNKNOWN) : -1;
    }

    public static String errorMessage(JSONObject resp) {
        return resp != null ? resp.optString("message", "unknown error") : "null response";
    }

    /** Парсит data.entries как список FileEntry. */
    public static List<FileEntry> parseEntries(String sourceId, JSONObject resp)
            throws FileTransportException {
        List<FileEntry> result = new ArrayList<>();
        if (resp == null) return result;
        JSONObject data = resp.optJSONObject("data");
        if (data == null) return result;
        JSONArray arr = data.optJSONArray("entries");
        if (arr == null) return result;
        try {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject e = arr.getJSONObject(i);
                result.add(RemoteFileEntry.fromJson(sourceId, e));
            }
        } catch (JSONException e) {
            throw new FileTransportException(
                    FileTransportException.CODE_PROTOCOL,
                    "parseEntries: " + e.getMessage(), e);
        }
        return result;
    }

    /** Парсит data как один FileEntry. */
    public static FileEntry parseEntry(String sourceId, JSONObject resp)
            throws FileTransportException {
        if (resp == null) return null;
        JSONObject data = resp.optJSONObject("data");
        if (data == null) return null;
        JSONObject entry = data.optJSONObject("entry");
        if (entry == null) return null;
        try {
            return RemoteFileEntry.fromJson(sourceId, entry);
        } catch (JSONException e) {
            throw new FileTransportException(
                    FileTransportException.CODE_PROTOCOL,
                    "parseEntry: " + e.getMessage(), e);
        }
    }

    /**
     * Парсит ответ /fs/info в RemoteRootInfo.
     * Ожидаемый формат data:
     * {
     *   "rootPath": "/",
     *   "subdirs": ["downloads","received",...],
     *   "protocol": 1,
     *   "server": "Mealse RemoteFsService"
     * }
     */
    public static RemoteRootInfo parseInfo(JSONObject resp) throws FileTransportException {
        if (resp == null) {
            throw new FileTransportException(
                    FileTransportException.CODE_PROTOCOL, "parseInfo: null response");
        }
        JSONObject data = resp.optJSONObject("data");
        if (data == null) {
            throw new FileTransportException(
                    FileTransportException.CODE_PROTOCOL, "parseInfo: data == null");
        }
        String rootPath = data.optString("rootPath", "/");
        if (rootPath == null || rootPath.isEmpty()) rootPath = "/";

        List<String> subdirs = new ArrayList<>();
        JSONArray arr = data.optJSONArray("subdirs");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, null);
                if (s != null && !s.isEmpty()) subdirs.add(s);
            }
        }
        int protocol = data.optInt("protocol", PROTOCOL_VERSION);
        String server = data.optString("server", null);

        return new RemoteRootInfo(rootPath, subdirs, protocol, server);
    }

    // ============================================================
    //  Хелперы
    // ============================================================

    public static String policyToString(FileTransport.ConflictPolicy p) {
        if (p == null) return "fail";
        switch (p) {
            case OVERWRITE: return "overwrite";
            case RENAME:    return "rename";
            case FAIL:
            default:        return "fail";
        }
    }

    public static FileTransport.ConflictPolicy policyFromString(String s) {
        if (s == null) return FileTransport.ConflictPolicy.FAIL;
        switch (s) {
            case "overwrite": return FileTransport.ConflictPolicy.OVERWRITE;
            case "rename":    return FileTransport.ConflictPolicy.RENAME;
            case "fail":
            default:          return FileTransport.ConflictPolicy.FAIL;
        }
    }

    // ============================================================
    //  Модель ответа /fs/info
    // ============================================================

    /**
     * Модель ответа /fs/info.
     *
     * - rootPath — всегда "/" для нашего сервера (виртуальный корень).
     * - subdirs — список стандартных подпапок, созданных
     *   RemoteFsPathGuard: downloads, received, documents, pictures,
     *   videos, audio, archives, other.
     * - protocol — версия протокола (для будущей совместимости).
     * - server — идентификатор сервера ("Mealse RemoteFsService"),
     *   null если сервер не указал.
     */
    public static class RemoteRootInfo {
        public final String rootPath;
        public final List<String> subdirs;
        public final int protocol;
        public final String server;

        public RemoteRootInfo(String rootPath, List<String> subdirs,
                              int protocol, String server) {
            this.rootPath = rootPath != null ? rootPath : "/";
            this.subdirs = subdirs != null
                    ? Collections.unmodifiableList(new ArrayList<>(subdirs))
                    : Collections.emptyList();
            this.protocol = protocol;
            this.server = server;
        }

        public boolean hasSubdir(String name) {
            return subdirs.contains(name);
        }

        @Override
        public String toString() {
            return "RemoteRootInfo{" +
                    "root='" + rootPath + '\'' +
                    ", subdirs=" + subdirs +
                    ", protocol=" + protocol +
                    ", server='" + server + '\'' +
                    '}';
        }
    }
}