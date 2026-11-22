package com.my.mealse.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.my.mealse.model.TrustedNode;
import com.my.mealse.util.NodeManager;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * ★ МОДЕЛЬ: узел уникален по {@code (ip, port)}, а не по {@code id}.
 *
 * Правила: - {@code id} — атрибут устройства, может быть ОДИНАКОВЫМ у нескольких узлов одного устройства (Wi-Fi и 4G, смена сети). - {@code deviceId} и {@code fingerprint} — тоже атрибуты, могут дублироваться. - Уникальность узла = пара {@code (ip, port)}. - {@code saveNode} ищет узел по {@code (ip, port)}: нашёл — обновляет, не нашёл — добавляет. - IP НЕ переписывается автоматически. Смена IP = новый узел. - {@code updateIpIfStale} и {@code updateNodeIpPort} УДАЛЕНЫ. - {@code deleteNode(ip, port)} — удаляет один узел. - {@code deleteNode(id, ip, port)} — deprecated, оставлен для совместимости. - {@code deleteNode(id)} — deprecated, удаляет первый по id. - Self-узлы (по fingerprint / deviceId / ip) не отдаются в {@code getAllNodes}, но и не удаляются из хранилища молча. - {@code name} — только визуализация.
 */
public class NodeManager {

  private static final String TAG = "NodeManager";
  private static final String PREF_NAME = "mealse_nodes";
  private static final String KEY_NODES = "trusted_nodes_list";
  private NodeManager nodeManager;

  private final SharedPreferences prefs;
  private final Gson gson;
  private final MealseLogger log;

  private final Context appContext;
  private volatile String cachedSelfFingerprint = null;
  private volatile String cachedSelfDeviceId = null;
  private volatile String cachedSelfIp = null;
  private volatile boolean selfCacheInitialized = false;

  public NodeManager(Context context) {
    this.appContext = context.getApplicationContext();
    prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    gson = new Gson();
    log = MealseLogger.get(appContext, TAG);
    log.log("══ NodeManager создан ══");
    log.log("Prefs name=" + PREF_NAME + ", key=" + KEY_NODES);
  }

  // ================= Self-данные =================

  private void ensureSelfCache() {
    if (selfCacheInitialized)
      return;
    synchronized (this) {
      if (selfCacheInitialized)
        return;
      try {
        SecureAuthManager auth = new SecureAuthManager(appContext);
        cachedSelfFingerprint = auth.getDeviceFingerprint();
      } catch (Exception e) {
        log.logError("ensureSelfCache: не удалось получить self fingerprint", e);
        cachedSelfFingerprint = null;
      }
      try {
        cachedSelfDeviceId = DeviceInfoHelper.getDeviceId(appContext);
      } catch (Exception e) {
        log.logError("ensureSelfCache: не удалось получить self deviceId", e);
        cachedSelfDeviceId = null;
      }
      try {
        cachedSelfIp = DeviceInfoHelper.getSelfIp(appContext);
      } catch (Exception e) {
        log.logError("ensureSelfCache: не удалось получить self IP", e);
        cachedSelfIp = null;
      }
      selfCacheInitialized = true;
      log.log("ensureSelfCache: selfFp=" + cachedSelfFingerprint + ", selfDeviceId="
          + cachedSelfDeviceId + ", selfIp=" + cachedSelfIp);
    }
  }

  public void invalidateSelfCache() {
    synchronized (this) {
      selfCacheInitialized = false;
      cachedSelfFingerprint = null;
      cachedSelfDeviceId = null;
      cachedSelfIp = null;
      log.log("invalidateSelfCache: self-кэш сброшен");
    }
  }

  public boolean isSelfNode(TrustedNode n) {
    if (n == null)
      return false;
    ensureSelfCache();
    if (cachedSelfFingerprint != null && cachedSelfFingerprint.equals(n.getFingerprint())) {
      return true;
    }
    if (cachedSelfDeviceId != null && cachedSelfDeviceId.equals(n.getDeviceId())) {
      return true;
    }
    if (cachedSelfIp != null && cachedSelfIp.equals(n.getIpAddress())) {
      return true;
    }
    return false;
  }

