package com.my.mealse.filemanager.server;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.my.mealse.R;
import com.my.mealse.util.MealseLogger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RemoteFsService — HTTP-сервер на порту 8084, отдающий файловые операции удалённым узлам Mealse.
 *
 * ★ РЕВИЗИЯ (Шаг 7): - Добавлен ACTION_QUERY_STATUS для запроса состояния из UI. - Добавлен ACTION_FS_STATE broadcast — MainActivity видит статус. - При старте/остановке шлём broadcast. - Работает только с getFilesDir() (см. RemoteFsPathGuard).
 */
public class RemoteFsService extends Service {

  private static final String TAG = "RemoteFsService";

  public static final int PORT = 8084;

  // ===== Actions =====
  public static final String ACTION_START = "com.my.mealse.fs.START";
  public static final String ACTION_STOP = "com.my.mealse.fs.STOP";
  public static final String ACTION_QUERY_STATUS = "com.my.mealse.fs.QUERY_STATUS";

  // ===== Broadcast для UI =====
  public static final String ACTION_FS_STATE = "com.my.mealse.FS_STATE";
  public static final String EXTRA_STATE = "fs_state";
  public static final String EXTRA_MESSAGE = "fs_message";
  public static final String STATE_RUNNING = "running";
  public static final String STATE_STOPPED = "stopped";
  public static final String STATE_ERROR = "error";

  private static final String CHANNEL_ID = "MealseFsServerChannel";
  private static final int NOTIF_ID = 105;

  private static final int MAX_HEADER_SIZE = 16 * 1024;
  private static final int MAX_BODY_SIZE = 1024 * 1024;
  private static final int SOCKET_TIMEOUT_MS = 30_000;
  private static final int BUFFER_SIZE = 64 * 1024;

  private final AtomicInteger activeConnections = new AtomicInteger(0);
  private final ExecutorService handlerPool = Executors.newCachedThreadPool();
  private volatile ServerSocket serverSocket;
  private volatile boolean running = false;

  private MealseLogger log;
  private RemoteFsPathGuard pathGuard;
  private RemoteFsAuth auth;

  // ============================================================
  // Lifecycle
  // ============================================================

  @Override
  public void onCreate() {
    super.onCreate();

    // 1. Сначала — минимум, чтобы не упасть на startForeground
    try {
      createNotificationChannel();
      startForeground(NOTIF_ID, buildNotification("Сервер файлов активен"));
    } catch (Exception e) {
      Log.e(TAG, "onCreate: startForeground FAILED", e);
      // Продолжаем, логгер ниже попробует записать
    }

    // 2. Логгер
    try {
      log = MealseLogger.get(this, TAG);
      log.log("══ RemoteFsService onCreate START ══");
    } catch (Exception e) {
      Log.e(TAG, "onCreate: MealseLogger FAILED", e);
    }

    // 3. PathGuard
    try {
      pathGuard = new RemoteFsPathGuard(this);
      if (log != null)
        log.log("pathGuard OK");
    } catch (Exception e) {
      if (log != null)
        log.logError("pathGuard FAILED", e);
      broadcastState(STATE_ERROR, "Ошибка pathGuard: " + e.getMessage());
      stopSelf();
      return;
    }

    // 4. Auth
    try {
      auth = new RemoteFsAuth(this);
      if (log != null)
        log.log("auth OK");
    } catch (Exception e) {
      if (log != null)
        log.logError("auth FAILED", e);
      broadcastState(STATE_ERROR, "Ошибка auth: " + e.getMessage());
      stopSelf();
      return;
    }

    // 5. Основной цикл
    try {
      running = true;
      startAcceptLoop();
      broadcastState(STATE_RUNNING, "Сервер файлов на порту " + PORT);
      if (log != null)
        log.log("══ RemoteFsService onCreate END — :" + PORT + " ══");
    } catch (Exception e) {
      if (log != null)
        log.logError("startAcceptLoop FAILED", e);
      broadcastState(STATE_ERROR, "Ошибка сервера: " + e.getMessage());
    }
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    String action = intent != null ? intent.getAction() : null;
    log.log("onStartCommand: action=" + action);

    if (ACTION_STOP.equals(action)) {
      // ★ Правильная остановка: убираем foreground ДО stopSelf
      try {
        stopForeground(true);
        log.log("stopForeground(true) выполнен");
      } catch (Exception e) {
        log.warn("stopForeground: " + e.getMessage());
      }
      broadcastState(STATE_STOPPED, "Сервер файлов остановлен");
      // ★ START_NOT_STICKY — не перезапускать
      stopSelf();
      return START_NOT_STICKY;
    }

    if (ACTION_QUERY_STATUS.equals(action)) {
      broadcastState(running ? STATE_RUNNING : STATE_STOPPED,
          running ? "Сервер активен" : "Сервер не запущен");
      return START_NOT_STICKY;
    }

    // Для ACTION_START и null — продолжаем работу, но НЕ просим
    // систему перезапускать: sticky поведение нам не нужно.
    return START_NOT_STICKY;
  }

