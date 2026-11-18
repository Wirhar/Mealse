package com.my.mealse.util;

import android.content.Context;

import com.my.mealse.filemanager.server.RemoteFsAuth;
import com.my.mealse.model.TrustedNode;

import java.util.List;

/**
 * Обёртка над RemoteFsAuth для UI-слоя.
 *
 * Даёт MainActivity простые методы:
 *   - isGranted(nodeKey)      — узнать, разрешён ли доступ узлу
 *   - grant(nodeKey)          — разрешить
 *   - revoke(nodeKey)         — отозвать
 *   - getGrantedCount()       — сколько узлов имеют доступ
 *
 * Плюс метод summary() для логов.
 */
public class FileAccessManager {

    private static final String TAG = "FileAccessManager";

    private final Context appContext;
    private final MealseLogger log;
    private final RemoteFsAuth auth;
    private final NodeManager nodeManager;

    public FileAccessManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.log = MealseLogger.get(appContext, TAG);
        this.auth = new RemoteFsAuth(appContext);
        this.nodeManager = new NodeManager(appContext);
    }

    /** Разрешён ли узлу доступ к файлам на этом устройстве. */
    public boolean isGranted(String nodeKey) {
        return auth.isGranted(nodeKey);
    }

    /** Разрешить доступ. */
    public boolean grant(String nodeKey) {
        boolean ok = auth.grantAccess(nodeKey);
        if (ok) log.log("grant: " + nodeKey);
        else log.warn("grant FAILED: " + nodeKey);
        return ok;
    }

    /** Отозвать доступ. */
    public boolean revoke(String nodeKey) {
        boolean ok = auth.revokeAccess(nodeKey);
        if (ok) log.log("revoke: " + nodeKey);
        else log.warn("revoke FAILED: " + nodeKey);
        return ok;
    }

    /** Сколько узлов имеют доступ к файлам. */
    public int getGrantedCount() {
        int n = 0;
        List<TrustedNode> all = nodeManager.getAllNodes();
        for (TrustedNode node : all) {
            if (node.isFileAccessGranted()) n++;
        }
        return n;
    }

    /** Сводка для логов: список узлов с доступом. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        List<TrustedNode> all = nodeManager.getAllNodes();
        for (TrustedNode node : all) {
            if (node.isFileAccessGranted()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(node.getName()).append("(").append(node.getKey()).append(")");
            }
        }
        return sb.length() == 0 ? "(нет)" : sb.toString();
    }
}