  // ================= Чтение =================

  /**
   * Возвращает все узлы, КРОМЕ self. ★ Узлы с одинаковым (id) и разными (ip:port) отдаются оба.
   */
  /**
   * ★ ИЗМЕНЕНО: getAllNodes больше не логирует каждый узел при каждом вызове (P2.7 из ошибки.docx — «логируется каждый узел каждые 2 с»).
   *
   * Теперь: - log.log("getAllNodes: узлов=N") — только сводка; - детально (key, id, name, ...) — только если изменился набор (hash от (key+mode+lastSeenTimestamp)).
   */
  private volatile int lastNodesLogHash = 0;

  public List<TrustedNode> getAllNodes() {
    String json = prefs.getString(KEY_NODES, null);
    if (json == null) {
      log.log("getAllNodes: prefs пусты → возвращаю []");
      return new ArrayList<>();
    }
    Type type = new TypeToken<List<TrustedNode>>() {
    }.getType();
    List<TrustedNode> nodes = gson.fromJson(json, type);
    if (nodes == null) {
      log.warn("getAllNodes: gson вернул null → []");
      return new ArrayList<>();
    }

    ensureSelfCache();
    List<TrustedNode> cleaned = new ArrayList<>(nodes.size());
    int selfFiltered = 0;
    for (TrustedNode n : nodes) {
      boolean isSelf = (cachedSelfFingerprint != null
          && cachedSelfFingerprint.equals(n.getFingerprint()))
          || (cachedSelfDeviceId != null && cachedSelfDeviceId.equals(n.getDeviceId()))
          || (cachedSelfIp != null && cachedSelfIp.equals(n.getIpAddress()));
      if (isSelf) {
        selfFiltered++;
        log.warn("getAllNodes: self-узел отфильтрован (в хранилище остаётся): key=" + n.getKey()
            + ", name=" + n.getName() + ", fp=" + n.getFingerprint() + ", deviceId="
            + n.getDeviceId());
      } else {
        cleaned.add(n);
      }
    }

    if (selfFiltered > 0) {
      log.warn("getAllNodes: отфильтровано self-узлов: " + selfFiltered);
    }

    sortNodesByRelevance(cleaned);

    // ★ НОВОЕ: логируем только сводку + детали при изменении
    log.log("getAllNodes: узлов=" + cleaned.size() + " (self отфильтровано=" + selfFiltered + ")");

    int hash = 17;
    for (TrustedNode n : cleaned) {
      hash = 31 * hash + (n.getKey() != null ? n.getKey().hashCode() : 0);
      hash = 31 * hash + (n.getMode() != null ? n.getMode().hashCode() : 0);
      hash = 31 * hash + (int) (n.getLastSeenTimestamp() & 0xFFFFFFFFL);
    }
    if (hash != lastNodesLogHash) {
      lastNodesLogHash = hash;
      for (TrustedNode n : cleaned) {
        log.log("  node key=" + n.getKey() + ", id=" + n.getId() + ", name=" + n.getName() + ", fp="
            + n.getFingerprint() + ", deviceId=" + n.getDeviceId() + ", mode=" + n.getMode()
            + ", gatewayIp=" + n.getGatewayIp() + ", lastSeenTimestamp="
            + n.getLastSeenTimestamp());
      }
    }
    return cleaned;
  }

