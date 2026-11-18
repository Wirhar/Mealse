package com.my.mealse.filemanager.wifidirect;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.my.mealse.filemanager.FileEntry;
import com.my.mealse.filemanager.FileTransportException;
import com.my.mealse.filemanager.server.RemoteFsAuth;
import com.my.mealse.filemanager.server.RemoteFsPathGuard;
import com.my.mealse.util.MealseLogger;
import com.my.mealse.util.WifiDirectManager;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ★ НОВОЕ: серверная сторона бинарного протокола Wi-Fi Direct.
 *
 * Обрабатывает входящие TYPE_FS_REQUEST от удалённого узла: - info / list / stat - mkdir / rename / delete / move / copy / search - download (шлём FS_RESPONSE + серия FS_CHUNK) - upload (принимаем чанки, по завершении отвечаем)
 *
 * Отправляет ответы через WifiDirectManager.sendFileFrame().
 *
 * Один экземпляр на приложение (singleton), подписывается на WifiDirectManager.FileFrameListener в MealseApp.onCreate.
 *
 * Использует те же RemoteFsPathGuard + RemoteFsAuth, что и HTTP-сервер RemoteFsService — то есть корень /getFilesDir() и та же авторизация по fingerprint/deviceId (из HTTPS-заголовков, здесь — из handshake Wi-Fi Direct, который уже лежит в WifiDirectManager).
 */
public class WifiDirectFileServer {

  private static final String TAG = "WifiDirectFileServer";

  private static volatile WifiDirectFileServer instance;

  private final Context appContext;
  private final MealseLogger log;
  private final WifiDirectManager wdm;
  private final RemoteFsPathGuard pathGuard;
  private final RemoteFsAuth auth;

  private final ExecutorService executor = Executors.newCachedThreadPool();
  private final Handler mainHandler = new Handler(Looper.getMainLooper());

  private final WifiDirectManager.FileFrameListener frameListener;

  /** Активные upload'ы: reqId → состояние. */
  private final Map<Integer, UploadState> activeUploads = new ConcurrentHashMap<>();

  // ============================================================
  // Singleton
  // ============================================================

  private WifiDirectFileServer(Context context) {
    this.appContext = context.getApplicationContext();
    this.log = MealseLogger.get(appContext, TAG);
    this.wdm = ((com.my.mealse.MealseApp) appContext).getWifiDirectManager();

    if (wdm == null) {
      log.error("WifiDirectManager == null — сервер не запущен");
      this.pathGuard = null;
      this.auth = null;
      this.frameListener = null;
      return;
    }

    this.pathGuard = new RemoteFsPathGuard(appContext);
    this.auth = new RemoteFsAuth(appContext);

    this.frameListener = this::onFrame;
    wdm.addFileFrameListener(frameListener);

    log.log("════ WifiDirectFileServer запущен ════");
  }

  public static synchronized WifiDirectFileServer get(Context context) {
    if (instance == null) {
      instance = new WifiDirectFileServer(context);
    }
    return instance;
  }

  public void shutdown() {
    try {
      if (wdm != null && frameListener != null) {
        wdm.removeFileFrameListener(frameListener);
      }
    } catch (Throwable ignored) {
    }
    executor.shutdownNow();
    log.log("WifiDirectFileServer остановлен");
  }

  // ============================================================
  // Обработка входящих кадров
  // ============================================================

  private void onFrame(WifiDirectFrame.Frame frame) {
    if (frame == null)
      return;

    switch (frame.type) {
      case WifiDirectFrame.TYPE_FS_REQUEST:
        handleRequest(frame.body);
        break;
      case WifiDirectFrame.TYPE_FS_CHUNK:
        handleChunk(WifiDirectFrame.parseChunk(frame.body));
        break;
      case WifiDirectFrame.TYPE_FS_CANCEL:
        handleCancel(WifiDirectFrame.parseCancel(frame.body));
        break;
      // FS_RESPONSE / FS_ERROR — это ответы на НАШИ запросы,
      // их обрабатывает WifiDirectFileTransport, не мы.
      default:
        // Игнорируем тихо — лог уже сделан в транспорте.
        break;
    }
  }

