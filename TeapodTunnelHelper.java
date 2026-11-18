package com.my.mealse.service;

import android.content.Context;
import android.os.ParcelFileDescriptor;

import com.teapodstream.tun2socks.TeapodVpnManager;
import com.teapodstream.tun2socks.WhitelistMode;
import com.my.mealse.util.MealseLogger;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * ★ Обёртка над teapod-tun2socks.
 *
 * Заменяет HevTunnelHelper. Использует Kotlin-API TeapodVpnManager.
 *
 * ★ ТОЧНАЯ СИГНАТУРА start(): start(ParcelFileDescriptor tunFd, int mtu, String socksHost, int socksPort, String socksUsername, String socksPassword, boolean allowIcmp, boolean blockQuic, Set<Integer> allowedUids, WhitelistMode whitelistMode, int cacheCapacity, int cacheTtlSeconds, UidValidator customValidator)
 *
 * ★ ВАЖНО: - socksUsername / socksPassword НЕ могут быть null → передаём "". - allowedUids НЕ может быть null → передаём Collections.emptySet(). - tunFd — это ParcelFileDescriptor (сам vpnInterface), не int.
 *
 * ★ РЕВИЗИЯ (метрики): добавлен ScheduledExecutorService для периодического
 * логирования uploadBytes/downloadBytes/cacheSize. Это критично для
 * диагностики проблемы downloadBytes=0.
 */
public class TeapodTunnelHelper {

  private static final String TAG = "TeapodTunnelHelper";

  /** MTU по умолчанию. */
  private static final int DEFAULT_MTU = 1500;
  /** Размер кэша UID-валидаций. */
  private static final int DEFAULT_CACHE_CAPACITY = 10000;
  /** TTL кэша в секундах. */
  private static final int DEFAULT_CACHE_TTL_SEC = 300;
  /** ★ Интервал логирования метрик. */
  private static final long METRICS_INTERVAL_SEC = 5;

  private final Context appContext;
  private final MealseLogger log;

  private TeapodVpnManager vpnManager;
  private ScheduledExecutorService metricsExecutor;
  private volatile long lastUploadBytes = 0;
  private volatile long lastDownloadBytes = 0;
  private volatile long metricsStartTime = 0;

  public TeapodTunnelHelper(Context context) {
    this.appContext = context.getApplicationContext();
    this.log = MealseLogger.get(appContext, TAG);
  }

  /**
   * Запускает tun2socks.
   *
   * @param socks5Address IP-адрес SOCKS5-прокси (шлюза), например "192.168.1.123"
   * @param socks5Port    Порт SOCKS5-прокси, например 1080
   * @param vpnInterface  ParcelFileDescriptor из VpnService.Builder.establish()
   * @return true, если запуск успешен
   */
  public boolean start(String socks5Address, int socks5Port, ParcelFileDescriptor vpnInterface) {
    log.log("════════ TeapodTunnelHelper.start ════════");
    log.log("  socks5Address=" + socks5Address);
    log.log("  socks5Port=" + socks5Port);
    log.log("  vpnInterface=" + (vpnInterface != null ? "fd=" + vpnInterface.getFd() : "null"));

    if (vpnInterface == null) {
      log.error("❌ vpnInterface == null — нечего запускать");
      return false;
    }

    try {
      // 1. Создаём менеджер
      log.log("  Создание TeapodVpnManager...");
      vpnManager = new TeapodVpnManager(appContext);
      log.log("  ✓ TeapodVpnManager создан");

      // 2. Включаем отладку Go-слоя (полезно для первого теста)
      try {
        vpnManager.setDebugLogging(true);
        log.log("  setDebugLogging(true) — OK");
      } catch (Throwable t) {
        log.warn("  setDebugLogging недоступен: " + t.getMessage());
      }

      // 3. Настраиваем UID-валидацию.
      // ★ Для ПЕРВОГО теста — ALLOW_ALL, чтобы убедиться, что туннель работает.
      // Потом можно переключить на DENY_ONLY с нашим UID.
      Set<Integer> allowedUids = new HashSet<>();
      WhitelistMode mode = WhitelistMode.ALLOW_ALL;

      // ★ Рабочий вариант (раскомментировать после первого успешного теста):
      // allowedUids.add(android.os.Process.myUid());
      // mode = WhitelistMode.DENY_ONLY;

      log.log("  allowedUids=" + allowedUids + " (size=" + allowedUids.size() + ")");
      log.log("  whitelistMode=" + mode);

      // 4. Запускаем.
      // ★ ВАЖНО: socksUsername и socksPassword — НЕ null, а "".
      // allowedUids — НЕ null, а пустой Set.
      log.log("  Вызов vpnManager.start(...) с параметрами:"
          + " mtu=" + DEFAULT_MTU
          + ", socksHost=" + socks5Address
          + ", socksPort=" + socks5Port
          + ", allowIcmp=true, blockQuic=false"
          + ", cacheCapacity=" + DEFAULT_CACHE_CAPACITY
          + ", cacheTtl=" + DEFAULT_CACHE_TTL_SEC);

      vpnManager.start(vpnInterface, // ParcelFileDescriptor (не getFd()!)
          DEFAULT_MTU, // 1500
          socks5Address, // socksHost
          socks5Port, // socksPort
          "", // socksUsername (НЕ null!)
          "", // socksPassword (НЕ null!)
          true, // allowIcmp — разрешить ping
          false, // blockQuic — не блокировать QUIC
          allowedUids, // Set<Integer> (НЕ null!)
          mode, // WhitelistMode
          DEFAULT_CACHE_CAPACITY, // 10000
          DEFAULT_CACHE_TTL_SEC, // 300
          null // customValidator
      );
      log.log("  ✓ vpnManager.start(...) вернулся");

      // 5. Проверяем статус
      boolean running = vpnManager.isRunning();
      log.log("  isRunning → " + running);

      if (!running) {
        log.error("❌ TeapodTunnelHelper: tunnel NOT running after start!");
        return false;
      }

      log.log("✓ TeapodTunnelHelper: tunnel запущен");
      log.log("  НАЧАЛЬНЫЕ метрики:");
      log.log("    cacheSize=" + safeGetCacheSize());
      log.log("    uploadBytes=" + safeGetUploadBytes());
      log.log("    downloadBytes=" + safeGetDownloadBytes());

      // ★ 6. Запускаем периодический вывод метрик
      startMetricsPolling();

      log.log("════════════════════════════════════════");
      return true;

    } catch (Throwable t) {
      log.logError("TeapodTunnelHelper.start FAILED", t);
      log.error("  Исключение: " + t.getClass().getName() + ": " + t.getMessage());
      if (t.getCause() != null) {
        log.error("  Причина: " + t.getCause().getClass().getName()
            + ": " + t.getCause().getMessage());
      }
      try {
        if (vpnManager != null) {
          log.log("  Попытка vpnManager.stop() после ошибки...");
          vpnManager.stop();
        }
      } catch (Throwable stopErr) {
        log.warn("  Ошибка при stop() после сбоя: " + stopErr.getMessage());
      }
      vpnManager = null;
      stopMetricsPolling();
      return false;
    }
  }

