package com.my.mealse.model;

import java.io.Serializable;
import java.util.List;

/**
 * Модель доверенного узла (устройства в сети Mealse).
 *
 * ★ МОДЕЛЬ (актуально): - Ключ узла = {@code (ip, port)}. Узел уникален по адресу. - Одно устройство ({@code deviceId}, {@code fingerprint}) может быть представлено НЕСКОЛЬКИМИ узлами с одним {@code id} и разными {@code ip:port} (Wi-Fi и 4G, смена сети). - {@code id} — атрибут устройства, НЕ уникален у узлов одного устройства. - {@code deviceId} и {@code fingerprint} — тоже атрибуты, могут дублироваться. - {@code name} — визуализация, пользователь сам различает. - IP НЕ переписывается автоматически: смена IP = новый узел. - {@code equals}/{@code hashCode} — по {@code (ip, port)}.
 *
 * ★ ИСТОРИЯ: - Ранее узел был уникален по {@code id}. Это приводило к тому, что одно устройство с Wi-Fi и 4G-адресами не различалось корректно. - Ранее {@code equals}/{@code hashCode} были по {@code id} — теперь по адресу.
 *
 * Логирование в модели НЕ используется — модель чистая, логирование выполняется на уровне NodeManager, SettingsActivity, CallActivity.
 */
public class TrustedNode implements Serializable {

  // ============================================================
  // enum режима подключения
  // ============================================================

  /**
   * Режим подключения к узлу.
   *
   * AUTO — пробуем все сценарии по приоритету: 1) IPv6 P2P 2) STUN P2P 3) host P2P (в одной сети) 4) через шлюз (если gatewayIp задан)
   *
   * DIRECT — только P2P (IPv6/STUN/host), без шлюза.
   *
   * GATEWAY — всегда через указанный gatewayIp.
   */
  public enum Mode {
    AUTO, DIRECT, GATEWAY, WIFI_DIRECT;

    public static Mode fromString(String s) {
      if (s == null || s.isEmpty())
        return AUTO;
      try {
        return Mode.valueOf(s.toUpperCase());
      } catch (IllegalArgumentException e) {
        return AUTO;
      }
    }

    public String toStorageString() {
      return this.name().toLowerCase();
    }
  }

  // ============================================================
  // Поля
  // ============================================================

  /** Идентификатор устройства. НЕ уникален у узлов одного устройства. */
  private String id;

  /** Отображаемое имя. Только визуализация. */
  private String name;

  /** Ключевая часть уникальности узла. */
  private String ipAddress;

  /** Ключевая часть уникальности узла. */
  private int port;

  /** ID устройства (ANDROID_ID). Атрибут, может дублироваться. */
  private String deviceId;

  /** SHA-256 от публичного ключа устройства. Атрибут, может дублироваться. */
  private String fingerprint;

  private long addedTimestamp;
  private boolean isGatewayEnabled;

  /**
   * Режим подключения к узлу. Default = AUTO. Gson: если поле отсутствует в JSON (старый узел) — после десериализации будет null. Геттер getMode() обработает null → AUTO.
   */
  private String mode;

  /**
   * IP шлюза для этого узла (если mode == GATEWAY или AUTO с fallback).
   */
  private String gatewayIp;

  /**
   * Порт шлюза. Default = 8083 (VPN-порт).
   */
  private int gatewayPort;

  /**
   * Флаг: узел когда-либо сообщал, что у него есть глобальный IPv6.
   */
  private boolean supportsIpv6;

  /**
   * Тип сети, в которой узел был замечен последний раз: "wifi", "cell", "ethernet", "unknown".
   */
  private String lastSeenNetwork;

  /**
   * Timestamp последнего контакта с узлом (мс).
   */
  private long lastSeenTimestamp;

  /**
   * Разрешён ли этому узлу доступ к файлам на этом устройстве через RemoteFsService. По умолчанию — false.
   */
  private boolean fileAccessGranted;

  /** Публичный IPv6 узла (2a00:...), если известен. */
  private String ipv6Address;

  /** Публичный IPv6 шлюза (если этот узел — шлюз или через него ходим). */
  private String gatewayIpv6;

  // ============================================================
  // Конструкторы
  // ============================================================

  public TrustedNode() {
    // Пустой конструктор для сериализации Gson
    this.gatewayPort = 8083;
  }

  public TrustedNode(String id, String name, String ipAddress, int port, String deviceId) {
    this(id, name, ipAddress, port, deviceId, null);
  }

  public TrustedNode(String id, String name, String ipAddress, int port, String deviceId,
      String fingerprint) {
    this.id = id;
    this.name = name;
    this.ipAddress = ipAddress;
    this.port = port;
    this.deviceId = deviceId;
    this.fingerprint = fingerprint;
    this.addedTimestamp = System.currentTimeMillis();
    this.isGatewayEnabled = false;

    this.mode = Mode.AUTO.toStorageString();
    this.gatewayIp = null;
    this.gatewayPort = 8083;
    this.supportsIpv6 = false;
    this.lastSeenNetwork = "unknown";
    this.lastSeenTimestamp = 0L;

    this.ipv6Address = null;
    this.gatewayIpv6 = null;
  }

  // ============================================================
  // Геттеры / сеттеры
  // ============================================================

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getIpAddress() {
    return ipAddress;
  }

  public void setIpAddress(String ipAddress) {
    this.ipAddress = ipAddress;
  }

  public int getPort() {
    return port;
  }

  public void setPort(int port) {
    this.port = port;
  }