  private void handleRequest(byte[] body) {
    try {
      String json = WifiDirectFrame.bodyToJson(body);
      JSONObject req = new JSONObject(json);

      long reqId = req.optLong("req_id", 0);
      String op = req.optString("op", "");
      String path = req.optString("path", null);
      JSONObject params = req.optJSONObject("params");

      log.log("→ FS_REQUEST req#" + reqId + " op=" + op + " path=" + path);

      if (op == null || op.isEmpty()) {
        sendError(reqId, FileTransportException.CODE_INVALID_REQUEST, "op is required");
        return;
      }

      // ★ Авторизация по fingerprint/deviceId удалённого узла
      // (из handshake Wi-Fi Direct)
      if (auth != null) {
        WifiDirectManager.Peer remote = wdm.getRemotePeer();
        String fp = remote != null ? remote.fingerprint : null;
        String did = remote != null ? remote.deviceId : null;
        RemoteFsAuth.AuthResult ar = auth.authorize(fp, did, "wifidirect");
        if (!ar.allowed) {
          log.warn("req#" + reqId + " auth denied: " + ar.reason);
          sendError(reqId, FileTransportException.CODE_ACCESS_DENIED,
              "access denied: " + ar.reason);
          return;
        }
      }

      executor.execute(() -> dispatch(reqId, op, path, params));

    } catch (JSONException e) {
      log.logError("handleRequest: не JSON", e);
    } catch (Throwable t) {
      log.logError("handleRequest: критическая ошибка", t);
    }
  }

  private void dispatch(long reqId, String op, String path, JSONObject params) {
    try {
      switch (op) {
        case "info":
          doInfo(reqId);
          break;
        case "list":
          doList(reqId, path);
          break;
        case "stat":
          doStat(reqId, path);
          break;
        case "mkdir":
          doMkdir(reqId, path, params);
          break;
        case "rename":
          doRename(reqId, path, params);
          break;
        case "delete":
          doDelete(reqId, path, params);
          break;
        case "move":
          doMove(reqId, path, params);
          break;
        case "copy":
          doCopy(reqId, path, params);
          break;
        case "search":
          doSearch(reqId, path, params);
          break;
        case "download":
          doDownload(reqId, path);
          break;
        case "upload":
          doUploadStart(reqId, path, params);
          break;
        case "upload_end":
          doUploadEnd(reqId);
          break;
        default:
          sendError(reqId, FileTransportException.CODE_INVALID_REQUEST, "unknown op: " + op);
      }
    } catch (FileTransportException fte) {
      sendError(reqId, fte.getCode(), fte.getMessage());
    } catch (Throwable t) {
      log.logError("dispatch(" + op + ")", t);
      sendError(reqId, FileTransportException.CODE_UNKNOWN, t.getMessage());
    }
  }

  // ============================================================
  // info / list / stat
  // ============================================================

  private void doInfo(long reqId) throws FileTransportException {
    try {
      JSONObject data = new JSONObject();
      data.put("rootPath", pathGuard.getVirtualRoot());

      JSONArray subs = new JSONArray();
      for (String name : pathGuard.getDefaultSubdirs())
        subs.put(name);
      data.put("subdirs", subs);
      data.put("protocol", 1);
      data.put("server", "Mealse WifiDirectFileServer");
      sendOk(reqId, data);
    } catch (JSONException e) {
      throw new FileTransportException(FileTransportException.CODE_UNKNOWN,
          "info: " + e.getMessage(), e);
    }
  }

  private void doList(long reqId, String virtualPath) throws FileTransportException {
    String vp = (virtualPath == null) ? "/" : virtualPath;
    File dir = pathGuard.resolveAbsolute(vp);
    if (dir == null)
      throw forbidden(vp);
    if (!dir.exists())
      throw notFound(vp);
    if (!dir.isDirectory())
      throw badRequest("not a directory");

    File[] children = dir.listFiles();
    if (children == null)
      children = new File[0];

    JSONArray arr = new JSONArray();
    for (File f : children) {
      JSONObject j = fileToJson(f);
      if (j != null)
        arr.put(j);
    }

    try {
      JSONObject data = new JSONObject();
      data.put("entries", arr);
      data.put("path", pathGuard.toVirtualPath(dir));
      sendOk(reqId, data);
    } catch (JSONException e) {
      throw new FileTransportException(FileTransportException.CODE_UNKNOWN,
          "list: " + e.getMessage(), e);
    }
  }

