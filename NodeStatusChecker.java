package com.my.mealse.util;

import android.os.Handler;
import android.os.Looper;

import com.my.mealse.adapter.NodeAdapter.NodeStatus;
import com.my.mealse.model.TrustedNode;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Проверяет доступность узлов в фоне.
 *
 * Использует Socket.connect() с таймаутом.
 * Результат передаёт через callback в UI-поток.
 */
public class NodeStatusChecker {

  private static final int TIMEOUT_MS = 2000;

  public interface StatusCallback {
    void onStatus(String nodeId, NodeStatus status);
  }

  private final ExecutorService executor = Executors.newFixedThreadPool(3);
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final MealseLogger log;

  public NodeStatusChecker() {
    this.log = null; // логирование опционально
  }

  /**
   * Проверяет узел и вызывает callback в UI-потоке.
   */
  public void check(TrustedNode node, StatusCallback callback) {
    if (node == null) return;

    final String nodeId = node.getId();
    final String ip = node.getIpAddress();
    final int port = node.getPort();

    executor.submit(() -> {
      NodeStatus status = doCheck(ip, port);
      if (callback != null) {
        mainHandler.post(() -> callback.onStatus(nodeId, status));
      }
    });
  }

  /**
   * Синхронная проверка.
   */
  private NodeStatus doCheck(String ip, int port) {
    if (ip == null || ip.isEmpty()) return NodeStatus.UNREACHABLE;

    Socket socket = null;
    try {
      socket = new Socket();
      socket.connect(new InetSocketAddress(ip, port), TIMEOUT_MS);
      return NodeStatus.ONLINE;
    } catch (SocketTimeoutException e) {
      return NodeStatus.UNREACHABLE;
    } catch (ConnectException e) {
      String msg = e.getMessage() != null ? e.getMessage() : "";
      if (msg.contains("ECONNREFUSED") || msg.contains("Connection refused")) {
        return NodeStatus.REFUSED;
      }
      return NodeStatus.UNREACHABLE;
    } catch (UnknownHostException e) {
      return NodeStatus.UNREACHABLE;
    } catch (Exception e) {
      return NodeStatus.UNREACHABLE;
    } finally {
      if (socket != null) {
        try {
          socket.close();
        } catch (Exception ignored) {}
      }
    }
  }

  /**
   * Останавливает executor.
   */
  public void shutdown() {
    try {
      executor.shutdownNow();
    } catch (Exception ignored) {}
  }
}