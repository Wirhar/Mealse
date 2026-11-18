package com.my.mealse.filemanager;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.my.mealse.model.TrustedNode;
import com.my.mealse.util.DeviceInfoHelper;
import com.my.mealse.util.MealseLogger;
import com.my.mealse.util.SecureAuthManager;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HTTP-транспорт для удалённых файловых операций.
 *
 * ★ РЕВИЗИЯ (Шаг 4, fix): RemoteFsProtocol.* теперь бросает FileTransportException, поэтому все вызовы безопасны для checked JSONException.
 */
public class HttpFileTransport implements FileTransport {

  private static final String TAG = "HttpFileTransport";

  public static final int DEFAULT_PORT = 8084;

  private static final int CONNECT_TIMEOUT_MS = 10_000;
  private static final int READ_TIMEOUT_MS = 45_000;
  private static final int BUFFER_SIZE = 64 * 1024;

  private final Context appContext;
  private final MealseLogger log;
  private final TrustedNode node;
  private final boolean viaVpn;

  private final ExecutorService executor = Executors.newCachedThreadPool();
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final AtomicLong reqIdSeq = new AtomicLong(1);
  private final AtomicBoolean closed = new AtomicBoolean(false);

  private volatile HttpURLConnection activeConnection;
  private final AtomicBoolean cancelled = new AtomicBoolean(false);

  private final String sourceId;
  private final String peerHost;
  private final int peerPort;

  private final String selfFingerprint;
  private final String selfDeviceId;

  public HttpFileTransport(Context context, TrustedNode node, boolean viaVpn)
      throws FileTransportException {

    if (node == null) {
      throw new FileTransportException(FileTransportException.CODE_INVALID_REQUEST, "node == null");
    }
    String host = node.getIpAddress();
    if (host == null || host.isEmpty()) {
      throw new FileTransportException(FileTransportException.CODE_INVALID_REQUEST, "node.ip пуст");
    }

    this.appContext = context.getApplicationContext();
    this.log = MealseLogger.get(appContext, TAG);
    this.node = node;
    this.viaVpn = viaVpn;
    this.sourceId = node.getKey();
    this.peerHost = host;
    this.peerPort = DEFAULT_PORT;

    String fp = null, did = null;
    try {
      fp = new SecureAuthManager(appContext).getDeviceFingerprint();
    } catch (Throwable ignored) {
    }
    try {
      did = DeviceInfoHelper.getDeviceId(appContext);
    } catch (Throwable ignored) {
    }
    this.selfFingerprint = fp != null ? fp : "";
    this.selfDeviceId = did != null ? did : "";

    log.log("HttpFileTransport создан: peer=" + peerHost + ":" + peerPort + ", viaVpn=" + viaVpn);
  }

  // ============================================================
  // FileTransport API
  // ============================================================

  @Override
  public void list(String path, ListCallback cb) {
    runAsync(cb, () -> {
      JSONObject resp = execGet("/fs/list", "path=" + enc(path));
      ensureOk(resp);
      List<FileEntry> entries = RemoteFsProtocol.parseEntries(sourceId, resp);
      postList(cb, path, entries);
      return null;
    });
  }

  @Override
  public void stat(String path, StatCallback cb) {
    runAsync(cb, () -> {
      JSONObject resp = execGet("/fs/stat", "path=" + enc(path));
      ensureOk(resp);
      FileEntry entry = RemoteFsProtocol.parseEntry(sourceId, resp);
      if (entry == null) {
        throw new FileTransportException(FileTransportException.CODE_PROTOCOL,
            "stat: пустой entry");
      }
      postStat(cb, entry);
      return null;
    });
  }

  @Override
  public void info(InfoCallback cb) {
    runAsync(cb, () -> {
      JSONObject resp = execGet("/fs/info", null);
      ensureOk(resp);
      RemoteFsProtocol.RemoteRootInfo info = RemoteFsProtocol.parseInfo(resp);
      postInfo(cb, info);
      return null;
    });
  }