  private void doStat(long reqId, String virtualPath) throws FileTransportException {
    File f = pathGuard.resolveAbsolute(virtualPath);
    if (f == null)
      throw forbidden(virtualPath);
    if (!f.exists())
      throw notFound(virtualPath);

    JSONObject j = fileToJson(f);
    if (j == null)
      throw new FileTransportException(FileTransportException.CODE_UNKNOWN, "cannot serialize");

    try {
      JSONObject data = new JSONObject();
      data.put("entry", j);
      sendOk(reqId, data);
    } catch (JSONException e) {
      throw new FileTransportException(FileTransportException.CODE_UNKNOWN,
          "stat: " + e.getMessage(), e);
    }
  }

  // ============================================================
  // Модифицирующие операции
  // ============================================================

  private void doMkdir(long reqId, String parentVirtual, JSONObject params)
      throws FileTransportException {
    String name = params != null ? params.optString("name", null) : null;
    if (parentVirtual == null || name == null)
      throw badRequest("path+name required");
    if (name.isEmpty() || name.contains("/") || name.contains("\\"))
      throw badRequest("invalid name");

    File parent = pathGuard.resolveAbsolute(parentVirtual);
    if (parent == null || !parent.exists() || !parent.isDirectory())
      throw badRequest("parent invalid");

    String parentV = pathGuard.toVirtualPath(parent);
    if (parentV == null)
      throw forbidden("parent");

    File newDir = pathGuard.resolveAbsolute(joinVirtual(parentV, name));
    if (newDir == null)
      throw forbidden("target");
    if (newDir.exists())
      throw alreadyExists(name);
    if (!newDir.mkdirs())
      throw new FileTransportException(FileTransportException.CODE_SERVER_ERROR, "mkdir failed");

    sendOk(reqId, new JSONObject());
  }

  private void doRename(long reqId, String virtualPath, JSONObject params)
      throws FileTransportException {
    String newName = params != null ? params.optString("new_name", null) : null;
    if (virtualPath == null || newName == null || newName.contains("/") || newName.contains("\\"))
      throw badRequest("invalid args");

    File src = pathGuard.resolveAbsolute(virtualPath);
    if (src == null || !src.exists())
      throw notFound(virtualPath);

    String parentV = pathGuard.toVirtualPath(src.getParentFile());
    if (parentV == null)
      throw forbidden("parent");

    File dst = pathGuard.resolveAbsolute(joinVirtual(parentV, newName));
    if (dst == null)
      throw forbidden("target");
    if (dst.exists())
      throw alreadyExists(newName);
    if (!src.renameTo(dst))
      throw new FileTransportException(FileTransportException.CODE_SERVER_ERROR, "rename failed");

    sendOk(reqId, new JSONObject());
  }

  private void doDelete(long reqId, String virtualPath, JSONObject params)
      throws FileTransportException {
    boolean recursive = params != null && params.optBoolean("recursive", false);

    File target = pathGuard.resolveAbsolute(virtualPath);
    if (target == null)
      throw forbidden(virtualPath);
    if (!target.exists())
      throw notFound(virtualPath);
    if (pathGuard.getVirtualRoot().equals(pathGuard.toVirtualPath(target)))
      throw forbidden("cannot delete root");

    boolean ok = (target.isDirectory() && recursive) ? deleteRecursive(target) : target.delete();
    if (!ok)
      throw new FileTransportException(FileTransportException.CODE_SERVER_ERROR, "delete failed");

    sendOk(reqId, new JSONObject());
  }