  private void sortNodesByRelevance(List<TrustedNode> nodes) {
    Collections.sort(nodes, new Comparator<TrustedNode>() {
      @Override
      public int compare(TrustedNode a, TrustedNode b) {
        // 1) Шлюзы сверху
        boolean aGw = a.hasGateway() || a.isGatewayEnabled();
        boolean bGw = b.hasGateway() || b.isGatewayEnabled();
        if (aGw != bGw) {
          return aGw ? -1 : 1;
        }
        // 2) По lastSeenTimestamp (убывание)
        long aTs = a.getLastSeenTimestamp();
        long bTs = b.getLastSeenTimestamp();
        if (aTs != bTs) {
          return Long.compare(bTs, aTs);
        }
        // 3) По имени
        String an = a.getName() != null ? a.getName() : "";
        String bn = b.getName() != null ? b.getName() : "";
        int cmpName = an.compareToIgnoreCase(bn);
        if (cmpName != 0)
          return cmpName;
        // 4) По (ip, port) — стабильный последний критерий
        String aKey = a.getKey();
        String bKey = b.getKey();
        return aKey.compareTo(bKey);
      }
    });
    log.log("sortNodesByRelevance: отсортировано " + nodes.size() + " узлов");
  }

  /**
   * @Deprecated используйте {@link #findByIpPort(String, int)}. Возвращает ПЕРВЫЙ узел с указанным id (id больше не уникален).
   */
  @Deprecated
  public TrustedNode getNodeById(String id) {
    if (id == null) {
      log.warn("getNodeById: id=null");
      return null;
    }
    for (TrustedNode n : getAllNodes()) {
      if (id.equals(n.getId())) {
        log.log("getNodeById: найден id=" + id + " → " + n.getKey());
        return n;
      }
    }
    log.warn("getNodeById: не найден id=" + id);
    return null;
  }

  /**
   * ★ ОСНОВНОЙ поиск по паре {@code (ip, port)}.
   */
  public TrustedNode findByIpPort(String ip, int port) {
    if (ip == null) {
      log.warn("findByIpPort: ip=null");
      return null;
    }
    for (TrustedNode n : getAllNodes()) {
      if (ip.equals(n.getIpAddress()) && n.getPort() == port) {
        log.log("findByIpPort: найден " + ip + ":" + port + " → id=" + n.getId() + ", name="
            + n.getName());
        return n;
      }
    }
    log.warn("findByIpPort: не найден " + ip + ":" + port);
    return null;
  }

  public TrustedNode findByFingerprint(String fingerprint) {
    if (fingerprint == null) {
      log.warn("findByFingerprint: fingerprint=null");
      return null;
    }
    for (TrustedNode n : getAllNodes()) {
      if (fingerprint.equals(n.getFingerprint())) {
        log.log("findByFingerprint: найден fingerprint=" + fingerprint + " → key=" + n.getKey());
        return n;
      }
    }
    log.warn("findByFingerprint: не найден fingerprint=" + fingerprint);
    return null;
  }

  public List<TrustedNode> findAllByFingerprint(String fingerprint) {
    List<TrustedNode> result = new ArrayList<>();
    if (fingerprint == null || fingerprint.isEmpty())
      return result;
    for (TrustedNode n : getAllNodes()) {
      if (fingerprint.equals(n.getFingerprint())) {
        result.add(n);
      }
    }
    log.log("findAllByFingerprint(" + fingerprint + "): найдено " + result.size() + " узлов");
    return result;
  }

  public TrustedNode findByDeviceId(String deviceId) {
    if (deviceId == null || deviceId.isEmpty()) {
      return null;
    }
    for (TrustedNode n : getAllNodes()) {
      if (deviceId.equals(n.getDeviceId())) {
        log.log("findByDeviceId: найден deviceId=" + deviceId + " → key=" + n.getKey());
        return n;
      }
    }
    log.log("findByDeviceId: не найден deviceId=" + deviceId);
    return null;
  }

  public List<TrustedNode> findAllByDeviceId(String deviceId) {
    List<TrustedNode> result = new ArrayList<>();
    if (deviceId == null || deviceId.isEmpty())
      return result;
    for (TrustedNode n : getAllNodes()) {
      if (deviceId.equals(n.getDeviceId())) {
        result.add(n);
      }
    }
    log.log("findAllByDeviceId(" + deviceId + "): найдено " + result.size() + " узлов");
    return result;
  }

