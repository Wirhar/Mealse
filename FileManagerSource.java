package com.my.mealse.filemanager.ui;

import com.my.mealse.model.TrustedNode;

/**
 * Модель «источника» для Spinner в файловом менеджере.
 *
 * Локальный источник — это само устройство, работаем через java.io.File. Удалённый — узел, работаем через FileTransport.
 */
public class FileManagerSource {

  public enum Type {
    LOCAL, LOCAL_APP, REMOTE
  }

  public final Type type;
  public final TrustedNode node; // null для LOCAL
  public final String displayName;
  /** Корневой путь для источника. */
  public final String rootPath;

  private FileManagerSource(Type type, TrustedNode node, String displayName, String rootPath) {
    this.type = type;
    this.node = node;
    this.displayName = displayName;
    this.rootPath = rootPath;
  }

  /** Локальный источник — внутренняя память приложения. */
  public static FileManagerSource localApp(String rootPath) {
    return new FileManagerSource(Type.LOCAL_APP, null, "📦 Приложение", rootPath);
  }

  public static FileManagerSource local(String rootPath) {
    return new FileManagerSource(Type.LOCAL, null, "📱 Это устройство", rootPath);
  }

  public static FileManagerSource remote(TrustedNode node, String rootPath) {
    String name = node.getName() != null ? node.getName() : node.getKey();
    String icon;
    switch (node.getMode()) {
      case WIFI_DIRECT:
        icon = "📡";
        break;
      case GATEWAY:
        icon = "🌐";
        break;
      case DIRECT:
        icon = "🔵";
        break;
      default:
        icon = "🟢";
        break;
    }
    return new FileManagerSource(Type.REMOTE, node, icon + " " + name, rootPath);
  }

  public boolean isLocal() {
    return type == Type.LOCAL || type == Type.LOCAL_APP;
  }

  public boolean isLocalExternal() {
    return type == Type.LOCAL;
  }

  public boolean isLocalApp() {
    return type == Type.LOCAL_APP;
  }

  public boolean isRemote() {
    return type == Type.REMOTE;
  }

  @Override
  public String toString() {
    return displayName;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o)
      return true;
    if (!(o instanceof FileManagerSource))
      return false;
    FileManagerSource that = (FileManagerSource) o;
    if (type != that.type)
      return false;
    if (node == null)
      return that.node == null;
    return node.getKey().equals(that.node.getKey());
  }

  @Override
  public int hashCode() {
    int h = type.hashCode();
    h = 31 * h + (node != null ? node.getKey().hashCode() : 0);
    return h;
  }

  /**
   * Уникальный строковый ключ источника. Используется для сохранения состояния после поворота экрана.
   *
   * LOCAL → "local" LOCAL_APP → "local_app" REMOTE → "node:<ip:port>"
   */
  public String key() {
    switch (type) {
      case LOCAL:
        return "local";
      case LOCAL_APP:
        return "local_app";
      case REMOTE:
        return "node:" + (node != null ? node.getKey() : "");
      default:
        return "unknown";
    }
  }
}