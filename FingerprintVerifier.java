package com.my.mealse.util;

import android.content.Context;
import android.util.Log;

import com.my.mealse.model.TrustedNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Проверяет fingerprint удалённого устройства против сохранённых узлов.
 *
 * Логика:
 *  - MATCH: fingerprint найден в списке узлов → доверяем.
 *  - FIRST_CONTACT: узел(ы) с таким deviceId найдены, но fingerprint ещё не сохранён
 *                   ни у одного из них → сохраняем fingerprint ВСЕМ, доверяем.
 *  - MISMATCH: узел(ы) с таким deviceId найдены, и хотя бы у одного fingerprint
 *              отличается от полученного → не доверяем, показываем предупреждение.
 *  - UNKNOWN: узел вообще не найден → игнорируем.
 *
 * ★ РЕВИЗИЯ:
 *  - Учитывается, что у устройства может быть НЕСКОЛЬКО узлов (Wi-Fi и 4G с одним
 *    fingerprint, но разными id/ip). Fingerprint проставляется/сверяется у ВСЕХ.
 *  - FIRST_CONTACT ставит fingerprint всем узлам с этим deviceId, а не только первому.
 *  - MISMATCH показывается, если хотя бы у одного узла с этим deviceId fingerprint
 *    НЕ пуст и НЕ совпадает. Если пуст у всех — это FIRST_CONTACT, не MISMATCH.
 *  - VerificationResult.matchedNodes содержит все узлы, у которых fingerprint совпал
 *    (полезно для диагностики/будущего UI). Основное поле node — первый из них
 *    (обратная совместимость с IncomingCallNotifier).
 */
public class FingerprintVerifier {

  private static final String TAG = "FingerprintVerifier";

  public enum Result {
    MATCH,           // fingerprint совпал с сохранённым
    FIRST_CONTACT,   // первое подключение — fingerprint сохранён
    MISMATCH,        // fingerprint не совпадает — возможная MITM
    UNKNOWN          // узел не найден
  }

  public static class VerificationResult {
    public final Result result;
    /** Первый найденный узел (для UI). null для UNKNOWN. */
    public final TrustedNode node;
    /** Все узлы, у которых совпал fingerprint (для диагностики). */
    public final List<TrustedNode> matchedNodes;
    /** Человекочитаемое описание. */
    public final String message;

    public VerificationResult(Result result, TrustedNode node,
                              List<TrustedNode> matchedNodes, String message) {
      this.result = result;
      this.node = node;
      this.matchedNodes = matchedNodes != null ? matchedNodes : new ArrayList<>();
      this.message = message;
    }
  }

  private final NodeManager nodeManager;

  public FingerprintVerifier(Context context) {
    this.nodeManager = new NodeManager(context);
  }