  public List<TrustedNode> getGatewayNodes() {
    List<TrustedNode> all = getAllNodes();
    List<TrustedNode> gateways = new ArrayList<>();
    for (TrustedNode n : all) {
      if (n.hasGateway() || n.isGatewayEnabled()) {
        gateways.add(n);
      }
    }
    log.log("getGatewayNodes: найдено " + gateways.size() + " шлюзов из " + all.size() + " узлов");
    return gateways;
  }

  public TrustedNode getNodeByGateway(String gwIp, int gwPort) {
    if (gwIp == null || gwIp.isEmpty()) {
      log.warn("getNodeByGateway: gwIp=null");
      return null;
    }
    for (TrustedNode n : getAllNodes()) {
      if (gwIp.equals(n.getGatewayIp()) && n.getGatewayPort() == gwPort) {
        log.log("getNodeByGateway: найден узел " + n.getName() + " (key=" + n.getKey()
            + "), использующий шлюз " + gwIp + ":" + gwPort);
        return n;
      }
    }
    log.log("getNodeByGateway: нет узлов с шлюзом " + gwIp + ":" + gwPort);
    return null;
  }

  // ================= updateNodeMode =================

  /**
   * ★ Обновляет режим ТОЛЬКО того узла, у которого совпала тройка {@code (id, ip, port)}.
   */
  public boolean updateNodeMode(String id, String ip, int port, TrustedNode.Mode mode,
      String gatewayIp, int gatewayPort) {
    if (id == null || ip == null || mode == null) {
      log.warn("updateNodeMode: id/ip/mode == null");
      return false;
    }

    List<TrustedNode> nodes = loadRaw();
    boolean changed = false;
    for (TrustedNode n : nodes) {
      if (id.equals(n.getId()) && ip.equals(n.getIpAddress()) && n.getPort() == port) {
        log.log("updateNodeMode: узел id=" + id + " (" + n.getName() + ", " + n.getKey() + ")"
            + " mode=" + n.getMode() + " → " + mode + ", gateway " + n.getGatewayIp() + ":"
            + n.getGatewayPort() + " → " + gatewayIp + ":" + gatewayPort);
        n.setMode(mode);
        n.setGatewayIp(gatewayIp);
        n.setGatewayPort(gatewayPort > 0 ? gatewayPort : 8083);
        changed = true;
        break;
      }
    }
    if (changed) {
      saveRaw(nodes);
      log.log("updateNodeMode: сохранено, узлов=" + nodes.size());
    } else {
      log.warn("updateNodeMode: узел (id=" + id + ", ip=" + ip + ":" + port + ") не найден");
    }
    return changed;
  }

  /**
   * @Deprecated используйте {@link #updateNodeMode(String, String, int, TrustedNode.Mode, String, int)}.
   */
  @Deprecated
  public boolean updateNodeMode(String nodeId, TrustedNode.Mode mode, String gatewayIp,
      int gatewayPort) {
    if (nodeId == null || mode == null) {
      log.warn("updateNodeMode(deprecated): nodeId или mode == null");
      return false;
    }

    List<TrustedNode> nodes = loadRaw();
    boolean changed = false;
    for (TrustedNode n : nodes) {
      if (nodeId.equals(n.getId())) {
        log.warn("updateNodeMode(deprecated): обновляю ПЕРВЫЙ узел с id=" + nodeId + " (name="
            + n.getName() + ", key=" + n.getKey() + ")" + " mode=" + n.getMode() + " → " + mode);
        n.setMode(mode);
        n.setGatewayIp(gatewayIp);
        n.setGatewayPort(gatewayPort > 0 ? gatewayPort : 8083);
        changed = true;
        break;
      }
    }
    if (changed) {
      saveRaw(nodes);
    } else {
      log.warn("updateNodeMode(deprecated): узел id=" + nodeId + " не найден");
    }
    return changed;
  }

