package com.my.mealse.filemanager.server;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Разобранный запрос к RemoteFsService.
 *
 * Универсальный — используется и HTTP-обработчиком, и бинарным
 * протоколом Wi-Fi Direct.
 */
public class RemoteFsRequest {

    public final long reqId;
    public final String op;
    public final String path;
    public final JSONObject params;

    private RemoteFsRequest(long reqId, String op, String path, JSONObject params) {
        this.reqId = reqId;
        this.op = op;
        this.path = path;
        this.params = params != null ? params : new JSONObject();
    }

    /**
     * Парсит JSON-запрос. Бросает JSONException, если запрос некорректен.
     */
    public static RemoteFsRequest fromJson(JSONObject o) throws JSONException {
        if (o == null) throw new JSONException("null request");
        long reqId = o.optLong("req_id", 0);
        String op = o.optString("op", null);
        if (op == null || op.isEmpty()) {
            throw new JSONException("op is required");
        }
        String path = o.optString("path", null);
        JSONObject params = o.optJSONObject("params");
        return new RemoteFsRequest(reqId, op, path, params);
    }

    // ===== Удобные геттеры для params =====

    public String paramString(String key, String def) {
        return params.optString(key, def);
    }

    public boolean paramBoolean(String key, boolean def) {
        return params.optBoolean(key, def);
    }

    public int paramInt(String key, int def) {
        return params.optInt(key, def);
    }

    public long paramLong(String key, long def) {
        return params.optLong(key, def);
    }
}