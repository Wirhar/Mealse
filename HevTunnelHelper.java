// HevTunnelHelper.java
package com.my.mealse.service;

import android.content.Context;

import com.my.mealse.util.MealseLogger;
import com.my.mealse.util.YamlValidator;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public final class HevTunnelHelper {

  private static final String TAG = "HevTunnelHelper";
  private static final String CONFIG_FILE = "hev-socks5-tunnel.yaml";
  private static final String HEV_LOG_FILE = "hev-socks5-tunnel.log";
  private static final String TUN_ADDRESS_V4 = "10.0.0.2";
  private static final String TUN_ADDRESS_V6 = "fd00::2";

  /** Сколько миллисекунд ждать после старта hev, прежде чем читать его состояние. */
  private static final long POST_START_DELAY_MS = 2000;

  /** Максимум строк logcat, которые считываем за один раз. */
  private static final int LOGCAT_MAX_LINES = 500;

  /** Таймаут для процесса logcat. */
  private static final int LOGCAT_TIMEOUT_SEC = 5;

  private final Context appContext;
  private final MealseLogger log;

  public HevTunnelHelper(Context context) {
    this.appContext = context.getApplicationContext();
    this.log = MealseLogger.get(appContext, TAG);
  }

  // ============================================================
  // start
  // ============================================================

  public boolean start(String socks5Address, int socks5Port, int tunFd) {
    try {
      String configPath = buildConfigFile(socks5Address, socks5Port);
      log.log("════════ HevTunnelHelper.start ════════");
      log.log("  socks5Address=" + socks5Address);
      log.log("  socks5Port=" + socks5Port);
      log.log("  tunFd=" + tunFd);
      log.log("  configPath=" + configPath);
      log.log("  TUN ipv4=" + TUN_ADDRESS_V4);
      log.log("  TUN ipv6=" + TUN_ADDRESS_V6);

      File configFile = new File(configPath);
      if (!configFile.exists() || configFile.length() == 0) {
        log.error("  ❌ Config file does NOT exist or is empty!");
        return false;
      }
      log.log("  Config file size=" + configFile.length() + " байт");

      // Валидация YAML независимо от hev
      log.log("  → Проверка YAML через YamlValidator...");
      YamlValidator.Result vr = YamlValidator.validate(configFile, log);
      if (!vr.valid) {
        log.error("  ❌ YAML INVALID!");
        log.error("  Причина: " + vr.message);
        log.error("  Разобранные значения: " + vr.values);
        return false;
      }
      log.log("  ✓ YAML валиден. Значения: " + vr.values);

      // Удаляем старый лог hev перед запуском
      deleteHevLog();

      // ★ Перед стартом — снимок logcat, чтобы отсечь старые сообщения
      clearLogcat();

      boolean ok = hev.htproxy.TProxyService.TProxyStartService(configPath, tunFd);
      log.log("  TProxyStartService → " + ok);

      // Проверка, что сервис «жив» с точки зрения JNI-обёртки
      try {
        boolean running = hev.htproxy.TProxyService.TProxyIsRunning();
        log.log("  TProxyIsRunning → " + running);
        if (!running) {
          log.error("  ❌ Tunnel NOT running after start!");
          readHevLog();
          readLogcat();
          return false;
        }
      } catch (Throwable t) {
        log.warn("  TProxyIsRunning check failed: " + t.getMessage());
      }

      // ★ Сразу после старта — счётчики
      readTProxyStats("after-start");

      // ★ Даём hev время инициализироваться, потом смотрим счётчики и логи
      try {
        Thread.sleep(POST_START_DELAY_MS);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }

      readTProxyStats("after-" + POST_START_DELAY_MS + "ms");
      readHevLog();
      readLogcat();

      log.log("════════════════════════════════════════");
      return ok;
    } catch (Throwable t) {
      log.logError("HevTunnelHelper.start", t);
      return false;
    }
  }

  // ============================================================
  // stop / isRunning
  // ============================================================

  public void stop() {
    try {
      // ★ Перед остановкой — финальный снимок счётчиков и logcat
      readTProxyStats("before-stop");
      readLogcat();

      boolean running = hev.htproxy.TProxyService.TProxyIsRunning();
      log.log("TProxyIsRunning (before stop) → " + running);

      boolean ok = hev.htproxy.TProxyService.TProxyStopService();
      log.log("TProxyStopService → " + ok);
    } catch (Throwable t) {
      log.logError("HevTunnelHelper.stop", t);
    }
  }

  public boolean isRunning() {
    try {
      return hev.htproxy.TProxyService.TProxyIsRunning();
    } catch (Throwable t) {
      return false;
    }
  }

  // ============================================================
  // YAML
  // ============================================================

  private String buildConfigFile(String socks5Address, int socks5Port) throws Exception {
    File f = new File(appContext.getFilesDir(), CONFIG_FILE);

    String addrYaml;
    if (socks5Address != null && socks5Address.contains(":")) {
      addrYaml = "'" + socks5Address + "'";
    } else {
      addrYaml = socks5Address;
    }

    StringBuilder sb = new StringBuilder();
    sb.append("tunnel:\n");
    sb.append("  mtu: 1500\n");
    sb.append("  ipv4: ").append(TUN_ADDRESS_V4).append("\n");
    sb.append("  ipv6: '").append(TUN_ADDRESS_V6).append("'\n");
    sb.append("\n");
    sb.append("socks5:\n");
    sb.append("  port: ").append(socks5Port).append("\n");
    sb.append("  address: ").append(addrYaml).append("\n");
    sb.append("  udp: true\n"); // ★ ИСПРАВЛЕНО: true вместо 'udp'
    sb.append("  pipeline: false\n");
    sb.append("\n");
    sb.append("misc:\n");
    sb.append("  log-level: debug\n");
    sb.append("  task-stack-size: 20480\n");

    String yaml = sb.toString();
    try (FileWriter w = new FileWriter(f)) {
      w.write(yaml);
      w.flush();
    }

    log.log("YAML записан (" + yaml.length() + " симв.):\n" + yaml);
    return f.getAbsolutePath();
  }

  // ============================================================
  // hev-socks5-tunnel.log
  // ============================================================

  private void deleteHevLog() {
    try {
      File logFile = new File(appContext.getFilesDir(), HEV_LOG_FILE);
      if (logFile.exists()) {
        if (logFile.delete()) {
          log.log("Старый лог hev удален: " + logFile.getAbsolutePath());
        } else {
          log.warn("Не удалось удалить старый лог hev");
        }
      }
    } catch (Exception e) {
      log.warn("Ошибка удаления лога hev: " + e.getMessage());
    }
  }

  private void readHevLog() {
    File logFile = new File(appContext.getFilesDir(), HEV_LOG_FILE);
    if (!logFile.exists()) {
      log.warn("Лог hev не найден: " + logFile.getAbsolutePath());
      return;
    }

    log.log("════════ hev-socks5-tunnel.log ════════");
    try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
      String line;
      while ((line = reader.readLine()) != null) {
        log.log("  HEV: " + line);
      }
    } catch (IOException e) {
      log.logError("Ошибка чтения лога hev", e);
    }
    log.log("═══════════════════════════════════════");
  }

  // ============================================================
  // TProxyGetStats (reflection)
  // ============================================================

  /**
   * Пытается вызвать hev.htproxy.TProxyService.TProxyGetStats(). Метод может не существовать в вашей версии .aar — тогда просто логируем.
   */
  private void readTProxyStats(String when) {
    log.log("════════ TProxyGetStats [" + when + "] ════════");
    try {
      Method m = hev.htproxy.TProxyService.class.getMethod("TProxyGetStats");
      Object result = m.invoke(null);
      log.log("  TProxyGetStats → " + result);
    } catch (NoSuchMethodException nsme) {
      log.warn("  TProxyGetStats недоступен (метод отсутствует в .aar)");
    } catch (Throwable t) {
      log.warn("  TProxyGetStats ошибка: " + t.getClass().getSimpleName() + ": " + t.getMessage());
    }
    log.log("════════════════════════════════════════");
  }

  // ============================================================
  // logcat
  // ============================================================

  /**
   * Очищает буфер logcat. Если не получится (нет разрешения) — не страшно.
   */
  private void clearLogcat() {
    try {
      Process p = new ProcessBuilder("logcat", "-c").redirectErrorStream(true).start();
      p.waitFor(2, TimeUnit.SECONDS);
      log.log("logcat -c выполнен");
    } catch (Throwable t) {
      log.warn("logcat -c не сработал: " + t.getMessage());
    }
  }

  /**
   * Читает logcat -d, фильтрует по ключевым словам и логирует результат.
   *
   * Фильтр: hev | tproxy | socks | native | fatal | signal | abort | dlopen Регистронезависимо.
   */
  private void readLogcat() {
    log.log("════════ logcat snapshot ════════");

    Process process = null;
    BufferedReader reader = null;
    try {
      // logcat -d — выгрузить буфер и завершиться
      // -v threadtime — минимальный формат с временем и потоком
      ProcessBuilder pb = new ProcessBuilder("logcat", "-d", "-v", "threadtime");
      pb.redirectErrorStream(true);
      process = pb.start();

      reader = new BufferedReader(
          new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

      int matched = 0;
      int total = 0;
      String line;
      while ((line = reader.readLine()) != null) {
        total++;
        if (total > LOGCAT_MAX_LINES) {
          log.warn("  logcat: превышен лимит " + LOGCAT_MAX_LINES + " строк — обрываю");
          break;
        }
        if (matchesKeywords(line)) {
          matched++;
          log.log("  LOGCAT: " + line);
        }
      }

      // Дадим процессу завершиться (на всякий случай)
      try {
        process.waitFor(LOGCAT_TIMEOUT_SEC, TimeUnit.SECONDS);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }

      log.log("  logcat: прочитано " + total + " строк, совпадений: " + matched);

    } catch (Throwable t) {
      log.warn(
          "logcat не удалось прочитать: " + t.getClass().getSimpleName() + ": " + t.getMessage());
    } finally {
      if (reader != null) {
        try {
          reader.close();
        } catch (IOException ignored) {
        }
      }
      if (process != null) {
        process.destroy();
      }
    }

    log.log("═══════════════════════════════════");
  }

  /** Проверка, что строка logcat содержит одно из ключевых слов. */
  private boolean matchesKeywords(String line) {
    if (line == null || line.isEmpty())
      return false;
    String lower = line.toLowerCase();
    return lower.contains("hev") || lower.contains("tproxy") || lower.contains("socks")
        || lower.contains("libhev") || lower.contains("native") || lower.contains("fatal")
        || lower.contains("signal 11") || lower.contains("signal 6") || lower.contains("abort")
        || lower.contains("dlopen") || lower.contains("enotfound") || lower.contains("library");
  }
}.contains("library");
  }
}