  public String getDeviceId() {
    return deviceId;
  }

  public void setDeviceId(String deviceId) {
    this.deviceId = deviceId;
  }

  public String getFingerprint() {
    return fingerprint;
  }

  public void setFingerprint(String fingerprint) {
    this.fingerprint = fingerprint;
  }

  public long getAddedTimestamp() {
    return addedTimestamp;
  }

  public boolean isGatewayEnabled() {
    return isGatewayEnabled;
  }

  public void setGatewayEnabled(boolean gatewayEnabled) {
    this.isGatewayEnabled = gatewayEnabled;
  }

  public Mode getMode() {
    return Mode.fromString(mode);
  }

  public void setMode(Mode mode) {
    this.mode = (mode != null ? mode : Mode.AUTO).toStorageString();
  }

  public void setMode(String mode) {
    this.mode = Mode.fromString(mode).toStorageString();
  }

  public String getModeString() {
    return mode;
  }

  public String getGatewayIp() {
    return gatewayIp;
  }

  public void setGatewayIp(String gatewayIp) {
    this.gatewayIp = gatewayIp;
  }

  public int getGatewayPort() {
    return gatewayPort;
  }

  public void setGatewayPort(int gatewayPort) {
    this.gatewayPort = gatewayPort;
  }

  public boolean isSupportsIpv6() {
    return supportsIpv6;
  }

  public void setSupportsIpv6(boolean supportsIpv6) {
    this.supportsIpv6 = supportsIpv6;
  }

  public String getLastSeenNetwork() {
    return lastSeenNetwork;
  }

  public void setLastSeenNetwork(String lastSeenNetwork) {
    this.lastSeenNetwork = lastSeenNetwork;
  }

  public long getLastSeenTimestamp() {
    return lastSeenTimestamp;
  }

  public void setLastSeenTimestamp(long lastSeenTimestamp) {
    this.lastSeenTimestamp = lastSeenTimestamp;
  }

  public String getIpv6Address() {
    return ipv6Address;
  }

  public void setIpv6Address(String ipv6Address) {
    this.ipv6Address = ipv6Address;
  }

  public String getGatewayIpv6() {
    return gatewayIpv6;
  }

  public void setGatewayIpv6(String gatewayIpv6) {
    this.gatewayIpv6 = gatewayIpv6;
  }

  /** ★ Удобный флаг: есть ли у узла публичный IPv6. */
  public boolean hasIpv6() {
    return ipv6Address != null && !ipv6Address.isEmpty();
  }

  /** ★ Удобный флаг: известен ли IPv6 шлюза. */
  public boolean hasGatewayIpv6() {
    return gatewayIpv6 != null && !gatewayIpv6.isEmpty();
  }

  /**
   * Обновить timestamp последнего контакта.
   */
  public void touchLastSeen(String networkType) {
    this.lastSeenTimestamp = System.currentTimeMillis();
    if (networkType != null && !networkType.isEmpty()) {
      this.lastSeenNetwork = networkType;
    }
  }

  /**
   * Есть ли у узла настроенный шлюз.
   */
  public boolean hasGateway() {
    return gatewayIp != null && !gatewayIp.isEmpty() && gatewayPort > 0;
  }

  /**
   * ★ НОВОЕ: строковый ключ узла {@code "ip:port"}. Используется для логов и для быстрого сравнения.
   */
  public String getKey() {
    return (ipAddress != null ? ipAddress : "null") + ":" + port;
  }

  public boolean isFileAccessGranted() {
    return fileAccessGranted;
  }

  public void setFileAccessGranted(boolean fileAccessGranted) {
    this.fileAccessGranted = fileAccessGranted;
  }

  // ============================================================
  // equals / hashCode — по (ip, port)
  // ============================================================

  /**
   * ★ Ключ — {@code (ip, port)}, а не {@code id}. Два узла с одним {@code id}, но разными {@code ip:port} — РАЗНЫЕ. Два узла с одним {@code ip:port} — ОДИН и тот же.
   */
  @Override
  public boolean equals(Object obj) {
    if (this == obj)
      return true;
    if (obj == null || getClass() != obj.getClass())
      return false;
    TrustedNode that = (TrustedNode) obj;

    // Сравниваем по ip:port
    if (port != that.port)
      return false;
    if (ipAddress == null) {
      return that.ipAddress == null;
    }
    return ipAddress.equals(that.ipAddress);
  }

  @Override
  public int hashCode() {
    int result = ipAddress != null ? ipAddress.hashCode() : 0;
    result = 31 * result + port;
    return result;
  }

  // ============================================================
  // toString
  // ============================================================

  @Override
  public String toString() {
    return "TrustedNode{" + "key='" + getKey() + '\'' + ", id='" + id + '\'' + ", name='" + name
        + '\'' + ", ipAddress='" + ipAddress + '\'' + ", port=" + port + ", deviceId='" + deviceId
        + '\'' + ", fingerprint='" + fingerprint + '\'' + ", isGatewayEnabled=" + isGatewayEnabled
        + ", mode=" + getMode() + ", gatewayIp='" + gatewayIp + '\'' + ", gatewayPort="
        + gatewayPort + ", supportsIpv6=" + supportsIpv6 + ", lastSeenNetwork='" + lastSeenNetwork
        + '\'' + ", lastSeenTimestamp=" + lastSeenTimestamp + '\'' + ", fileAccessGranted="
        + fileAccessGranted + '}' + ", ipv6Address='" + ipv6Address + '\'' + ", gatewayIpv6='"
        + gatewayIpv6 + '\'';
  }
}