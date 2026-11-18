package com.my.mealse.util;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Build;

/**
 * Безопасная регистрация BroadcastReceiver.
 *
 * На Android 13 (API 33) и выше Android требует явно указывать флаг:
 *  - RECEIVER_NOT_EXPORTED — если receiver слушает только свои broadcast'ы
 *  - RECEIVER_EXPORTED     — если другие приложения могут отправлять broadcast
 *
 * Без флага приложение падает с SecurityException.
 */
public final class BroadcastUtils {

  private BroadcastUtils() {}

  /**
   * Регистрирует receiver, слушающий только свои broadcast'ы
   * (из своего же приложения).
   */
  public static void registerNotExported(Context context,
                                          BroadcastReceiver receiver,
                                          IntentFilter filter) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
    } else {
      context.registerReceiver(receiver, filter);
    }
  }

  /**
   * Регистрирует receiver, которому могут отправлять broadcast'ы
   * другие приложения.
   *
   * ⚠️ Использовать осторожно — потенциальная уязвимость.
   */
  public static void registerExported(Context context,
                                       BroadcastReceiver receiver,
                                       IntentFilter filter) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
    } else {
      context.registerReceiver(receiver, filter);
    }
  }

  /**
   * Безопасно снимает регистрацию receiver'а.
   * Игнорирует IllegalArgumentException, если receiver не был зарегистрирован.
   */
  public static void unregisterSafely(Context context, BroadcastReceiver receiver) {
    if (context == null || receiver == null) return;
    try {
      context.unregisterReceiver(receiver);
    } catch (IllegalArgumentException e) {
      // receiver не был зарегистрирован — игнорируем
    }
  }
}