  @Override
  public void onDestroy() {
    log.log("onDestroy START");
    running = false;

    try {
      if (serverSocket != null && !serverSocket.isClosed()) {
        serverSocket.close();
        log.log("ServerSocket закрыт");
      }
    } catch (IOException e) {
      log.logError("Ошибка закрытия ServerSocket", e);
    }

    try {
      handlerPool.shutdownNow();
    } catch (Throwable ignored) {
    }

    try {
      stopForeground(true);
      log.log("stopForeground(true) в onDestroy");
    } catch (Exception e) {
      log.warn("stopForeground: " + e.getMessage());
    }

    broadcastState(STATE_STOPPED, "Сервер файлов остановлен");

    log.log("onDestroy END");
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  // ============================================================
  // Broadcast состояния
  // ============================================================

  private void broadcastState(String state, String message) {
    try {
      Intent i = new Intent(ACTION_FS_STATE);
      i.putExtra(EXTRA_STATE, state);
      i.putExtra(EXTRA_MESSAGE, message);
      i.setPackage(getPackageName());
      sendBroadcast(i);
      if (log != null) {
        log.log("📡 FS state broadcast: " + state + " (" + message + ")");
      }
    } catch (Throwable t) {
      if (log != null)
        log.logError("broadcastState", t);
    }
  }

  // ============================================================
  // Accept-loop
  // ============================================================

  private void startAcceptLoop() {
    handlerPool.execute(() -> {
      try {
        serverSocket = new ServerSocket(PORT);
        serverSocket.setReuseAddress(true);
        log.log("HTTP-сервер слушает :" + PORT);

        while (running) {
          try {
            Socket client = serverSocket.accept();
            String ip = client.getInetAddress().getHostAddress();
            log.log("accept: " + ip + ":" + client.getPort());
            handlerPool.execute(() -> handleClient(client));
          } catch (IOException e) {
            if (running)
              log.logError("accept loop", e);
          }
        }
      } catch (IOException e) {
        log.logError("Не удалось открыть ServerSocket на :" + PORT, e);
        broadcastState(STATE_ERROR, "Порт " + PORT + " занят");
      }
    });
  }

  // ============================================================
  // Обработчик клиента
  // ============================================================

  private void handleClient(Socket client) {
    int connId = activeConnections.incrementAndGet();
    String clientIp = "unknown";

    try {
      client.setSoTimeout(SOCKET_TIMEOUT_MS);
      client.setTcpNoDelay(true);
      clientIp = client.getInetAddress().getHostAddress();

      BufferedInputStream in = new BufferedInputStream(client.getInputStream(), BUFFER_SIZE);
      BufferedOutputStream out = new BufferedOutputStream(client.getOutputStream(), BUFFER_SIZE);

      RequestLine rl = readRequestLine(in);
      if (rl == null) {
        sendJsonError(out, 400, "bad request line");
        return;
      }
      Map<String, String> headers = readHeaders(in);
      if (headers == null) {
        sendJsonError(out, 400, "bad headers");
        return;
      }

      String fp = headers.get("x-mealse-fingerprint");
      String did = headers.get("x-mealse-deviceid");
      RemoteFsAuth.AuthResult ar = auth.authorize(fp, did, clientIp);
      if (!ar.allowed) {
        log.warn("conn#" + connId + " auth denied: " + ar.reason);
        sendJsonError(out, 403, "access denied: " + ar.reason);
        return;
      }

      String method = rl.method;
      String path = rl.path;
      Map<String, String> query = rl.query;

      log.log("conn#" + connId + " " + method + " " + path);

      switch (path) {
        case "/fs/info":
          handleInfo(out);
          break;
        case "/fs/list":
          handleList(out, query);
          break;
        case "/fs/stat":
          handleStat(out, query);
          break;
        case "/fs/mkdir":
          handleMkdir(out, in, headers);
          break;
        case "/fs/rename":
          handleRename(out, in, headers);
          break;
        case "/fs/delete":
          handleDelete(out, in, headers);
          break;
        case "/fs/move":
          handleMove(out, in, headers);
          break;
        case "/fs/copy":
          handleCopy(out, in, headers);
          break;
        case "/fs/search":
          handleSearch(out, query);
          break;
        case "/fs/download":
          handleDownload(out, query);
          break;
        case "/fs/upload":
          handleUpload(out, in, headers, query);
          break;
        default:
          sendJsonError(out, 404, "unknown endpoint: " + path);
      }

    } catch (Throwable t) {
      log.logError("conn#" + connId + " (" + clientIp + ")", t);
    } finally {
      try {
        client.close();
      } catch (Throwable ignored) {
      }
      activeConnections.decrementAndGet();
    }
  }

  // ============================================================
  // Endpoints (см. полный код ниже — сохранён без изменений из Шага 6)
  // ============================================================

  private void handleInfo(BufferedOutputStream out) throws IOException {
    JSONObject data = new JSONObject();
    try {
      data.put("rootPath", pathGuard.getVirtualRoot());
      JSONArray subs = new JSONArray();
      for (String name : pathGuard.getDefaultSubdirs())
        subs.put(name);
      data.put("subdirs", subs);
      data.put("protocol", 1);
      data.put("server", "Mealse RemoteFsService");
    } catch (JSONException ignored) {
    }
    sendJsonOk(out, data);
  }

  private void handleList(BufferedOutputStream out, Map<String, String> query) throws IOException {
    String virtualPath = query.get("path");
    if (virtualPath == null)
      virtualPath = "/";

    File dir = pathGuard.resolveAbsolute(virtualPath);
    if (dir == null) {
      sendJsonError(out, 403, "path forbidden: " + virtualPath);
      return;
    }
    if (!dir.exists()) {
      sendJsonError(out, 404, "not found: " + virtualPath);
      return;
    }
    if (!dir.isDirectory()) {
      sendJsonError(out, 400, "not a directory");
      return;
    }

    File[] children = dir.listFiles();
    if (children == null)
      children = new File[0];

    JSONArray arr = new JSONArray();
    for (File f : children) {
      JSONObject j = fileToJson(f);
      if (j != null)
        arr.put(j);
    }

    JSONObject data = new JSONObject();
    try {
      data.put("entries", arr);
      data.put("path", pathGuard.toVirtualPath(dir));
    } catch (JSONException ignored) {
    }
    sendJsonOk(out, data);
  }

  private void handleStat(BufferedOutputStream out, Map<String, String> query) throws IOException {
    String virtualPath = query.get("path");
    File f = pathGuard.resolveAbsolute(virtualPath);
    if (f == null) {
      sendJsonError(out, 403, "path forbidden");
      return;
    }
    if (!f.exists()) {
      sendJsonError(out, 404, "not found");
      return;
    }

    JSONObject j = fileToJson(f);
    if (j == null) {
      sendJsonError(out, 500, "cannot serialize");
      return;
    }
    JSONObject data = new JSONObject();
    try {
      data.put("entry", j);
    } catch (JSONException ignored) {
    }
    sendJsonOk(out, data);
  }

  private void handleMkdir(BufferedOutputStream out, InputStream in, Map<String, String> headers)
      throws IOException {
    JSONObject body = readJsonBody(in, headers);
    if (body == null) {
      sendJsonError(out, 400, "bad json");
      return;
    }

    String parentVirtual = body.optString("path", null);
    String name = body.optJSONObject("params") != null
        ? body.optJSONObject("params").optString("name", null)
        : null;

    if (parentVirtual == null || name == null) {
      sendJsonError(out, 400, "path+name required");
      return;
    }
    if (name.isEmpty() || name.contains("/") || name.contains("\\")) {
      sendJsonError(out, 400, "invalid name");
      return;
    }

    File parent = pathGuard.resolveAbsolute(parentVirtual);
    if (parent == null || !parent.exists() || !parent.isDirectory()) {
      sendJsonError(out, 400, "parent invalid");
      return;
    }

    String parentV = pathGuard.toVirtualPath(parent);
    if (parentV == null) {
      sendJsonError(out, 403, "parent forbidden");
      return;
    }
    String childV = joinVirtual(parentV, name);

    File newDir = pathGuard.resolveAbsolute(childV);
    if (newDir == null) {
      sendJsonError(out, 403, "target forbidden");
      return;
    }
    if (newDir.exists()) {
      sendJsonError(out, 409, "already exists");
      return;
    }
    if (!newDir.mkdirs()) {
      sendJsonError(out, 500, "mkdir failed");
      return;
    }
    sendJsonOk(out, new JSONObject());
  }

  private void handleRename(BufferedOutputStream out, InputStream in, Map<String, String> headers)
      throws IOException {
    JSONObject body = readJsonBody(in, headers);
    if (body == null) {
      sendJsonError(out, 400, "bad json");
      return;
    }

    String vpath = body.optString("path", null);
    String newName = body.optJSONObject("params") != null
        ? body.optJSONObject("params").optString("new_name", null)
        : null;

    if (vpath == null || newName == null || newName.contains("/") || newName.contains("\\")) {
      sendJsonError(out, 400, "invalid args");
      return;
    }

    File src = pathGuard.resolveAbsolute(vpath);
    if (src == null || !src.exists()) {
      sendJsonError(out, 404, "not found");
      return;
    }

    String parentV = pathGuard.toVirtualPath(src.getParentFile());
    if (parentV == null) {
      sendJsonError(out, 403, "parent forbidden");
      return;
    }
    File dst = pathGuard.resolveAbsolute(joinVirtual(parentV, newName));
    if (dst == null) {
      sendJsonError(out, 403, "target forbidden");
      return;
    }
    if (dst.exists()) {
      sendJsonError(out, 409, "exists");
      return;
    }
    if (!src.renameTo(dst)) {
      sendJsonError(out, 500, "rename failed");
      return;
    }
    sendJsonOk(out, new JSONObject());
  }

  private void handleDelete(BufferedOutputStream out, InputStream in, Map<String, String> headers)
      throws IOException {
    JSONObject body = readJsonBody(in, headers);
    if (body == null) {
      sendJsonError(out, 400, "bad json");
      return;
    }

    String vpath = body.optString("path", null);
    boolean recursive = body.optJSONObject("params") != null
        && body.optJSONObject("params").optBoolean("recursive", false);

    File target = pathGuard.resolveAbsolute(vpath);
    if (target == null) {
      sendJsonError(out, 403, "path forbidden");
      return;
    }
    if (!target.exists()) {
      sendJsonError(out, 404, "not found");
      return;
    }
    if (pathGuard.getVirtualRoot().equals(pathGuard.toVirtualPath(target))) {
      sendJsonError(out, 403, "cannot delete root");
      return;
    }

    boolean ok = (target.isDirectory() && recursive) ? deleteRecursive(target) : target.delete();
    if (!ok) {
      sendJsonError(out, 500, "delete failed");
      return;
    }
    sendJsonOk(out, new JSONObject());
  }

  private void handleMove(BufferedOutputStream out, InputStream in, Map<String, String> headers)
      throws IOException {
    JSONObject body = readJsonBody(in, headers);
    if (body == null) {
      sendJsonError(out, 400, "bad json");
      return;
    }

    String srcV = body.optString("path", null);
    String dstDirV = body.optJSONObject("params") != null
        ? body.optJSONObject("params").optString("dst_dir", null)
        : null;

    File src = pathGuard.resolveAbsolute(srcV);
    File dstDir = pathGuard.resolveAbsolute(dstDirV);
    if (src == null || dstDir == null) {
      sendJsonError(out, 403, "forbidden");
      return;
    }
    if (!src.exists()) {
      sendJsonError(out, 404, "src not found");
      return;
    }
    if (!dstDir.exists() || !dstDir.isDirectory()) {
      sendJsonError(out, 400, "dst invalid");
      return;
    }

    String dstV = pathGuard.toVirtualPath(dstDir);
    if (dstV == null) {
      sendJsonError(out, 403, "dst forbidden");
      return;
    }
    File target = pathGuard.resolveAbsolute(joinVirtual(dstV, src.getName()));
    if (target == null) {
      sendJsonError(out, 403, "target forbidden");
      return;
    }
    if (target.exists()) {
      sendJsonError(out, 409, "target exists");
      return;
    }

    if (!src.renameTo(target)) {
      boolean copied = src.isDirectory() ? copyRecursive(src, target) : copyFile(src, target);
      if (copied && deleteRecursive(src)) {
        sendJsonOk(out, new JSONObject());
        return;
      }
      sendJsonError(out, 500, "move failed");
      return;
    }
    sendJsonOk(out, new JSONObject());
  }

  private void handleCopy(BufferedOutputStream out, InputStream in, Map<String, String> headers)
      throws IOException {
    JSONObject body = readJsonBody(in, headers);
    if (body == null) {
      sendJsonError(out, 400, "bad json");
      return;
    }

    String srcV = body.optString("path", null);
    String dstDirV = body.optJSONObject("params") != null
        ? body.optJSONObject("params").optString("dst_dir", null)
        : null;

    File src = pathGuard.resolveAbsolute(srcV);
    File dstDir = pathGuard.resolveAbsolute(dstDirV);
    if (src == null || dstDir == null) {
      sendJsonError(out, 403, "forbidden");
      return;
    }
    if (!src.exists()) {
      sendJsonError(out, 404, "src not found");
      return;
    }
    if (!dstDir.exists() || !dstDir.isDirectory()) {
      sendJsonError(out, 400, "dst invalid");
      return;
    }

    String dstV = pathGuard.toVirtualPath(dstDir);
    if (dstV == null) {
      sendJsonError(out, 403, "dst forbidden");
      return;
    }
    File target = pathGuard.resolveAbsolute(joinVirtual(dstV, src.getName()));
    if (target == null) {
      sendJsonError(out, 403, "target forbidden");
      return;
    }
    if (target.exists()) {
      sendJsonError(out, 409, "target exists");
      return;
    }

    boolean ok = src.isDirectory() ? copyRecursive(src, target) : copyFile(src, target);
    if (!ok) {
      sendJsonError(out, 500, "copy failed");
      return;
    }
    sendJsonOk(out, new JSONObject());
  }

  private void handleSearch(BufferedOutputStream out, Map<String, String> query)
      throws IOException {
    String vpath = query.get("path");
    String q = query.get("q");
    if (q == null || q.isEmpty()) {
      sendJsonError(out, 400, "q required");
      return;
    }

    File root = pathGuard.resolveAbsolute(vpath);
    if (root == null || !root.exists() || !root.isDirectory()) {
      sendJsonError(out, 404, "root not found");
      return;
    }

    List<File> found = new ArrayList<>();
    searchRecursive(root, q.toLowerCase(), found, 500);
    JSONArray arr = new JSONArray();
    for (File f : found) {
      JSONObject j = fileToJson(f);
      if (j != null)
        arr.put(j);
    }
    JSONObject data = new JSONObject();
    try {
      data.put("entries", arr);
      data.put("path", pathGuard.toVirtualPath(root));
    } catch (JSONException ignored) {
    }
    sendJsonOk(out, data);
  }

  private void handleDownload(BufferedOutputStream out, Map<String, String> query)
      throws IOException {
    String vpath = query.get("path");
    File f = pathGuard.resolveAbsolute(vpath);
    if (f == null) {
      sendJsonError(out, 403, "forbidden");
      return;
    }
    if (!f.exists() || !f.isFile()) {
      sendJsonError(out, 404, "not found");
      return;
    }

    long size = f.length();
    String mime = guessMime(f.getName());
    StringBuilder head = new StringBuilder(256);
    head.append("HTTP/1.1 200 OK\r\n").append("Content-Type: ").append(mime).append("\r\n")
        .append("Content-Length: ").append(size).append("\r\n")
        .append("Content-Disposition: attachment; filename=\"")
        .append(f.getName().replace("\"", "\\\"")).append("\"\r\n")
        .append("Connection: close\r\n\r\n");
    out.write(head.toString().getBytes(StandardCharsets.UTF_8));
    out.flush();

    try (InputStream in = new BufferedInputStream(new FileInputStream(f), BUFFER_SIZE)) {
      byte[] buf = new byte[BUFFER_SIZE];
      int r;
      while ((r = in.read(buf)) != -1)
        out.write(buf, 0, r);
      out.flush();
    }
  }

  private void handleUpload(BufferedOutputStream out, InputStream in, Map<String, String> headers,
      Map<String, String> query) throws IOException {
    String fullV = query.get("path");
    String name = query.get("name");
    String policyStr = query.get("policy");

    if (fullV == null || name == null) {
      sendJsonError(out, 400, "path+name required");
      return;
    }
    if (name.isEmpty() || name.contains("/") || name.contains("\\")) {
      sendJsonError(out, 400, "invalid name");
      return;
    }

    File resolvedFull = pathGuard.resolveAbsolute(fullV);
    if (resolvedFull == null) {
      sendJsonError(out, 403, "target forbidden");
      return;
    }
    File dstDir = resolvedFull.getParentFile();
    if (dstDir == null) {
      sendJsonError(out, 400, "no parent");
      return;
    }
    String dstDirV = pathGuard.toVirtualPath(dstDir);
    if (dstDirV == null) {
      sendJsonError(out, 403, "dst forbidden");
      return;
    }
    File realDstDir = pathGuard.resolveAbsolute(dstDirV);
    if (realDstDir == null || !realDstDir.exists() || !realDstDir.isDirectory()) {
      sendJsonError(out, 400, "dst invalid");
      return;
    }

    File target = pathGuard.resolveAbsolute(joinVirtual(dstDirV, name));
    if (target == null) {
      sendJsonError(out, 403, "target forbidden");
      return;
    }

    if (target.exists()) {
      if ("overwrite".equals(policyStr)) {
        if (!target.delete()) {
          sendJsonError(out, 500, "cannot overwrite");
          return;
        }
      } else if ("rename".equals(policyStr)) {
        target = autoRename(target);
      } else {
        sendJsonError(out, 409, "already exists");
        return;
      }
    }

    String clStr = headers.get("content-length");
    if (clStr == null) {
      sendJsonError(out, 400, "Content-Length required");
      return;
    }
    long length;
    try {
      length = Long.parseLong(clStr);
    } catch (NumberFormatException e) {
      sendJsonError(out, 400, "bad length");
      return;
    }
    if (length < 0) {
      sendJsonError(out, 400, "length out of range");
      return;
    }

    try (OutputStream fos = new BufferedOutputStream(new FileOutputStream(target), BUFFER_SIZE)) {
      byte[] buf = new byte[BUFFER_SIZE];
      long remaining = length;
      while (remaining > 0) {
        int toRead = (int) Math.min(buf.length, remaining);
        int r = in.read(buf, 0, toRead);
        if (r < 0)
          break;
        fos.write(buf, 0, r);
        remaining -= r;
      }
      fos.flush();
    }
    sendJsonOk(out, new JSONObject());
  }

  // ============================================================
  // Парсинг HTTP
  // ============================================================

  private static class RequestLine {
    final String method;
    final String path;
    final Map<String, String> query;

    RequestLine(String m, String p, Map<String, String> q) {
      method = m;
      path = p;
      query = q;
    }
  }

  private RequestLine readRequestLine(InputStream in) throws IOException {
    String line = readLine(in, MAX_HEADER_SIZE);
    if (line == null || line.isEmpty())
      return null;
    String[] parts = line.split(" ");
    if (parts.length < 3)
      return null;

    String method = parts[0];
    String rawTarget = parts[1];
    Map<String, String> query = new HashMap<>();
    String path = rawTarget;
    int qIdx = rawTarget.indexOf('?');
    if (qIdx >= 0) {
      path = rawTarget.substring(0, qIdx);
      String qs = rawTarget.substring(qIdx + 1);
      for (String pair : qs.split("&")) {
        if (pair.isEmpty())
          continue;
        int eq = pair.indexOf('=');
        if (eq < 0)
          query.put(urlDecode(pair), "");
        else
          query.put(urlDecode(pair.substring(0, eq)), urlDecode(pair.substring(eq + 1)));
      }
    }
    return new RequestLine(method, path, query);
  }

  private Map<String, String> readHeaders(InputStream in) throws IOException {
    Map<String, String> headers = new HashMap<>();
    int total = 0;
    while (true) {
      String line = readLine(in, MAX_HEADER_SIZE);
      if (line == null)
        return null;
      if (line.isEmpty())
        return headers;
      total += line.length();
      if (total > MAX_HEADER_SIZE)
        return null;
      int colon = line.indexOf(':');
      if (colon < 0)
        continue;
      headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
    }
  }

  private static String readLine(InputStream in, int max) throws IOException {
    StringBuilder sb = new StringBuilder(64);
    int b;
    while ((b = in.read()) != -1) {
      if (b == '\n')
        break;
      if (b == '\r')
        continue;
      sb.append((char) b);
      if (sb.length() > max)
        throw new IOException("line too long");
    }
    if (sb.length() == 0 && b == -1)
      return null;
    return sb.toString();
  }

  private JSONObject readJsonBody(InputStream in, Map<String, String> headers) throws IOException {
    String clStr = headers.get("content-length");
    if (clStr == null)
      return null;
    int length;
    try {
      length = Integer.parseInt(clStr);
    } catch (NumberFormatException e) {
      return null;
    }
    if (length < 0 || length > MAX_BODY_SIZE)
      return null;

    byte[] buf = new byte[length];
    int read = 0;
    while (read < length) {
      int r = in.read(buf, read, length - read);
      if (r < 0)
        break;
      read += r;
    }
    try {
      return new JSONObject(new String(buf, 0, read, StandardCharsets.UTF_8));
    } catch (JSONException e) {
      return null;
    }
  }

  // ============================================================
  // Отправка ответов
  // ============================================================

  private void sendJsonOk(BufferedOutputStream out, JSONObject data) throws IOException {
    JSONObject resp = new JSONObject();
    try {
      resp.put("v", 1);
      resp.put("status", "ok");
      resp.put("data", data != null ? data : new JSONObject());
    } catch (JSONException ignored) {
    }
    sendJson(out, 200, resp.toString());
  }

  private void sendJsonError(BufferedOutputStream out, int httpCode, String message)
      throws IOException {
    JSONObject resp = new JSONObject();
    try {
      resp.put("v", 1);
      resp.put("status", "error");
      resp.put("code", mapHttpToErrorCode(httpCode));
      resp.put("message", message);
    } catch (JSONException ignored) {
    }
    sendJson(out, httpCode, resp.toString());
  }

  private void sendJson(BufferedOutputStream out, int httpCode, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    String head = "HTTP/1.1 " + httpCode + " " + httpText(httpCode) + "\r\n"
        + "Content-Type: application/json; charset=utf-8\r\n" + "Content-Length: " + bytes.length
        + "\r\n" + "Connection: close\r\n\r\n";
    out.write(head.getBytes(StandardCharsets.UTF_8));
    out.write(bytes);
    out.flush();
  }

  // ============================================================
  // Утилиты
  // ============================================================

  private JSONObject fileToJson(File f) {
    if (f == null)
      return null;
    try {
      String vpath = pathGuard.toVirtualPath(f);
      if (vpath == null)
        return null;

      File parent = f.getParentFile();
      String vparent = parent != null ? pathGuard.toVirtualPath(parent) : null;

      JSONObject o = new JSONObject();
      o.put("path", vpath);
      o.put("name", f.getName());
      if (vparent != null)
        o.put("parent", vparent);
      o.put("dir", f.isDirectory());
      o.put("size", f.isDirectory() ? 0L : f.length());
      o.put("mtime", f.lastModified());
      o.put("mime", f.isDirectory() ? "inode/directory" : guessMime(f.getName()));
      o.put("hidden", f.isHidden());
      o.put("readable", f.canRead());
      o.put("writable", f.canWrite());
      return o;
    } catch (JSONException e) {
      return null;
    }
  }

  private static boolean deleteRecursive(File f) {
    if (f == null)
      return false;
    if (f.isDirectory()) {
      File[] kids = f.listFiles();
      if (kids != null)
        for (File k : kids)
          deleteRecursive(k);
    }
    return f.delete();
  }

  private static boolean copyFile(File src, File dst) {
    try (InputStream in = new BufferedInputStream(new FileInputStream(src));
        OutputStream out = new BufferedOutputStream(new FileOutputStream(dst))) {
      byte[] buf = new byte[BUFFER_SIZE];
      int r;
      while ((r = in.read(buf)) != -1)
        out.write(buf, 0, r);
      out.flush();
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  private static boolean copyRecursive(File src, File dst) {
    if (src.isDirectory()) {
      if (!dst.mkdirs() && !dst.exists())
        return false;
      File[] kids = src.listFiles();
      if (kids != null) {
        for (File k : kids)
          if (!copyRecursive(k, new File(dst, k.getName())))
            return false;
      }
      return true;
    }
    return copyFile(src, dst);
  }

  private static void searchRecursive(File root, String q, List<File> out, int limit) {
    if (out.size() >= limit)
      return;
    File[] kids = root.listFiles();
    if (kids == null)
      return;
    for (File f : kids) {
      if (out.size() >= limit)
        return;
      if (f.getName().toLowerCase().contains(q))
        out.add(f);
      if (f.isDirectory())
        searchRecursive(f, q, out, limit);
    }
  }

  private static File autoRename(File f) {
    String name = f.getName();
    int dot = name.lastIndexOf('.');
    String base = dot > 0 ? name.substring(0, dot) : name;
    String ext = dot > 0 ? name.substring(dot) : "";
    File dir = f.getParentFile();
    for (int i = 1; i < 1000; i++) {
      File c = new File(dir, base + " (" + i + ")" + ext);
      if (!c.exists())
        return c;
    }
    return f;
  }

  private static String joinVirtual(String dir, String name) {
    if (dir == null || dir.isEmpty())
      dir = "/";
    if (dir.endsWith("/"))
      return dir + name;
    return dir + "/" + name;
  }

  private static String urlDecode(String s) {
    try {
      return URLDecoder.decode(s, "UTF-8");
    } catch (Throwable t) {
      return s;
    }
  }

  private static int mapHttpToErrorCode(int c) {
    if (c == 400)
      return 6;
    if (c == 403)
      return 3;
    if (c == 404)
      return 2;
    if (c == 409)
      return 5;
    if (c >= 500)
      return 7;
    return 0;
  }

  private static String httpText(int code) {
    if (code == 200)
      return "OK";
    if (code == 400)
      return "Bad Request";
    if (code == 403)
      return "Forbidden";
    if (code == 404)
      return "Not Found";
    if (code == 409)
      return "Conflict";
    if (code == 500)
      return "Internal Server Error";
    return "Unknown";
  }

  private static String guessMime(String name) {
    if (name == null)
      return "application/octet-stream";
    String lower = name.toLowerCase();
    int dot = lower.lastIndexOf('.');
    if (dot < 0)
      return "application/octet-stream";
    switch (lower.substring(dot + 1)) {
      case "mp4":
        return "video/mp4";
      case "mkv":
        return "video/x-matroska";
      case "avi":
        return "video/x-msvideo";
      case "mov":
        return "video/quicktime";
      case "webm":
        return "video/webm";
      case "mp3":
        return "audio/mpeg";
      case "wav":
        return "audio/wav";
      case "flac":
        return "audio/flac";
      case "jpg":
      case "jpeg":
        return "image/jpeg";
      case "png":
        return "image/png";
      case "gif":
        return "image/gif";
      case "webp":
        return "image/webp";
      case "pdf":
        return "application/pdf";
      case "txt":
        return "text/plain";
      case "json":
        return "application/json";
      case "xml":
        return "application/xml";
      case "html":
      case "htm":
        return "text/html";
      case "zip":
        return "application/zip";
      case "rar":
        return "application/x-rar-compressed";
      case "apk":
        return "application/vnd.android.package-archive";
      default:
        return "application/octet-stream";
    }
  }

  private void createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Файлы Mealse",
          NotificationManager.IMPORTANCE_LOW);
      ch.setDescription("Приём запросов на доступ к файлам");
      NotificationManager nm = getSystemService(NotificationManager.class);
      if (nm != null)
        nm.createNotificationChannel(ch);
    }
  }

