package com.my.mealse.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Хранит состояние активного звонка в SharedPreferences.
 * Используется, чтобы ServerService знал: открыт ли CallActivity.
 * Потокобезопасен.
 */
public class CallStateManager {

  private static final String PREF_NAME = "mealse_call_state";
  private static final String KEY_CALL_ACTIVE = "call_active";
  private static final String KEY_CALL_ID = "active_call_id";
  private static final String KEY_TARGET_NODE = "active_target_node";

  private final SharedPreferences prefs;

  public CallStateManager(Context context) {
    this.prefs = context.getApplicationContext()
        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
  }

  /** Помечает, что звонок активен (вызывается из CallActivity.onCreate). */
  public synchronized void setCallActive(String callId, String targetNodeId) {
    prefs.edit()
        .putBoolean(KEY_CALL_ACTIVE, true)
        .putString(KEY_CALL_ID, callId)
        .putString(KEY_TARGET_NODE, targetNodeId)
        .apply();
  }

  /** Снимает флаг (вызывается из CallActivity.onDestroy). */
  public synchronized void clearCallActive() {
    prefs.edit()
        .putBoolean(KEY_CALL_ACTIVE, false)
        .remove(KEY_CALL_ID)
        .remove(KEY_TARGET_NODE)
        .apply();
  }

  public synchronized boolean isCallActive() {
    return prefs.getBoolean(KEY_CALL_ACTIVE, false);
  }

  public synchronized String getActiveCallId() {
    return prefs.getString(KEY_CALL_ID, null);
  }

  public synchronized String getActiveTargetNode() {
    return prefs.getString(KEY_TARGET_NODE, null);
  }
}