  private void doMove(long reqId, String srcV, JSONObject params) throws FileTransportException {
    String dstDirV = params != null ? params.optString("dst_dir", null) : null;

    File src = pathGuard.resolveAbsolute(srcV);
    File dstDir = pathGuard.resolveAbsolute(dstDirV);
    if (src == null || dstDir == null)
      throw forbidden("forbidden");
    if (!src.exists())
      throw notFound(srcV);
    if (!dstDir.exists() || !dstDir.isDirectory())
      throw badRequest("dst invalid");

    String dstV = pathGuard.toVirtualPath(dstDir);
    if (dstV == null)
      throw forbidden("dst");

    File target = pathGuard.resolveAbsolute(joinVirtual(dstV, src.getName()));
    if (target == null)
      throw forbidden("target");
    if (target.exists())
      throw alreadyExists(target.getName());

    if (!src.renameTo(target)) {
      boolean copied = src.isDirectory() ? copyRecursive(src, target) : copyFile(src, target);
      if (copied && deleteRecursive(src)) {
        sendOk(reqId, new JSONObject());
        return;
      }
      throw new FileTransportException(FileTransportException.CODE_SERVER_ERROR, "move failed");
    }
    sendOk(reqId, new JSONObject());
  }

  private void doCopy(long reqId, String srcV, JSONObject params) throws FileTransportException {
    String dstDirV = params != null ? params.optString("dst_dir", null) : null;

    File src = pathGuard.resolveAbsolute(srcV);
    File dstDir = pathGuard.resolveAbsolute(dstDirV);
    if (src == null || dstDir == null)
      throw forbidden("forbidden");
    if (!src.exists())
      throw notFound(srcV);
    if (!dstDir.exists() || !dstDir.isDirectory())
      throw badRequest("dst invalid");

    String dstV = pathGuard.toVirtualPath(dstDir);
    if (dstV == null)
      throw forbidden("dst");

    File target = pathGuard.resolveAbsolute(joinVirtual(dstV, src.getName()));
    if (target == null)
      throw forbidden("target");
    if (target.exists())
      throw alreadyExists(target.getName());

    boolean ok = src.isDirectory() ? copyRecursive(src, target) : copyFile(src, target);
    if (!ok)
      throw new FileTransportException(FileTransportException.CODE_SERVER_ERROR, "copy failed");

    sendOk(reqId, new JSONObject());
  }

  private void doSearch(long reqId, String startV, JSONObject params)
      throws FileTransportException {
    String q = params != null ? params.optString("q", null) : null;
    if (q == null || q.isEmpty())
      throw badRequest("q required");

    File root = pathGuard.resolveAbsolute(startV);
    if (root == null || !root.exists() || !root.isDirectory())
      throw notFound(startV);

    List<File> found = new ArrayList<>();
    searchRecursive(root, q.toLowerCase(), found, 500);

    JSONArray arr = new JSONArray();
    for (File f : found) {
      JSONObject j = fileToJson(f);
      if (j != null)
        arr.put(j);
    }

    try {
      JSONObject data = new JSONObject();
      data.put("entries", arr);
      data.put("path", pathGuard.toVirtualPath(root));
      sendOk(reqId, data);
    } catch (JSONException e) {
      throw new FileTransportException(FileTransportException.CODE_UNKNOWN,
          "search: " + e.getMessage(), e);
    }
  }

  // ============================================================
  // download: FS_RESPONSE + серия FS_CHUNK
  // ============================================================

  private void doDownload(long reqId, String virtualPath) throws FileTransportException {
    File f = pathGuard.resolveAbsolute(virtualPath);
    if (f == null)
      throw forbidden(virtualPath);
    if (!f.exists() || !f.isFile())
      throw notFound(virtualPath);

    final long size = f.length();

    // 1. Шлём FS_RESPONSE с метаданными
    try {
      JSONObject data = new JSONObject();
      data.put("size", size);
      data.put("mime", guessMime(f.getName()));
      JSONObject resp = buildResponse(reqId, data);
      wdm.sendFileFrame(
          WifiDirectFrame.packJson(WifiDirectFrame.TYPE_FS_RESPONSE, resp.toString()));
    } catch (JSONException e) {
      throw new FileTransportException(FileTransportException.CODE_UNKNOWN,
          "download resp: " + e.getMessage(), e);
    }

    // 2. Отправляем файл чанками — в фоновом потоке, чтобы не держать
    // диспетчер.
    executor.execute(() -> streamFileToPeer(reqId, f, size));
  }

