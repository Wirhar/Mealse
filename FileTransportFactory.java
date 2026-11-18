package com.my.mealse.filemanager;

import android.content.Context;
import com.my.mealse.filemanager.wifidirect.WifiDirectFileTransport;

import com.my.mealse.model.TrustedNode;
import com.my.mealse.util.MealseLogger;

/**
 * Фабрика транспортов.
 *
 * По узлу (TrustedNode) и режиму подключения решает, какой транспорт
 * использовать:
 *
 *   - WIFI_DIRECT          → WifiDirectFileTransport  (бинарный протокол)
 *   - AUTO / DIRECT        → HttpFileTransport        (REST 8084)
 *   - GATEWAY              → HttpFileTransport        (REST 8084 через VPN)
 *
 * Локальный транспорт (для этого устройства) тоже отдаётся отсюда
 * через createLocal() — просто для единообразия (возвращает null,
 * MAS_FileExplorer работает с java.io.File напрямую, без транспорта).
 *
 * Все транспорты кэшируются: на один узел — один экземпляр.
 * Закрываются через releaseAll() в onDestroy() файлового менеджера.
 */
public final class FileTransportFactory {

    private static final String TAG = "FileTransportFactory";

    private static volatile FileTransportFactory instance;

    private final Context appContext;
    private final MealseLogger log;

    // Кэш: sourceId -> транспорт
    private final java.util.concurrent.ConcurrentHashMap<String, FileTransport> cache =
            new java.util.concurrent.ConcurrentHashMap<>();

    private FileTransportFactory(Context context) {
        this.appContext = context.getApplicationContext();
        this.log = MealseLogger.get(appContext, TAG);
        log.log("FileTransportFactory создан");
    }

    public static FileTransportFactory get(Context context) {
        if (instance == null) {
            synchronized (FileTransportFactory.class) {
                if (instance == null) {
                    instance = new FileTransportFactory(context);
                }
            }
        }
        return instance;
    }

    // ============================================================
    //  Создание транспорта для TrustedNode
    // ============================================================

    /**
     * Возвращает транспорт для указанного узла. Если транспорт уже
     * был создан и не закрыт — возвращает из кэша.
     *
     * @throws FileTransportException если узел невалиден (нет IP/порта и т.п.)
     */
    public FileTransport forNode(TrustedNode node) throws FileTransportException {
        if (node == null) {
            throw new FileTransportException(
                    FileTransportException.CODE_INVALID_REQUEST, "node == null");
        }

        String sourceId = node.getKey();
        FileTransport existing = cache.get(sourceId);
        if (existing != null) {
            return existing;
        }

        synchronized (cache) {
            existing = cache.get(sourceId);
            if (existing != null) return existing;

            FileTransport fresh = createForNode(node);
            cache.put(sourceId, fresh);
            log.log("Создан транспорт для узла " + sourceId + " → " + fresh.describe());
            return fresh;
        }
    }

    private FileTransport createForNode(TrustedNode node) throws FileTransportException {
        TrustedNode.Mode mode = node.getMode();

        switch (mode) {
            case WIFI_DIRECT:
                return new WifiDirectFileTransport(appContext, node);
            case GATEWAY:
                return new HttpFileTransport(appContext, node, true);
            case DIRECT:
            case AUTO:
            default:
                return new HttpFileTransport(appContext, node, false);
        }
    }

    // ============================================================
    //  Освобождение
    // ============================================================

    /**
     * Закрыть и удалить транспорт для конкретного узла.
     */
    public void release(String sourceId) {
        if (sourceId == null) return;
        FileTransport t = cache.remove(sourceId);
        if (t != null) {
            try { t.close(); } catch (Throwable ignored) {}
            log.log("Транспорт закрыт: " + sourceId);
        }
    }

    /**
     * Закрыть все транспорты. Вызывать в onDestroy() файлового менеджера.
     */
    public void releaseAll() {
        for (java.util.Map.Entry<String, FileTransport> e : cache.entrySet()) {
            try { e.getValue().close(); } catch (Throwable ignored) {}
        }
        cache.clear();
        log.log("Все транспорты закрыты");
    }

    /**
     * Принудительно пересоздать транспорт для узла (например, после
     * смены IP при reconnect Wi-Fi Direct).
     */
    public void invalidate(String sourceId) {
        release(sourceId);
    }
}