  public boolean updateNodeNetworkInfo(String fingerprint, String networkType,
      boolean supportsIpv6) {
    if (fingerprint == null || fingerprint.isEmpty()) {
      log.warn("updateNodeNetworkInfo: fingerprint пустой");
      return false;
    }

    List<TrustedNode> nodes = loadRaw();
    boolean changed = false;
    for (TrustedNode n : nodes) {
      if (fingerprint.equals(n.getFingerprint())) {
        log.log("updateNodeNetworkInfo: узел " + n.getName() + " (key=" + n.getKey() + ")"
            + " networkType=" + n.getLastSeenNetwork() + " → " + networkType + ", supportsIpv6="
            + n.isSupportsIpv6() + " → " + supportsIpv6);
        n.touchLastSeen(networkType);
        n.setSupportsIpv6(supportsIpv6);
        changed = true;
      }
    }
    if (changed) {
      saveRaw(nodes);
    } else {
      log.log("updateNodeNetworkInfo: узел с fingerprint=" + fingerprint + " не найден");
    }
    return changed;
  }

  // ================= Запись =================

  /**
   * ★ Основной метод сохранения.
   *
   * Логика: - Отказываемся сохранять self-узел (по fingerprint или deviceId). - Если узел с такой же парой (ip, port) существует — обновляем его. При этом НЕ затираем id/deviceId/fingerprint, если в новом они пустые. - Иначе — ДОБАВЛЯЕМ новый узел. Дубли по id/deviceId/fingerprint с разными (ip:port) РАЗРЕШЕНЫ.
   */
  public boolean saveNode(TrustedNode node, String selfFingerprint, String selfDeviceId) {
    if (node == null) {
      log.warn("saveNode: node=null");
      return false;
    }

    if (selfFingerprint != null && selfFingerprint.equals(node.getFingerprint())) {
      log.warn("saveNode: отклонено — fingerprint узла совпадает с self (" + selfFingerprint + ")");
      return false;
    }
    if (selfDeviceId != null && selfDeviceId.equals(node.getDeviceId())) {
      log.warn("saveNode: отклонено — deviceId узла совпадает с self (" + selfDeviceId + ")");
      return false;
    }

    if (node.getIpAddress() == null || node.getIpAddress().isEmpty()) {
      log.warn("saveNode: ipAddress пуст — отклонено");
      return false;
    }

    if (node.getId() == null || node.getId().isEmpty()) {
      node.setId(UUID.randomUUID().toString());
      log.log("saveNode: сгенерирован id=" + node.getId());
    }

    node.touchLastSeen(node.getLastSeenNetwork());

    List<TrustedNode> nodes = loadRaw();
    int before = nodes.size();

    boolean replaced = false;
    for (int i = 0; i < nodes.size(); i++) {
      TrustedNode existing = nodes.get(i);
      boolean sameAddr = node.getIpAddress().equals(existing.getIpAddress())
          && node.getPort() == existing.getPort();

      if (sameAddr) {
        // ★ Обновляем существующий узел — сохраняем поля, если в новом пустые.
        if (node.getId() == null || node.getId().isEmpty()) {
          node.setId(existing.getId());
        }
        if (node.getDeviceId() == null || node.getDeviceId().isEmpty()) {
          node.setDeviceId(existing.getDeviceId());
        }
        if (node.getFingerprint() == null || node.getFingerprint().isEmpty()) {
          node.setFingerprint(existing.getFingerprint());
        }
        // ★ НОВОЕ: name — только визуализация, но если в новом пустое —
        // сохраняем из existing. Иначе UI показывает «null».
        if (node.getName() == null || node.getName().isEmpty()) {
          node.setName(existing.getName());
        }

        log.log("saveNode: обновляю существующий узел (key=" + existing.getKey() + ")" + ", id="
            + node.getId() + ", name=" + existing.getName() + " → " + node.getName());
        nodes.set(i, node);
        replaced = true;
        break;
      }
    }

    if (!replaced) {
      log.log("saveNode: добавляю новый узел id=" + node.getId() + ", deviceId="
          + node.getDeviceId() + ", key=" + node.getKey());
      nodes.add(node);
    }

    saveRaw(nodes);

    log.log("saveNode: key=" + node.getKey() + ", id=" + node.getId() + ", name=" + node.getName()
        + ", deviceId=" + node.getDeviceId() + ", mode=" + node.getMode() + " (было " + before
        + ", стало " + nodes.size() + (replaced ? ", replace" : ", append") + ")");
    return true;
  }

