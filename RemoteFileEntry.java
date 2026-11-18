package com.my.mealse.filemanager;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Запись файла на удалённом узле (получена по REST API или
 * бинарным протоколом Wi-Fi Direct).
 *
 * Не держит java.io.File — файла локально нет. Для открытия нужно
 * сначала скачать через FileTransport.download().
 *
 * equals/hashCode — по (sourceId, path).
 *
 * Сериализация в JSON — единый формат, который отдаёт
 * RemoteFsService на удалённой стороне.
 */
public class RemoteFileEntry implements FileEntry {

    private static final long serialVersionUID = 1L;

    private final String sourceId;     // "ip:port" удалённого узла
    private final String path;
    private final String name;
    private final String parentPath;
    private final boolean directory;
    private final long size;
    private final long lastModified;
    private final String mimeType;
    private final boolean hidden;
    private final boolean readable;
    private final boolean writable;

    public RemoteFileEntry(String sourceId,
                           String path,
                           String name,
                           String parentPath,
                           boolean directory,
                           long size,
                           long lastModified,
                           String mimeType,
                           boolean hidden,
                           boolean readable,
                           boolean writable) {
        if (sourceId == null) {
            throw new IllegalArgumentException("sourceId == null (это не remote-запись)");
        }
        if (path == null) {
            throw new IllegalArgumentException("path == null");
        }
        this.sourceId = sourceId;
        this.path = path;
        this.name = name != null ? name : extractName(path);
        this.parentPath = parentPath != null ? parentPath : extractParent(path);
        this.directory = directory;
        this.size = size;
        this.lastModified = lastModified;
        this.mimeType = mimeType;
        this.hidden = hidden;
        this.readable = readable;
        this.writable = writable;
    }

    // ==================== FileEntry API ====================

    @Override public String getName() { return name; }
    @Override public String getPath() { return path; }
    @Override public String getParentPath() { return parentPath; }
    @Override public boolean isDirectory() { return directory; }
    @Override public long getSize() { return size; }
    @Override public long getLastModified() { return lastModified; }
    @Override public boolean isHidden() { return hidden; }
    @Override public boolean isReadable() { return readable; }
    @Override public boolean isWritable() { return writable; }
    @Override public String getSourceId() { return sourceId; }

    @Override
    public String getMimeType() {
        if (mimeType != null && !mimeType.isEmpty()) return mimeType;
        if (directory) return "inode/directory";
        return "application/octet-stream";
    }

    // ==================== JSON (de)serialization ====================

    /**
     * Формат JSON, который присылает RemoteFsService:
     * {
     *   "path": "/storage/emulated/0/Download/video.mp4",
     *   "name": "video.mp4",
     *   "parent": "/storage/emulated/0/Download",
     *   "dir": false,
     *   "size": 131072000,
     *   "mtime": 1712345678901,
     *   "mime": "video/mp4",
     *   "hidden": false,
     *   "readable": true,
     *   "writable": true
     * }
     */
    public static RemoteFileEntry fromJson(String sourceId, JSONObject json) throws JSONException {
        return new RemoteFileEntry(
                sourceId,
                json.getString("path"),
                json.optString("name", null),
                json.optString("parent", null),
                json.optBoolean("dir", false),
                json.optLong("size", 0L),
                json.optLong("mtime", 0L),
                json.optString("mime", null),
                json.optBoolean("hidden", false),
                json.optBoolean("readable", true),
                json.optBoolean("writable", false)
        );
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("path", path);
        o.put("name", name);
        if (parentPath != null) o.put("parent", parentPath);
        o.put("dir", directory);
        o.put("size", size);
        o.put("mtime", lastModified);
        if (mimeType != null) o.put("mime", mimeType);
        o.put("hidden", hidden);
        o.put("readable", readable);
        o.put("writable", writable);
        return o;
    }

    // ==================== equals / hashCode ====================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RemoteFileEntry)) return false;
        RemoteFileEntry that = (RemoteFileEntry) o;
        return sourceId.equals(that.sourceId) && path.equals(that.path);
    }

    @Override
    public int hashCode() {
        int h = sourceId.hashCode();
        h = 31 * h + path.hashCode();
        return h;
    }

    @Override
    public String toString() {
        return "RemoteFileEntry{" + sourceId + ":" + path
                + (directory ? "/" : "") + "}";
    }

    // ==================== Хелперы ====================

    private static String extractName(String path) {
        if (path == null) return "";
        int end = path.length();
        while (end > 0 && path.charAt(end - 1) == '/') end--;
        int slash = path.lastIndexOf('/', end - 1);
        return slash >= 0 ? path.substring(slash + 1, end) : path.substring(0, end);
    }

    private static String extractParent(String path) {
        if (path == null) return null;
        int end = path.length();
        while (end > 0 && path.charAt(end - 1) == '/') end--;
        int slash = path.lastIndexOf('/', end - 1);
        if (slash < 0) return null;
        if (slash == 0) return "/";
        return path.substring(0, slash);
    }
}