  /**
   * Проверяет fingerprint узла.
   *
   * @param deviceId    ID удалённого устройства (из сигнала)
   * @param fingerprint fingerprint удалённого устройства
   */
  public VerificationResult verify(String deviceId, String fingerprint) {
    Log.d(TAG, "verify START: deviceId=" + deviceId + ", fingerprint=" + fingerprint);

    if (fingerprint == null || fingerprint.isEmpty()) {
      Log.w(TAG, "Пустой fingerprint от deviceId=" + deviceId);
      // Если fingerprint пуст, но deviceId известен — попробуем найти узел,
      // чтобы хотя бы сообщить пользователю «от такого-то устройства».
      if (deviceId != null && !deviceId.isEmpty()) {
        List<TrustedNode> byDeviceId = nodeManager.findAllByDeviceId(deviceId);
        if (!byDeviceId.isEmpty()) {
          TrustedNode first = byDeviceId.get(0);
          return new VerificationResult(
              Result.UNKNOWN,
              first,
              byDeviceId,
              "Fingerprint отсутствует в сигнале — доверие не подтверждено");
        }
      }
      return new VerificationResult(Result.UNKNOWN, null, null, "Fingerprint отсутствует");
    }

    // === 1. Есть ли узел(ы) с таким fingerprint? ===
    List<TrustedNode> byFingerprint = nodeManager.findAllByFingerprint(fingerprint);
    if (!byFingerprint.isEmpty()) {
      TrustedNode first = byFingerprint.get(0);
      Log.d(TAG, "Fingerprint MATCH: найдено " + byFingerprint.size()
          + " узлов, первый — " + first.getName() + " (id=" + first.getId() + ")");
      return new VerificationResult(
          Result.MATCH,
          first,
          byFingerprint,
          "Fingerprint совпадает: " + first.getName()
              + (byFingerprint.size() > 1 ? " (+" + (byFingerprint.size() - 1) + ")" : ""));
    }

    // === 2. Есть ли узел(ы) с таким deviceId? ===
    if (deviceId == null || deviceId.isEmpty()) {
      Log.d(TAG, "deviceId пуст и fingerprint не найден → UNKNOWN");
      return new VerificationResult(
          Result.UNKNOWN, null, null,
          "Устройство не найдено в доверенных (нет deviceId)");
    }

    List<TrustedNode> byDeviceId = nodeManager.findAllByDeviceId(deviceId);
    if (byDeviceId.isEmpty()) {
      Log.d(TAG, "Узел с deviceId=" + deviceId + " не найден → UNKNOWN");
      return new VerificationResult(
          Result.UNKNOWN, null, null,
          "Устройство не найдено в доверенных");
    }

    Log.d(TAG, "Найдено " + byDeviceId.size() + " узлов с deviceId=" + deviceId);

    // === 3. Разбираем по fingerprint: пуст / совпал / не совпал ===
    List<TrustedNode> emptyFpNodes = new ArrayList<>();
    List<TrustedNode> mismatchedNodes = new ArrayList<>();
    List<TrustedNode> matchedNodes = new ArrayList<>();

    for (TrustedNode n : byDeviceId) {
      String existingFp = n.getFingerprint();
      if (existingFp == null || existingFp.isEmpty()) {
        emptyFpNodes.add(n);
      } else if (existingFp.equals(fingerprint)) {
        matchedNodes.add(n);
      } else {
        mismatchedNodes.add(n);
      }
    }

    // === 3a. MISMATCH: хотя бы один узел с другим fingerprint ===
    if (!mismatchedNodes.isEmpty()) {
      TrustedNode bad = mismatchedNodes.get(0);
      Log.w(TAG, "Fingerprint MISMATCH для " + bad.getName()
          + " (id=" + bad.getId() + ")"
          + "! Ожидался: " + bad.getFingerprint()
          + ", получен: " + fingerprint);
      return new VerificationResult(
          Result.MISMATCH,
          bad,
          mismatchedNodes,
          "ВНИМАНИЕ: fingerprint не совпадает для " + bad.getName()
              + "!\nВозможна MITM-атака.");
    }

    // === 3b. Все совпали (или часть совпала, часть пустых) ===
    if (!matchedNodes.isEmpty()) {
      // Дозаполняем пустые fingerprint — доверие уже подтверждено.
      if (!emptyFpNodes.isEmpty()) {
        Log.d(TAG, "Fingerprint MATCH с дозаполнением " + emptyFpNodes.size()
            + " узлов (fingerprint совпал у " + matchedNodes.size() + ")");
        applyFingerprintToNodes(emptyFpNodes, fingerprint);
      }
      TrustedNode first = matchedNodes.get(0);
      List<TrustedNode> allMatched = new ArrayList<>(matchedNodes);
      allMatched.addAll(emptyFpNodes);
      return new VerificationResult(
          Result.MATCH,
          first,
          allMatched,
          "Fingerprint совпадает: " + first.getName()
              + (allMatched.size() > 1 ? " (+" + (allMatched.size() - 1) + ")" : ""));
    }

    // === 3c. Все пусты — FIRST_CONTACT ===
    // Проставляем fingerprint ВСЕМ узлам с этим deviceId.
    Log.d(TAG, "FIRST_CONTACT: все " + emptyFpNodes.size()
        + " узлов без fingerprint, сохраняю всем: " + fingerprint);
    applyFingerprintToNodes(emptyFpNodes, fingerprint);

    TrustedNode first = emptyFpNodes.get(0);
    return new VerificationResult(
        Result.FIRST_CONTACT,
        first,
        emptyFpNodes,
        "Первое подключение к " + first.getName()
            + (emptyFpNodes.size() > 1
                ? " (+" + (emptyFpNodes.size() - 1) + " узлов того же устройства)"
                : ""));
  }

  /**
   * ★ Проставляет fingerprint всем переданным узлам.
   * Использует новый saveNode(node, selfFp, selfDeviceId) для корректного
   * self-отсечения (fingerprint не может быть self).
   */
  private void applyFingerprintToNodes(List<TrustedNode> nodes, String fingerprint) {
    for (TrustedNode n : nodes) {
      try {
        String oldFp = n.getFingerprint();
        n.setFingerprint(fingerprint);
        boolean ok = nodeManager.saveNode(n);
        if (ok) {
          Log.d(TAG, "applyFingerprintToNodes: id=" + n.getId()
              + ", name=" + n.getName()
              + ", oldFp=" + oldFp + " → newFp=" + fingerprint);
        } else {
          Log.w(TAG, "applyFingerprintToNodes: saveNode вернул false для id=" + n.getId()
              + " — возможно self?");
          // Вернём старое значение, чтобы не «терять» данные
          n.setFingerprint(oldFp);
        }
      } catch (Exception e) {
        Log.e(TAG, "applyFingerprintToNodes: ошибка сохранения id=" + n.getId(), e);
      }
    }
  }
}