package com.my.mealse.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Хранит состояние активного звонка в SharedPreferences.
 * Используется, чтобы ServerService / MealseApp знали: открыт ли звонок.
 *
 * ★ ИЗМЕНЕНО (heartbeat + автосброс «зомби»):
 *
 *   Раньше флаг call_active мог «зависнуть» навсегда, если CallActivity
 *   убита жёстко (MIUI task-kill, Don't keep activities, OOM) БЕЗ onDestroy.
 *   Тогда:
 *     - ServerService.handleIncomingOffer видел isCallActive==true →
 *       отправлял busy-bye новому звонку («зомби-занятость»);
 *     - MealseApp.handleWifiDirectSignal игнорировал offer;
 *     - CallActivity.onCreate → finish() «Уже идёт другой звонок».
 *
 *   Теперь:
 *     - setCallActive ставит lastHeartbeat = now;
 *     - CallActivity (в onResume/onPause) вызывает heartbeat() раз в 5 с;
 *     - isCallActive() проверяет свежесть: если heartbeat старше
 *       HEARTBEAT_TIMEOUT_MS (15 с) — флаг автоматически сбрасывается
 *       и возвращается false.
 *
 *   Ограничение: heartbeat в onResume/onPause работает, пока Activity
 *   жива. Если Activity в фоне и НЕ убита — heartbeat останавливается,
 *   и через 15 с isCallActive() вернёт false, хотя звонок жив (retained-
 *   движок в WebView). Для сценария «свернул, звонок продолжается» это
 *   НЕПРАВИЛЬНО. Поэтому:
 *     - при retained-движке (sWebView жив) CallActivity.onPause НЕ
 *       останавливает heartbeat — он идёт из onResume до onDestroy;
 *     - onDestroy (detach без destroy) heartbeat НЕ трогает;
 *     - останавливает heartbeat только endCallInternal (через
 *       clearCallActive).
 *   См. комментарий в CallActivity.onResume / onDestroy.
 *
 * Потокобезопасен (synchronized на this).
 */
public class CallStateManager {

  private static final String PREF_NAME = "mealse_call_state";
  private static final String KEY_CALL_ACTIVE = "call_active";
  private static final String KEY_CALL_ID = "active_call_id";
  private static final String KEY_TARGET_NODE = "active_target_node";
  private static final String KEY_LAST_HEARTBEAT = "last_heartbeat";

  /**
   * ★ НОВОЕ: порог «зомби». Если last_heartbeat старше — звонок считается
   * мёртвым, isCallActive() вернёт false и автосбросит флаг.
   *
   * 15 с — компромисс: с одной стороны, CallActivity шлёт heartbeat раз в
   * 5 с, так что 3 пропущенных подряд = смерть. С другой — не сбрасываем
   * при кратком GC-стопе или momentary UI-зависании.
   */
  private static final long HEARTBEAT_TIMEOUT_MS = 15_000L;

  private final SharedPreferences prefs;

  public CallStateManager(Context context) {
    this.prefs = context.getApplicationContext()
        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
  }

  // ============================================================
  // Запись
  // ============================================================

  /**
   * Помечает, что звонок активен (вызывается из CallActivity.onCreate).
   * ★ ИЗМЕНЕНО: дополнительно ставит lastHeartbeat = now.
   */
  public synchronized void setCallActive(String callId, String targetNodeId) {
    prefs.edit()
        .putBoolean(KEY_CALL_ACTIVE, true)
        .putString(KEY_CALL_ID, callId)
        .putString(KEY_TARGET_NODE, targetNodeId)
        .putLong(KEY_LAST_HEARTBEAT, System.currentTimeMillis())
        .apply();
  }

  /**
   * ★ НОВОЕ: обновляет timestamp heartbeat.
   * Вызывать из CallActivity раз в 5 с (onResume → postDelayed loop).
   * Если флаг call_active не выставлен — no-op (не создаём «зомби»).
   */
  public synchronized void heartbeat() {
    if (!prefs.getBoolean(KEY_CALL_ACTIVE, false)) {
      return;
    }
    prefs.edit()
        .putLong(KEY_LAST_HEARTBEAT, System.currentTimeMillis())
        .apply();
  }

  /**
   * Снимает флаг (вызывается из CallActivity.endCallInternal — единая
   * точка завершения).
   *
   * ★ ИЗМЕНЕНО: дополнительно удаляет lastHeartbeat.
   *
   * ★ ВАЖНО: НЕ вызывать из onDestroy. Если onDestroy вызван системой
   * (Don't keep activities / MIUI task-kill), а звонок не завершён —
   * retained-движок жив, и clearCallActive() здесь создаст «зомби-
   * свободность»: следующий offer от собеседника будет принят как новый
   * входящий, хотя старый звонок идёт.
   */
  public synchronized void clearCallActive() {
    prefs.edit()
        .putBoolean(KEY_CALL_ACTIVE, false)
        .remove(KEY_CALL_ID)
        .remove(KEY_TARGET_NODE)
        .remove(KEY_LAST_HEARTBEAT)
        .apply();
  }

  // ============================================================
  // Чтение
  // ============================================================

  /**
   * ★ ИЗМЕНЕНО: возвращает true ТОЛЬКО если флаг выставлен И heartbeat
   * свежий (не старше HEARTBEAT_TIMEOUT_MS).
   *
   * Если heartbeat старый (или отсутствует — старый формат prefs) —
   * флаг автоматически сбрасывается и возвращается false. Это лечит
   * «зомби-занятость» после жёсткого убийства CallActivity без onDestroy.
   */
  public synchronized boolean isCallActive() {
    if (!prefs.getBoolean(KEY_CALL_ACTIVE, false)) {
      return false;
    }
    long last = prefs.getLong(KEY_LAST_HEARTBEAT, 0);
    if (last == 0) {
      // Старый формат (до heartbeat) — считаем мёртвым и сбрасываем.
      prefs.edit()
          .putBoolean(KEY_CALL_ACTIVE, false)
          .remove(KEY_CALL_ID)
          .remove(KEY_TARGET_NODE)
          .remove(KEY_LAST_HEARTBEAT)
          .apply();
      return false;
    }
    long age = System.currentTimeMillis() - last;
    if (age > HEARTBEAT_TIMEOUT_MS) {
      // Автосброс «зомби».
      prefs.edit()
          .putBoolean(KEY_CALL_ACTIVE, false)
          .remove(KEY_CALL_ID)
          .remove(KEY_TARGET_NODE)
          .remove(KEY_LAST_HEARTBEAT)
          .apply();
      return false;
    }
    return true;
  }

  public synchronized String getActiveCallId() {
    return prefs.getString(KEY_CALL_ID, null);
  }

  public synchronized String getActiveTargetNode() {
    return prefs.getString(KEY_TARGET_NODE, null);
  }
}