  @Override
  public void download(String remotePath, File dest, TransferCallback cb) {
    cancelled.set(false);

    executor.execute(() -> {
      long reqId = reqIdSeq.getAndIncrement();
      long total = -1;
      long transferred = 0;
      HttpURLConnection conn = null;
      InputStream in = null;
      OutputStream out = null;

      try {
        String url = buildUrl("/fs/download", "path=" + enc(remotePath));
        log.log("[dl#" + reqId + "] " + url);

        conn = openConnection(url, "GET");
        conn.setRequestProperty("X-Mealse-Fingerprint", selfFingerprint);
        conn.setRequestProperty("X-Mealse-DeviceId", selfDeviceId);
        conn.setRequestProperty("X-Mealse-Request-Id", String.valueOf(reqId));
        activeConnection = conn;

        int code = conn.getResponseCode();
        if (code != 200) {
          String err = readErrorBody(conn);
          throw new FileTransportException(FileTransportException.fromHttpStatus(code),
              "download HTTP " + code + ": " + err);
        }

        total = conn.getContentLengthLong();
        postStart(cb, total);

        File parentDir = dest.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
          // noinspection ResultOfMethodCallIgnored
          parentDir.mkdirs();
        }

        in = new BufferedInputStream(conn.getInputStream(), BUFFER_SIZE);
        out = new BufferedOutputStream(new FileOutputStream(dest), BUFFER_SIZE);

        byte[] buf = new byte[BUFFER_SIZE];
        int r;
        long lastReport = 0;
        while ((r = in.read(buf)) != -1) {
          if (cancelled.get()) {
            closeQuietly(in);
            closeQuietly(out);
            // noinspection ResultOfMethodCallIgnored
            dest.delete();
            postCancelled(cb);
            return;
          }
          out.write(buf, 0, r);
          transferred += r;

          long now = System.currentTimeMillis();
          if (now - lastReport > 100) {
            postProgress(cb, transferred, total);
            lastReport = now;
          }
        }
        out.flush();

        postProgress(cb, transferred, total);
        postSuccess(cb, dest);

      } catch (FileTransportException fte) {
        postError(cb, fte);
      } catch (IOException ioe) {
        postError(cb, new FileTransportException(FileTransportException.CODE_NETWORK,
            "download I/O: " + ioe.getMessage(), ioe));
      } catch (Throwable t) {
        postError(cb, new FileTransportException(FileTransportException.CODE_UNKNOWN,
            "download: " + t.getMessage(), t));
      } finally {
        activeConnection = null;
        closeQuietly(in);
        closeQuietly(out);
        if (conn != null)
          try {
            conn.disconnect();
          } catch (Throwable ignored) {
          }
      }
    });
  }

  @Override
  public void upload(File src, String remoteDir, String remoteName, ConflictPolicy policy,
      TransferCallback cb) {
    cancelled.set(false);

    if (src == null || !src.exists() || !src.isFile()) {
      postError(cb, new FileTransportException(FileTransportException.CODE_INVALID_REQUEST,
          "src не файл: " + src));
      return;
    }

    executor.execute(() -> {
      long reqId = reqIdSeq.getAndIncrement();
      long total = src.length();
      long transferred = 0;
      HttpURLConnection conn = null;
      InputStream in = null;
      OutputStream out = null;

      try {
        String fullPath = joinPath(remoteDir, remoteName);
        String query = "path=" + enc(fullPath) + "&name=" + enc(remoteName) + "&policy="
            + enc(RemoteFsProtocol.policyToString(policy));

        String url = buildUrl("/fs/upload", query);
        log.log("[ul#" + reqId + "] " + url + " (size=" + total + ")");

        conn = openConnection(url, "POST");
        conn.setDoOutput(true);
        conn.setFixedLengthStreamingMode(total);
        conn.setRequestProperty("Content-Type", "application/octet-stream");
        conn.setRequestProperty("X-Mealse-Fingerprint", selfFingerprint);
        conn.setRequestProperty("X-Mealse-DeviceId", selfDeviceId);
        conn.setRequestProperty("X-Mealse-Request-Id", String.valueOf(reqId));
        activeConnection = conn;

        postStart(cb, total);

        out = new BufferedOutputStream(conn.getOutputStream(), BUFFER_SIZE);
        in = new BufferedInputStream(new java.io.FileInputStream(src), BUFFER_SIZE);

        byte[] buf = new byte[BUFFER_SIZE];
        int r;
        long lastReport = 0;
        while ((r = in.read(buf)) != -1) {
          if (cancelled.get()) {
            closeQuietly(in);
            closeQuietly(out);
            postCancelled(cb);
            return;
          }
          out.write(buf, 0, r);
          transferred += r;

          long now = System.currentTimeMillis();
          if (now - lastReport > 100) {
            postProgress(cb, transferred, total);
            lastReport = now;
          }
        }
        out.flush();
        closeQuietly(out);

        int code = conn.getResponseCode();
        if (code != 200 && code != 201) {
          String err = readErrorBody(conn);
          throw new FileTransportException(FileTransportException.fromHttpStatus(code),
              "upload HTTP " + code + ": " + err);
        }

        postProgress(cb, transferred, total);
        postSuccess(cb, null);

      } catch (FileTransportException fte) {
        postError(cb, fte);
      } catch (IOException ioe) {
        postError(cb, new FileTransportException(FileTransportException.CODE_NETWORK,
            "upload I/O: " + ioe.getMessage(), ioe));
      } catch (Throwable t) {
        postError(cb, new FileTransportException(FileTransportException.CODE_UNKNOWN,
            "upload: " + t.getMessage(), t));
      } finally {
        activeConnection = null;
        closeQuietly(in);
        closeQuietly(out);
        if (conn != null)
          try {
            conn.disconnect();
          } catch (Throwable ignored) {
          }
      }
    });
  }

  @Override
  public void mkdir(String parentPath, String folderName, OpCallback cb) {
    runAsync(cb, () -> {
      JSONObject params = RemoteFsProtocol.paramsMkdir(folderName);
      JSONObject body = RemoteFsProtocol.buildRequest(reqIdSeq.getAndIncrement(),
          RemoteFsProtocol.OP_MKDIR, joinPath(parentPath, folderName), params);
      JSONObject resp = execPost("/fs/mkdir", body);
      ensureOk(resp);
      return null;
    });
  }

  @Override
  public void rename(String path, String newName, OpCallback cb) {
    runAsync(cb, () -> {
      JSONObject params = RemoteFsProtocol.paramsRename(newName);
      JSONObject body = RemoteFsProtocol.buildRequest(reqIdSeq.getAndIncrement(),
          RemoteFsProtocol.OP_RENAME, path, params);
      JSONObject resp = execPost("/fs/rename", body);
      ensureOk(resp);
      return null;
    });
  }

  @Override
  public void delete(String path, boolean recursive, OpCallback cb) {
    runAsync(cb, () -> {
      JSONObject params = RemoteFsProtocol.paramsDelete(recursive);
      JSONObject body = RemoteFsProtocol.buildRequest(reqIdSeq.getAndIncrement(),
          RemoteFsProtocol.OP_DELETE, path, params);
      JSONObject resp = execPost("/fs/delete", body);
      ensureOk(resp);
      return null;
    });
  }

  @Override
  public void move(String srcPath, String dstDirPath, OpCallback cb) {
    runAsync(cb, () -> {
      JSONObject params = RemoteFsProtocol.paramsMoveOrCopy(dstDirPath);
      JSONObject body = RemoteFsProtocol.buildRequest(reqIdSeq.getAndIncrement(),
          RemoteFsProtocol.OP_MOVE, srcPath, params);
      JSONObject resp = execPost("/fs/move", body);
      ensureOk(resp);
      return null;
    });
  }

  @Override
  public void copy(String srcPath, String dstDirPath, OpCallback cb) {
    runAsync(cb, () -> {
      JSONObject params = RemoteFsProtocol.paramsMoveOrCopy(dstDirPath);
      JSONObject body = RemoteFsProtocol.buildRequest(reqIdSeq.getAndIncrement(),
          RemoteFsProtocol.OP_COPY, srcPath, params);
      JSONObject resp = execPost("/fs/copy", body);
      ensureOk(resp);
      return null;
    });
  }

  @Override
  public void search(String startPath, String query, ListCallback cb) {
    runAsync(cb, () -> {
      JSONObject resp = execGet("/fs/search", "path=" + enc(startPath) + "&q=" + enc(query));
      ensureOk(resp);
      List<FileEntry> entries = RemoteFsProtocol.parseEntries(sourceId, resp);
      postList(cb, startPath, entries);
      return null;
    });
  }

  @Override
  public void cancel() {
    cancelled.set(true);
    HttpURLConnection c = activeConnection;
    if (c != null) {
      try {
        c.disconnect();
      } catch (Throwable ignored) {
      }
    }
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true))
      return;
    cancel();
    try {
      executor.shutdownNow();
    } catch (Throwable ignored) {
    }
    log.log("HttpFileTransport закрыт для " + sourceId);
  }

  @Override
  public String getSourceId() {
    return sourceId;
  }

  @Override
  public String describe() {
    return (viaVpn ? "VPN-" : "") + "HTTP " + peerHost + ":" + peerPort;
  }

  // ============================================================
  // HTTP-примитивы
  // ============================================================

  private JSONObject execGet(String endpoint, String query) throws FileTransportException {
    HttpURLConnection conn = null;
    try {
      String url = buildUrl(endpoint, query);
      log.log("GET " + url);
      conn = openConnection(url, "GET");
      conn.setRequestProperty("Accept", "application/json");
      conn.setRequestProperty("X-Mealse-Fingerprint", selfFingerprint);
      conn.setRequestProperty("X-Mealse-DeviceId", selfDeviceId);

      int code = conn.getResponseCode();
      String body = readResponse(conn, code);

      if (code != 200) {
        JSONObject maybeErr = tryParseJson(body);
        if (maybeErr != null && RemoteFsProtocol.isError(maybeErr)) {
          throw new FileTransportException(RemoteFsProtocol.errorCode(maybeErr),
              RemoteFsProtocol.errorMessage(maybeErr));
        }
        throw new FileTransportException(FileTransportException.fromHttpStatus(code),
            "HTTP " + code + ": " + body);
      }

      JSONObject resp = tryParseJson(body);
      if (resp == null) {
        throw new FileTransportException(FileTransportException.CODE_PROTOCOL,
            "Не JSON в ответе: " + shortStr(body));
      }
      return resp;

    } catch (FileTransportException fte) {
      throw fte;
    } catch (IOException ioe) {
      throw new FileTransportException(FileTransportException.CODE_NETWORK,
          "GET I/O: " + ioe.getMessage(), ioe);
    } finally {
      if (conn != null)
        try {
          conn.disconnect();
        } catch (Throwable ignored) {
        }
    }
  }

  private JSONObject execPost(String endpoint, JSONObject body) throws FileTransportException {
    HttpURLConnection conn = null;
    try {
      String url = buildUrl(endpoint, null);
      log.log("POST " + url + " body=" + body);
      conn = openConnection(url, "POST");
      conn.setDoOutput(true);
      conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
      conn.setRequestProperty("Accept", "application/json");
      conn.setRequestProperty("X-Mealse-Fingerprint", selfFingerprint);
      conn.setRequestProperty("X-Mealse-DeviceId", selfDeviceId);

      byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
      conn.setFixedLengthStreamingMode(bytes.length);
      try (OutputStream os = conn.getOutputStream()) {
        os.write(bytes);
        os.flush();
      }

      int code = conn.getResponseCode();
      String resp = readResponse(conn, code);

      if (code != 200 && code != 201) {
        JSONObject maybeErr = tryParseJson(resp);
        if (maybeErr != null && RemoteFsProtocol.isError(maybeErr)) {
          throw new FileTransportException(RemoteFsProtocol.errorCode(maybeErr),
              RemoteFsProtocol.errorMessage(maybeErr));
        }
        throw new FileTransportException(FileTransportException.fromHttpStatus(code),
            "HTTP " + code + ": " + resp);
      }

      JSONObject json = tryParseJson(resp);
      if (json == null) {
        throw new FileTransportException(FileTransportException.CODE_PROTOCOL,
            "Не JSON в ответе: " + shortStr(resp));
      }
      return json;

    } catch (FileTransportException fte) {
      throw fte;
    } catch (IOException ioe) {
      throw new FileTransportException(FileTransportException.CODE_NETWORK,
          "POST I/O: " + ioe.getMessage(), ioe);
    } finally {
      if (conn != null)
        try {
          conn.disconnect();
        } catch (Throwable ignored) {
        }
    }
  }

  private HttpURLConnection openConnection(String urlStr, String method) throws IOException {
    URL url = new URL(urlStr);
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setRequestMethod(method);
    conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
    conn.setReadTimeout(READ_TIMEOUT_MS);
    conn.setUseCaches(false);
    conn.setInstanceFollowRedirects(false);
    return conn;
  }

  private String buildUrl(String endpoint, String query) {
    StringBuilder sb = new StringBuilder(128);
    sb.append("http://");
    if (peerHost.contains(":") && !peerHost.startsWith("[")) {
      sb.append('[').append(peerHost).append(']');
    } else {
      sb.append(peerHost);
    }
    sb.append(':').append(peerPort);
    sb.append(endpoint);
    if (query != null && !query.isEmpty()) {
      sb.append('?').append(query);
    }
    return sb.toString();
  }

  private String readResponse(HttpURLConnection conn, int code) throws IOException {
    InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
    if (is == null)
      return "";
    try (InputStream in = new BufferedInputStream(is)) {
      byte[] buf = new byte[8192];
      StringBuilder sb = new StringBuilder();
      int r;
      while ((r = in.read(buf)) != -1) {
        sb.append(new String(buf, 0, r, StandardCharsets.UTF_8));
        if (sb.length() > 256 * 1024)
          break;
      }
      return sb.toString();
    }
  }

  private String readErrorBody(HttpURLConnection conn) {
    try {
      InputStream is = conn.getErrorStream();
      if (is == null)
        return "";
      try (InputStream in = is) {
        byte[] buf = new byte[2048];
        int r = in.read(buf);
        return r > 0 ? new String(buf, 0, r, StandardCharsets.UTF_8) : "";
      }
    } catch (Throwable t) {
      return "";
    }
  }

  private void ensureOk(JSONObject resp) throws FileTransportException {
    if (resp == null) {
      throw new FileTransportException(FileTransportException.CODE_PROTOCOL, "null response");
    }
    if (RemoteFsProtocol.isError(resp)) {
      throw new FileTransportException(RemoteFsProtocol.errorCode(resp),
          RemoteFsProtocol.errorMessage(resp));
    }
    if (!RemoteFsProtocol.isOk(resp)) {
      throw new FileTransportException(FileTransportException.CODE_PROTOCOL,
          "unexpected status: " + resp.optString("status"));
    }
  }

  // ============================================================
  // Публикация колбэков
  // ============================================================

  private interface Job<T> {
    T run() throws FileTransportException;
  }

  private <T> void runAsync(Object cb, Job<T> job) {
    executor.execute(() -> {
      try {
        job.run();
      } catch (FileTransportException fte) {
        postErrorToAny(cb, fte);
      } catch (Throwable t) {
        postErrorToAny(cb,
            new FileTransportException(FileTransportException.CODE_UNKNOWN, t.getMessage(), t));
      }
    });
  }

  @SuppressWarnings("unchecked")
  private void postErrorToAny(Object cb, FileTransportException e) {
    if (cb instanceof OpCallback) {
      final OpCallback c = (OpCallback) cb;
      mainHandler.post(() -> c.onError(e));
    } else if (cb instanceof ListCallback) {
      final ListCallback c = (ListCallback) cb;
      mainHandler.post(() -> c.onError(e));
    } else if (cb instanceof StatCallback) {
      final StatCallback c = (StatCallback) cb;
      mainHandler.post(() -> c.onError(e));
    } else if (cb instanceof InfoCallback) {
      final InfoCallback c = (InfoCallback) cb;
      mainHandler.post(() -> c.onError(e));
    } else if (cb instanceof TransferCallback) {
      final TransferCallback c = (TransferCallback) cb;
      mainHandler.post(() -> c.onError(e));
    }
  }

  private void postList(ListCallback cb, String path, List<FileEntry> entries) {
    mainHandler.post(() -> cb.onResult(path, entries));
  }

  private void postStat(StatCallback cb, FileEntry e) {
    mainHandler.post(() -> cb.onResult(e));
  }

  private void postStart(TransferCallback cb, long total) {
    mainHandler.post(() -> cb.onStart(total));
  }

  private void postProgress(TransferCallback cb, long done, long total) {
    mainHandler.post(() -> cb.onProgress(done, total));
  }

  private void postSuccess(TransferCallback cb, File dest) {
    mainHandler.post(() -> cb.onSuccess(dest));
  }

  private void postError(TransferCallback cb, FileTransportException e) {
    mainHandler.post(() -> cb.onError(e));
  }

  private void postCancelled(TransferCallback cb) {
    mainHandler.post(cb::onCancelled);
  }

  private void postInfo(InfoCallback cb, RemoteFsProtocol.RemoteRootInfo info) {
    mainHandler.post(() -> cb.onResult(info));
  }

  // ============================================================
  // Утилиты
  // ============================================================

  private static String enc(String s) {
    if (s == null)
      return "";
    try {
      return URLEncoder.encode(s, "UTF-8");
    } catch (Throwable t) {
      return s;
    }
  }

  private static String joinPath(String dir, String name) {
    if (dir == null)
      return name;
    if (name == null)
      return dir;
    if (dir.endsWith("/"))
      return dir + name;
    return dir + "/" + name;
  }

  private static JSONObject tryParseJson(String s) {
    if (s == null || s.isEmpty())
      return null;
    try {
      return new JSONObject(s);
    } catch (Throwable t) {
      return null;
    }
  }

  private static String shortStr(String s) {
    if (s == null)
      return "null";
    return s.length() > 120 ? s.substring(0, 120) + "..." : s;
  }

  private static void closeQuietly(java.io.Closeable c) {
    if (c == null)
      return;
    try {
      c.close();
    } catch (Throwable ignored) {
    }
  }
}} catch (Throwable t) {
                postErrorToAny(cb, new FileTransportException(
                        FileTransportException.CODE_UNKNOWN,
                        t.getMessage(), t));
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void postErrorToAny(Object cb, FileTransportException e) {
        if (cb instanceof OpCallback) {
            final OpCallback c = (OpCallback) cb;
            mainHandler.post(() -> c.onError(e));
        } else if (cb instanceof ListCallback) {
            final ListCallback c = (ListCallback) cb;
            mainHandler.post(() -> c.onError(e));
        } else if (cb instanceof StatCallback) {
            final StatCallback c = (StatCallback) cb;
            mainHandler.post(() -> c.onError(e));
        } else if (cb instanceof TransferCallback) {
            final TransferCallback c = (TransferCallback) cb;
            mainHandler.post(() -> c.onError(e));
        }
    }

    private void postList(ListCallback cb, String path, List<FileEntry> entries) {
        mainHandler.post(() -> cb.onResult(path, entries));
    }

    private void postStat(StatCallback cb, FileEntry e) {
        mainHandler.post(() -> cb.onResult(e));
    }

    private void postStart(TransferCallback cb, long total) {
        mainHandler.post(() -> cb.onStart(total));
    }

    private void postProgress(TransferCallback cb, long done, long total) {
        mainHandler.post(() -> cb.onProgress(done, total));
    }

    private void postSuccess(TransferCallback cb, File dest) {
        mainHandler.post(() -> cb.onSuccess(dest));
    }

    private void postError(TransferCallback cb, FileTransportException e) {
        mainHandler.post(() -> cb.onError(e));
    }

    private void postCancelled(TransferCallback cb) {
        mainHandler.post(cb::onCancelled);
    }

    // ============================================================
    //  Утилиты
    // ============================================================

    private static String enc(String s) {
        if (s == null) return "";
        try { return URLEncoder.encode(s, "UTF-8"); }
        catch (Throwable t) { return s; }
    }

    private static String joinPath(String dir, String name) {
        if (dir == null) return name;
        if (name == null) return dir;
        if (dir.endsWith("/")) return dir + name;
        return dir + "/" + name;
    }

    private static JSONObject tryParseJson(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return new JSONObject(s); } catch (Throwable t) { return null; }
    }

    private static String shortStr(String s) {
        if (s == null) return "null";
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (Throwable ignored) {}
    }
}