  /**
   * ★ НОВОЕ: периодическое логирование метрик.
   * Каждые 5 секунд выводит uploadBytes/downloadBytes/cacheSize и их
   * изменение с прошлого замера. Это критично для диагностики.
   */
  private void startMetricsPolling() {
    if (metricsExecutor != null) {
      return;
    }
    lastUploadBytes = 0;
    lastDownloadBytes = 0;
    metricsStartTime = System.currentTimeMillis();

    metricsExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "TeapodMetrics");
      t.setDaemon(true);
      return t;
    });

    metricsExecutor.scheduleWithFixedDelay(() -> {
      try {
        if (vpnManager == null) {
          log.warn("METRICS: vpnManager == null — прекращаю опрос");
          stopMetricsPolling();
          return;
        }

        long up = safeGetUploadBytes();
        long down = safeGetDownloadBytes();
        int cache = safeGetCacheSize();
        boolean running = isRunning();

        long upDelta = up - lastUploadBytes;
        long downDelta = down - lastDownloadBytes;

        long elapsedSec = (System.currentTimeMillis() - metricsStartTime) / 1000;

        log.log("METRICS[t=" + elapsedSec + "s]"
            + " running=" + running
            + " cache=" + cache
            + " up=" + up + "B (+" + upDelta + ")"
            + " down=" + down + "B (+" + downDelta + ")");

        lastUploadBytes = up;
        lastDownloadBytes = down;

        // ★ Если давно нет обратного трафика — предупреждаем
        if (elapsedSec > 20 && down == 0) {
          log.warn("METRICS: downloadBytes=0 уже " + elapsedSec
              + "с — обратный трафик не идёт!");
        }

      } catch (Throwable t) {
        log.logError("METRICS: ошибка опроса", t);
      }
    }, METRICS_INTERVAL_SEC, METRICS_INTERVAL_SEC, TimeUnit.SECONDS);

    log.log("  Метрики: запущен опрос каждые " + METRICS_INTERVAL_SEC + "с");
  }

  private void stopMetricsPolling() {
    if (metricsExecutor != null) {
      try {
        metricsExecutor.shutdownNow();
      } catch (Exception ignored) {
      }
      metricsExecutor = null;
      log.log("  Метрики: опрос остановлен");
    }
  }

  /**
   * Останавливает tun2socks.
   */
  public void stop() {
    log.log("════════ TeapodTunnelHelper.stop ════════");

    // ★ Финальный вывод метрик перед остановкой
    try {
      if (vpnManager != null) {
        log.log("  ФИНАЛЬНЫЕ метрики:");
        log.log("    cacheSize=" + safeGetCacheSize());
        log.log("    uploadBytes=" + safeGetUploadBytes());
        log.log("    downloadBytes=" + safeGetDownloadBytes());
      }
    } catch (Throwable t) {
      log.warn("  Ошибка чтения финальных метрик: " + t.getMessage());
    }

    stopMetricsPolling();

    try {
      if (vpnManager != null) {
        log.log("  vpnManager.stop()...");
        vpnManager.stop();
        log.log("  vpnManager.stop() завершён");
      } else {
        log.log("  vpnManager == null — нечего останавливать");
      }
    } catch (Throwable t) {
      log.logError("TeapodTunnelHelper.stop", t);
    } finally {
      vpnManager = null;
    }
    log.log("════════════════════════════════════════");
  }

  /**
   * Проверяет, запущен ли туннель.
   */
  public boolean isRunning() {
    try {
      return vpnManager != null && vpnManager.isRunning();
    } catch (Throwable t) {
      return false;
    }
  }

  // ============================================================
  // Метрики (для отладки)
  // ============================================================

  public int safeGetCacheSize() {
    try {
      return vpnManager != null ? vpnManager.getCacheSize() : -1;
    } catch (Throwable t) {
      return -1;
    }
  }

  public long safeGetUploadBytes() {
    try {
      return vpnManager != null ? vpnManager.getUploadBytes() : -1;
    } catch (Throwable t) {
      return -1;
    }
  }

  public long safeGetDownloadBytes() {
    try {
      return vpnManager != null ? vpnManager.getDownloadBytes() : -1;
    } catch (Throwable t) {
      return -1;
    }
  }
}    }
  }
}