  private void streamFileToPeer(long reqId, File f, long size) {
    final int chunkSize = WifiDirectFrame.MAX_CHUNK_DATA;
    long offset = 0;

    try (InputStream in = new BufferedInputStream(new FileInputStream(f), chunkSize)) {
      byte[] buf = new byte[chunkSize];
      int r;
      while ((r = in.read(buf)) != -1) {
        byte[] frame = WifiDirectFrame.packChunk((int) reqId, offset, buf, r);
        wdm.sendFileFrame(frame);
        offset += r;

        // Небольшая пауза, чтобы не забить TCP-буфер в 0
        if (offset % (chunkSize * 16) == 0) {
          try {
            Thread.sleep(5);
          } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            break;
          }
        }
      }
      log.log(
          "download req#" + reqId + " завершён: отправлено " + offset + " из " + size + " байт");
    } catch (IOException e) {
      log.logError("streamFileToPeer req#" + reqId, e);
      sendError(reqId, FileTransportException.CODE_IO, e.getMessage());
    }
  }

  // ============================================================
  // upload: принимаем FS_CHUNK-и
  // ============================================================

  private void doUploadStart(long reqId, String fullV, JSONObject params)
      throws FileTransportException {
    String name = params != null ? params.optString("name", null) : null;
    long size = params != null ? params.optLong("size", -1) : -1;

    if (fullV == null || name == null)
      throw badRequest("path+name required");
    if (name.isEmpty() || name.contains("/") || name.contains("\\"))
      throw badRequest("invalid name");

    File resolvedFull = pathGuard.resolveAbsolute(fullV);
    if (resolvedFull == null)
      throw forbidden("target");

    File dstDir = resolvedFull.getParentFile();
    if (dstDir == null)
      throw badRequest("no parent");

    String dstDirV = pathGuard.toVirtualPath(dstDir);
    if (dstDirV == null)
      throw forbidden("dst");

    File realDstDir = pathGuard.resolveAbsolute(dstDirV);
    if (realDstDir == null || !realDstDir.exists() || !realDstDir.isDirectory())
      throw badRequest("dst invalid");

    File target = pathGuard.resolveAbsolute(joinVirtual(dstDirV, name));
    if (target == null)
      throw forbidden("target");

    if (target.exists()) {
      String policy = params.optString("policy", "fail");
      if ("overwrite".equals(policy)) {
        if (!target.delete())
          throw new FileTransportException(FileTransportException.CODE_SERVER_ERROR,
              "cannot overwrite");
      } else if ("rename".equals(policy)) {
        target = autoRename(target);
      } else {
        throw alreadyExists(name);
      }
    }

    OutputStream out;
    try {
      out = new BufferedOutputStream(new FileOutputStream(target), 64 * 1024);
    } catch (IOException e) {
      throw new FileTransportException(FileTransportException.CODE_IO,
          "open dst: " + e.getMessage(), e);
    }

    int reqIdInt = (int) reqId;
    UploadState st = new UploadState(reqIdInt, target, out, size);
    activeUploads.put(reqIdInt, st);

    // Отвечаем "готовы принимать"
    try {
      JSONObject data = new JSONObject();
      data.put("ready", true);
      JSONObject resp = buildResponse(reqId, data);
      wdm.sendFileFrame(
          WifiDirectFrame.packJson(WifiDirectFrame.TYPE_FS_RESPONSE, resp.toString()));
    } catch (JSONException e) {
      closeQuietly(out);
      activeUploads.remove((int) reqId);
      throw new FileTransportException(FileTransportException.CODE_UNKNOWN,
          "upload resp: " + e.getMessage(), e);
    }
  }

  private void handleChunk(WifiDirectFrame.ChunkData chunk) {
    if (chunk == null)
      return;
    UploadState st = activeUploads.get(chunk.reqId);
    if (st == null) {
      log.warn("handleChunk: нет активного upload для req#" + chunk.reqId);
      return;
    }
    if (st.cancelled)
      return;

    try {
      st.out.write(chunk.data);
      st.received += chunk.data.length;
    } catch (IOException e) {
      log.logError("handleChunk write req#" + chunk.reqId, e);
      st.cancelled = true;
      closeQuietly(st.out);
      activeUploads.remove(chunk.reqId);
      sendError(chunk.reqId, FileTransportException.CODE_IO, e.getMessage());
    }
  }

  private void doUploadEnd(long reqId) {
    UploadState st = activeUploads.remove((int) reqId);
    if (st == null) {
      log.warn("doUploadEnd: нет активного upload для req#" + reqId);
      return;
    }
    closeQuietly(st.out);

    if (st.cancelled) {
      sendError(reqId, FileTransportException.CODE_CANCELLED, "cancelled");
      return;
    }

    try {
      JSONObject data = new JSONObject();
      data.put("finished", true);
      data.put("size", st.received);
      JSONObject resp = buildResponse(reqId, data);
      wdm.sendFileFrame(
          WifiDirectFrame.packJson(WifiDirectFrame.TYPE_FS_RESPONSE, resp.toString()));
      log.log("upload req#" + reqId + " завершён: " + st.received + " байт → "
          + st.target.getAbsolutePath());
    } catch (JSONException e) {
      log.logError("doUploadEnd", e);
    }
  }

  private void handleCancel(int reqId) {
    UploadState st = activeUploads.remove(reqId);
    if (st != null) {
      st.cancelled = true;
      closeQuietly(st.out);
      log.log("upload req#" + reqId + " отменён клиентом");
    }
  }

  // ============================================================
  // Отправка ответов
  // ============================================================

  private void sendOk(long reqId, JSONObject data) {
    try {
      JSONObject resp = buildResponse(reqId, data);
      wdm.sendFileFrame(
          WifiDirectFrame.packJson(WifiDirectFrame.TYPE_FS_RESPONSE, resp.toString()));
    } catch (Throwable t) {
      log.logError("sendOk", t);
    }
  }

  private void sendError(long reqId, int code, String message) {
    try {
      JSONObject resp = new JSONObject();
      resp.put("v", 1);
      resp.put("req_id", reqId);
      resp.put("status", "error");
      resp.put("code", code);
      resp.put("message", message != null ? message : "unknown");
      wdm.sendFileFrame(WifiDirectFrame.packJson(WifiDirectFrame.TYPE_FS_ERROR, resp.toString()));
    } catch (Throwable t) {
      log.logError("sendError", t);
    }
  }

  private JSONObject buildResponse(long reqId, JSONObject data) throws JSONException {
    JSONObject resp = new JSONObject();
    resp.put("v", 1);
    resp.put("req_id", reqId);
    resp.put("status", "ok");
    resp.put("data", data != null ? data : new JSONObject());
    return resp;
  }

  // ============================================================
  // Вспомогательные
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
      byte[] buf = new byte[64 * 1024];
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
        for (File k : kids) {
          if (!copyRecursive(k, new File(dst, k.getName())))
            return false;
        }
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

  private static void closeQuietly(java.io.Closeable c) {
    if (c == null)
      return;
    try {
      c.close();
    } catch (Throwable ignored) {
    }
  }

  private static FileTransportException forbidden(String p) {
    return new FileTransportException(FileTransportException.CODE_PATH_FORBIDDEN,
        "path forbidden: " + p);
  }

  private static FileTransportException notFound(String p) {
    return new FileTransportException(FileTransportException.CODE_NOT_FOUND, "not found: " + p);
  }

  private static FileTransportException alreadyExists(String p) {
    return new FileTransportException(FileTransportException.CODE_ALREADY_EXISTS,
        "already exists: " + p);
  }

  private static FileTransportException badRequest(String m) {
    return new FileTransportException(FileTransportException.CODE_INVALID_REQUEST, m);
  }

  private static class UploadState {
    final int reqId;
    final File target;
    final OutputStream out;
    final long expectedSize;
    long received = 0;
    volatile boolean cancelled = false;

    UploadState(int reqId, File target, OutputStream out, long expectedSize) {
      this.reqId = reqId;
      this.target = target;
      this.out = out;
      this.expectedSize = expectedSize;
    }
  }
} {
      c.close();
    } catch (Throwable ignored) {
    }
  }
}