  /**
   * @Deprecated используйте {@link #saveNode(TrustedNode, String, String)}. Self-данные берутся автоматически через ensureSelfCache().
   */
  @Deprecated
  public boolean saveNode(TrustedNode node) {
    ensureSelfCache();
    return saveNode(node, cachedSelfFingerprint, cachedSelfDeviceId);
  }

  /**
   * ★ НОВОЕ: удаляет ТОЛЬКО узел с совпавшей парой {@code (ip, port)}.
   */
  public void deleteNode(String ip, int port) {
    if (ip == null) {
      log.warn("deleteNode(ip, port): ip=null");
      return;
    }
    List<TrustedNode> nodes = loadRaw();
    int before = nodes.size();
    boolean removed = false;
    for (int i = 0; i < nodes.size(); i++) {
      TrustedNode n = nodes.get(i);
      if (ip.equals(n.getIpAddress()) && n.getPort() == port) {
        nodes.remove(i);
        removed = true;
        break;
      }
    }
    if (removed) {
      saveRaw(nodes);
      log.log("deleteNode: удалён узел (ip=" + ip + ":" + port + ")" + " (было " + before
          + ", стало " + nodes.size() + ")");
    } else {
      log.warn("deleteNode: узел (ip=" + ip + ":" + port + ") не найден");
    }
  }

  /**
   * @Deprecated используйте {@link #deleteNode(String, int)}. Оставлен для совместимости с SettingsActivity.
   */
  @Deprecated
  public void deleteNode(String id, String ip, int port) {
    // Делегируем в удаление по (ip, port) — id больше не ключ.
    deleteNode(ip, port);
  }

  /**
   * @Deprecated используйте {@link #deleteNode(String, int)}.
   */
  @Deprecated
  public void deleteNode(String id) {
    if (id == null) {
      log.warn("deleteNode(id): id=null");
      return;
    }
    List<TrustedNode> nodes = loadRaw();
    int before = nodes.size();
    boolean removed = false;
    for (int i = 0; i < nodes.size(); i++) {
      TrustedNode n = nodes.get(i);
      if (id.equals(n.getId())) {
        nodes.remove(i);
        removed = true;
        break;
      }
    }
    if (removed) {
      saveRaw(nodes);
      log.log("deleteNode(id): удалён первый узел с id=" + id + " (было " + before + ", стало "
          + nodes.size() + ")");
    } else {
      log.warn("deleteNode(id): id=" + id + " не найден");
    }
  }

  /**
   * @deprecated используйте {@link #deleteNode(String, int)}
   */
  @Deprecated
  public void removeNode(String nodeId) {
    deleteNode(nodeId);
  }

  public void clearAllNodes() {
    prefs.edit().remove(KEY_NODES).apply();
    log.log("clearAllNodes: список узлов очищен");
  }

  // ================= Сырые чтение/запись =================

  private List<TrustedNode> loadRaw() {
    String json = prefs.getString(KEY_NODES, null);
    if (json == null)
      return new ArrayList<>();
    Type type = new TypeToken<List<TrustedNode>>() {
    }.getType();
    List<TrustedNode> nodes = gson.fromJson(json, type);
    return nodes != null ? nodes : new ArrayList<>();
  }