  private Notification buildNotification(String text) {
    Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        ? new Notification.Builder(this, CHANNEL_ID)
        : new Notification.Builder(this);
    return b.setContentTitle("Mealse Файлы").setContentText(text).setSmallIcon(R.drawable.ic_file)
        .setOngoing(true).build();
  }
}"Unknown";
    }

    private static String guessMime(String name) {
        if (name == null) return "application/octet-stream";
        String lower = name.toLowerCase();
        int dot = lower.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        switch (lower.substring(dot + 1)) {
            case "mp4": return "video/mp4";
            case "mkv": return "video/x-matroska";
            case "avi": return "video/x-msvideo";
            case "mov": return "video/quicktime";
            case "webm": return "video/webm";
            case "mp3": return "audio/mpeg";
            case "wav": return "audio/wav";
            case "flac": return "audio/flac";
            case "jpg":
            case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "pdf": return "application/pdf";
            case "txt": return "text/plain";
            case "json": return "application/json";
            case "xml": return "application/xml";
            case "html":
            case "htm": return "text/html";
            case "zip": return "application/zip";
            case "rar": return "application/x-rar-compressed";
            case "apk": return "application/vnd.android.package-archive";
            default: return "application/octet-stream";
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "Файлы Mealse",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Приём запросов на доступ к файлам");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            //noinspection deprecation
            b = new Notification.Builder(this);
        }
        return b.setContentTitle("Mealse Файлы")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_file)
                .setOngoing(true)
                .build();
    }
}