  private void saveRaw(List<TrustedNode> nodes) {
    String json = gson.toJson(nodes);
    prefs.edit().putString(KEY_NODES, json).apply();
    log.log("saveRaw: сохранено узлов=" + nodes.size() + ", json="
        + (json.length() > 200 ? json.substring(0, 200) + "…" : json));
  }

  /**
   * ★ НОВОЕ: универсальный поиск по nodeKey.
   *
   * Поддерживает два формата: 1. "ip:port" — стандартный HTTP-ключ (192.168.1.123:8081); 2. "mac:0" — WD-ключ (MAC-адрес как deviceId, порт 0).
   *
   * Логика: - если порт парсится как число и ip — валидный IP → findByIpPort; - иначе (или если findByIpPort вернул null) → findAllByDeviceId(mac) и первый WD-узел (или первый вообще).
   *
   * @param nodeKey "ip:port" или "mac:0"
   * @return TrustedNode или null
   */
  public TrustedNode findByKey(String nodeKey) {
    if (nodeKey == null || nodeKey.isEmpty())
      return null;
    int idx = nodeKey.lastIndexOf(':');
    if (idx <= 0)
      return null;
    String ipOrMac = nodeKey.substring(0, idx);
    String portStr = nodeKey.substring(idx + 1);

    // 1. Пробуем как ip:port
    try {
      int port = Integer.parseInt(portStr);
      TrustedNode byIp = findByIpPort(ipOrMac, port);
      if (byIp != null) {
        return byIp;
      }
    } catch (NumberFormatException ignored) {
      // порт не число — это не ip:port
    }

    // 2. Fallback: WD-ключ "mac:0" — ищем по deviceId == MAC
    try {
      List<TrustedNode> byDevice = findAllByDeviceId(ipOrMac);
      if (byDevice != null && !byDevice.isEmpty()) {
        // Приоритет WD-узлу
        for (TrustedNode n : byDevice) {
          if (n.getMode() == TrustedNode.Mode.WIFI_DIRECT && n.getIpAddress() != null
              && !n.getIpAddress().isEmpty()) {
            log.log("findByKey: " + nodeKey + " → WD узел " + n.getKey());
            return n;
          }
        }
        log.log("findByKey: " + nodeKey + " → первый из " + byDevice.size() + " по deviceId="
            + ipOrMac);
        return byDevice.get(0);
      }
    } catch (Exception e) {
      log.warn("findByKey: " + nodeKey + " — " + e.getMessage());
    }

    log.warn("findByKey: " + nodeKey + " не найден");
    return null;
  }

  /**
   * ★ НОВОЕ: все узлы по nodeKey (для failover / retry).
   *
   * - "ip:port" → 1 узел (уникален по (ip, port)); - "mac:0" → все узлы с deviceId == mac (HTTP + WD).
   *
   * Используется в DeliveryScheduler / MessageCommands для перебора альтернативных транспортов одного устройства.
   */
  public List<TrustedNode> findAllByKey(String nodeKey) {
    List<TrustedNode> result = new ArrayList<>();
    if (nodeKey == null || nodeKey.isEmpty())
      return result;
    int idx = nodeKey.lastIndexOf(':');
    if (idx <= 0)
      return result;
    String ipOrMac = nodeKey.substring(0, idx);
    String portStr = nodeKey.substring(idx + 1);

    // 1. Если это ip:port — один узел
    try {
      int port = Integer.parseInt(portStr);
      TrustedNode byIp = findByIpPort(ipOrMac, port);
      if (byIp != null) {
        result.add(byIp);
        return result;
      }
    } catch (NumberFormatException ignored) {
    }

    // 2. Иначе — все по deviceId == mac
    List<TrustedNode> byDevice = findAllByDeviceId(ipOrMac);
    if (byDevice != null) {
      result.addAll(byDevice);
    }
    log.log("findAllByKey: " + nodeKey + " → " + result.size() + " узлов");
    return result;
  }

}