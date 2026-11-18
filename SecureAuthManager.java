package com.my.mealse.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.UserNotAuthenticatedException;
import android.util.Base64;
import android.util.Log;

import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class SecureAuthManager {

  private static final String TAG = "SecureAuth";
  private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

  // === Alias: обычный AES-ключ (шифрование фоновых данных) ===
  private static final String KEY_ALIAS_NORMAL = "mealse_master_key_v1";

  // === Alias: секретный AES-ключ (требует биометрию) ===
  private static final String KEY_ALIAS_SECURE = "mealse_secure_key_v1";

  // === Alias: RSA-ключ (только для fingerprint) ===
  private static final String KEY_ALIAS_FINGERPRINT = "mealse_fingerprint_key_v1";

  private static final String PREF_NAME = "mealse_secure_prefs";
  private static final String KEY_FINGERPRINT = "device_fingerprint";
  private static final String KEY_FINGERPRINT_SECURE = "device_fingerprint_secure";

  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final int GCM_TAG_LENGTH_BITS = 128;
  private static final int IV_LENGTH_BYTES = 12;
  private static final int AES_KEY_SIZE_BITS = 256;
  private static final int RSA_KEY_SIZE_BITS = 2048;

  // -1 = требовать промпт каждый раз; >0 = секунд после разблокировки
  private static final int SECURE_KEY_VALIDITY_SECONDS = -1;

  private final Context appContext;
  private final SharedPreferences prefs;
  private final SecureRandom secureRandom = new SecureRandom();
  private final MealseLogger log;

  // Кэш AES-ключей
  private SecretKey cachedNormalKey;
  private SecretKey cachedSecureKey;

  // Кэш fingerprint
  private String cachedFingerprint;
  private String cachedSecureFingerprint;

  // ============================================================
  // Конструктор
  // ============================================================

  public SecureAuthManager(Context context) {
    this.appContext = context.getApplicationContext();
    this.log = MealseLogger.get(appContext, TAG);
    this.prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

    log.log("══ SecureAuthManager создан ══");
    log.log("Log dir: " + MealseLogger.getLogDirPath(appContext));
  }

  // ============================================================
  // Публичный API — обычный режим
  // ============================================================

  public synchronized String encrypt(String plaintext) {
    return encrypt(plaintext, false);
  }

  public synchronized String decrypt(String encryptedBase64) {
    return decrypt(encryptedBase64, false);
  }

  /**
   * Возвращает fingerprint устройства (по обычному RSA-ключу).
   */
  public synchronized String getDeviceFingerprint() {
    return getDeviceFingerprint(false);
  }

  // ============================================================
  // Универсальный API
  // ============================================================

  public synchronized String encrypt(String plaintext, boolean secure) {
    if (plaintext == null)
      return null;
    try {
      log.log("encrypt(secure=" + secure + "): " + plaintext.length() + " симв.");

      SecretKey key = getOrCreateAesKey(secure);
      if (key == null) {
        log.error("encrypt: AES-ключ null");
        return null;
      }

      Cipher cipher = initCipher(Cipher.ENCRYPT_MODE, key);
      if (cipher == null) {
        log.error("encrypt: cipher null");
        return null;
      }

      byte[] ciphertext = cipher.doFinal(plaintext.getBytes("UTF-8"));
      byte[] iv = cipher.getIV();

      if (iv == null || iv.length != IV_LENGTH_BYTES) {
        log.error("encrypt: неверный IV");
        return null;
      }

      byte[] combined = new byte[iv.length + ciphertext.length];
      System.arraycopy(iv, 0, combined, 0, iv.length);
      System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

      String result = Base64.encodeToString(combined, Base64.NO_WRAP);
      log.log("encrypt OK: " + result.length() + " симв. base64");
      return result;

    } catch (UserNotAuthenticatedException e) {
      log.warn("encrypt: пользователь не аутентифицирован");
      return null;
    } catch (Exception e) {
      log.logError("encrypt: ошибка", e);
      return null;
    }
  }

  public synchronized String decrypt(String encryptedBase64, boolean secure) {
    if (encryptedBase64 == null)
      return null;
    try {
      log.log("decrypt(secure=" + secure + ")");

      SecretKey key = getOrCreateAesKey(secure);
      if (key == null) {
        log.error("decrypt: AES-ключ null");
        return null;
      }

      byte[] combined = Base64.decode(encryptedBase64, Base64.NO_WRAP);
      if (combined.length < IV_LENGTH_BYTES + 16) {
        log.error("decrypt: слишком короткий шифротекст");
        return null;
      }

      byte[] iv = new byte[IV_LENGTH_BYTES];
      byte[] ciphertext = new byte[combined.length - IV_LENGTH_BYTES];
      System.arraycopy(combined, 0, iv, 0, IV_LENGTH_BYTES);
      System.arraycopy(combined, IV_LENGTH_BYTES, ciphertext, 0, ciphertext.length);

      Cipher cipher = initCipher(Cipher.DECRYPT_MODE, key, iv);
      if (cipher == null) {
        log.error("decrypt: cipher null");
        return null;
      }

      byte[] plaintext = cipher.doFinal(ciphertext);
      String result = new String(plaintext, "UTF-8");
      log.log("decrypt OK: " + result.length() + " симв.");
      return result;

    } catch (UserNotAuthenticatedException e) {
      log.warn("decrypt: пользователь не аутентифицирован");
      return null;
    } catch (Exception e) {
      log.logError("decrypt: ошибка", e);
      return null;
    }
  }

  /**
   * Возвращает fingerprint устройства.
   *
   * Логика: 1. Кэш в памяти 2. SharedPreferences 3. Генерация RSA-ключа (если нет) → вычисление SHA-256 от публичного ключа
   *
   * @param secure если true — возвращает fingerprint secure-ключа (в текущей реализации оба режима используют один RSA-ключ для стабильности fingerprint; значение параметра оставлено для совместимости API)
   */
  public synchronized String getDeviceFingerprint(boolean secure) {
    final String tag = secure ? "secure" : "insecure";
    log.log("══ getDeviceFingerprint(" + tag + ") START ══");

    try {
      // 1. Проверка кэша
      String cached = secure ? cachedSecureFingerprint : cachedFingerprint;
      log.log("[кэш] cachedFingerprint=" + cachedFingerprint + ", cachedSecureFingerprint="
          + cachedSecureFingerprint);
      log.log("[кэш] выбран (" + tag + "): " + cached);

      if (cached != null && !cached.isEmpty()) {
        log.log("→ Fingerprint из кэша: " + cached);
        return cached;
      }
      log.log("[кэш] пуст — читаем SharedPreferences");

      // 2. SharedPreferences
      String prefKey = secure ? KEY_FINGERPRINT_SECURE : KEY_FINGERPRINT;
      log.log("[prefs] ключ=" + prefKey);
      String saved = prefs.getString(prefKey, null);
      log.log("[prefs] значение=" + saved);

      if (saved != null && !saved.isEmpty()) {
        if (secure)
          cachedSecureFingerprint = saved;
        else
          cachedFingerprint = saved;
        log.log("→ Fingerprint из prefs: " + saved);
        return saved;
      }
      log.log("[prefs] пусто — генерируем из RSA-сертификата");

      // 3. Генерируем/читаем RSA-ключ
      log.log("[RSA] вызов getOrCreateFingerprintCert()");
      java.security.cert.Certificate cert = getOrCreateFingerprintCert();
      if (cert == null) {
        log.error("Не удалось получить RSA-сертификат");
        return null;
      }
      log.log("[RSA] сертификат получен: type=" + cert.getType() + ", класс="
          + cert.getClass().getSimpleName());

      // 4. Вычисляем SHA-256
      log.log("[SHA-256] вычисляем fingerprint из сертификата");
      String fingerprint = computeSha256FromCert(cert);
      log.log("[SHA-256] результат: " + fingerprint);

      if (fingerprint == null || fingerprint.isEmpty()) {
        log.error("Fingerprint не вычислен");
        return null;
      }

      // 5. Сохраняем
      prefs.edit().putString(prefKey, fingerprint).apply();
      if (secure)
        cachedSecureFingerprint = fingerprint;
      else
        cachedFingerprint = fingerprint;

      log.log("→ Fingerprint сохранён и возвращён: " + fingerprint);
      return fingerprint;

    } catch (Exception e) {
      log.logError("getDeviceFingerprint: критическая ошибка", e);
      return null;
    } finally {
      log.log("══ getDeviceFingerprint(" + tag + ") END ══");
    }
  }

  public synchronized void rotateKey(boolean secure) {
    String alias = secure ? KEY_ALIAS_SECURE : KEY_ALIAS_NORMAL;
    String prefKey = secure ? KEY_FINGERPRINT_SECURE : KEY_FINGERPRINT;

    log.log("rotateKey(secure=" + secure + "), alias=" + alias);

    try {
      KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
      ks.load(null);
      if (ks.containsAlias(alias)) {
        ks.deleteEntry(alias);
        log.log("Старый AES-ключ удалён");
      }
    } catch (Exception e) {
      log.logError("Ошибка удаления AES-ключа", e);
    }

    if (secure) {
      cachedSecureKey = null;
      // fingerprint secure не сбрасываем — он от RSA, не от AES
    } else {
      cachedNormalKey = null;
      cachedFingerprint = null;
      prefs.edit().remove(prefKey).apply();
    }

    getOrCreateAesKey(secure);
    log.log("rotateKey завершён");
  }

  /**
   * Ротация RSA-ключа fingerprint. ⚠️ Это изменит fingerprint устройства! Использовать осторожно.
   */
  public synchronized void rotateFingerprintKey() {
    log.log("rotateFingerprintKey START");

    try {
      KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
      ks.load(null);
      if (ks.containsAlias(KEY_ALIAS_FINGERPRINT)) {
        ks.deleteEntry(KEY_ALIAS_FINGERPRINT);
        log.log("RSA-ключ удалён");
      }
    } catch (Exception e) {
      log.logError("Ошибка удаления RSA-ключа", e);
    }

    // Сбрасываем кэш и prefs
    cachedFingerprint = null;
    cachedSecureFingerprint = null;
    prefs.edit().remove(KEY_FINGERPRINT).remove(KEY_FINGERPRINT_SECURE).apply();

    // Генерируем новый
    String newFp = getDeviceFingerprint(false);
    log.log("rotateFingerprintKey завершён, новый fingerprint: " + newFp);
  }

  public synchronized Cipher getCipherForBiometric(int mode) {
    try {
      log.log("getCipherForBiometric(mode=" + mode + ")");
      SecretKey key = getOrCreateAesKey(true);
      if (key == null)
        return null;

      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(mode, key);
      return cipher;
    } catch (Exception e) {
      log.logError("getCipherForBiometric: ошибка", e);
      return null;
    }
  }

  // ============================================================
  // RSA-ключ для fingerprint
  // ============================================================

  /**
   * Возвращает X509-сертификат RSA-ключа fingerprint. Создаёт ключ при первом обращении.
   *
   * ⚠️ Для RSA-ключей Android Keystore ВСЕГДА создаёт X509Certificate. Для AES — никогда. Поэтому fingerprint вычисляется именно от RSA.
   */
  private java.security.cert.Certificate getOrCreateFingerprintCert() {
    log.log("getOrCreateFingerprintCert START");

    try {
      KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
      ks.load(null);

      // 1. Если ключ уже есть — читаем сертификат
      if (ks.containsAlias(KEY_ALIAS_FINGERPRINT)) {
        java.security.cert.Certificate cert = ks.getCertificate(KEY_ALIAS_FINGERPRINT);
        if (cert instanceof X509Certificate) {
          log.log("RSA-сертификат прочитан");
          return cert;
        } else {
          log.warn("RSA-сертификат не X509 или null, пересоздаём");
          ks.deleteEntry(KEY_ALIAS_FINGERPRINT);
        }
      }

      // 2. Генерируем новый RSA-ключ
      log.log("Генерация RSA-" + RSA_KEY_SIZE_BITS + " ключа для fingerprint");
      KeyPairGenerator kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA,
          ANDROID_KEYSTORE);

      KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(KEY_ALIAS_FINGERPRINT,
          KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY).setKeySize(RSA_KEY_SIZE_BITS)
          .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
          .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
          .setUserAuthenticationRequired(false);

      // На API 30+ — можно указать явно
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        // Ничего особенного — просто используем дефолт
      }

      kpg.initialize(builder.build());
      kpg.generateKeyPair();
      log.log("RSA-ключ сгенерирован");

      // 3. Читаем сертификат
      java.security.cert.Certificate cert = ks.getCertificate(KEY_ALIAS_FINGERPRINT);
      if (cert instanceof X509Certificate) {
        log.log("RSA-сертификат получен после генерации");
        return cert;
      } else {
        log.error("После генерации RSA сертификат всё ещё null");
        return null;
      }

    } catch (Exception e) {
      log.logError("getOrCreateFingerprintCert: ошибка", e);
      return null;
    }
  }

  /**
   * Вычисляет SHA-256 от публичного ключа сертификата. Возвращает 32 hex-символа (первые 16 байт хеша).
   */
  private String computeSha256FromCert(java.security.cert.Certificate cert) {
    try {
      if (!(cert instanceof X509Certificate)) {
        log.error("computeSha256FromCert: не X509");
        return null;
      }

      X509Certificate x509 = (X509Certificate) cert;
      java.security.PublicKey publicKey = x509.getPublicKey();
      if (publicKey == null) {
        log.error("computeSha256FromCert: publicKey null");
        return null;
      }

      byte[] pubKeyBytes = publicKey.getEncoded();
      if (pubKeyBytes == null || pubKeyBytes.length == 0) {
        log.error("computeSha256FromCert: pubKeyBytes пустой");
        return null;
      }

      log.log("PublicKey encoded: " + pubKeyBytes.length + " байт");

      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(pubKeyBytes);
      log.log("SHA-256 hash: " + hash.length + " байт");

      StringBuilder sb = new StringBuilder(32);
      for (int i = 0; i < 16 && i < hash.length; i++) {
        sb.append(String.format("%02x", hash[i]));
      }

      return sb.toString();

    } catch (Exception e) {
      log.logError("computeSha256FromCert: ошибка", e);
      return null;
    }
  }

  // ============================================================
  // AES-ключи (для encrypt/decrypt)
  // ============================================================

  private SecretKey getOrCreateAesKey(boolean secure) {
    SecretKey cached = secure ? cachedSecureKey : cachedNormalKey;
    if (cached != null) {
      log.log("getOrCreateAesKey(secure=" + secure + "): из кэша");
      return cached;
    }

    String alias = secure ? KEY_ALIAS_SECURE : KEY_ALIAS_NORMAL;
    log.log("getOrCreateAesKey(secure=" + secure + "), alias=" + alias);

    try {
      KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
      ks.load(null);

      if (ks.containsAlias(alias)) {
        try {
          KeyStore.Entry entry = ks.getEntry(alias, null);
          if (entry instanceof KeyStore.SecretKeyEntry) {
            SecretKey key = ((KeyStore.SecretKeyEntry) entry).getSecretKey();
            log.log("AES-ключ прочитан");
            if (secure)
              cachedSecureKey = key;
            else
              cachedNormalKey = key;
            return key;
          } else {
            log.warn("Entry не SecretKeyEntry, удаляем");
            ks.deleteEntry(alias);
          }
        } catch (Exception e) {
          log.logError("Не удалось прочитать AES-ключ, удаляем", e);
          try {
            ks.deleteEntry(alias);
          } catch (Exception ignored) {
          }
        }
      }

      log.log("Генерация нового AES-ключа");
      return generateAesKey(secure);

    } catch (Exception e) {
      log.logError("getOrCreateAesKey: критическая ошибка", e);
      return null;
    }
  }

  private SecretKey generateAesKey(boolean secure) throws Exception {
    String alias = secure ? KEY_ALIAS_SECURE : KEY_ALIAS_NORMAL;
    log.log("generateAesKey START: alias=" + alias + ", secure=" + secure);

    KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
        ANDROID_KEYSTORE);

    KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(alias,
        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(AES_KEY_SIZE_BITS)
        .setRandomizedEncryptionRequired(true);

    if (secure) {
      builder.setUserAuthenticationRequired(true);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        builder.setUserAuthenticationParameters(SECURE_KEY_VALIDITY_SECONDS,
            KeyProperties.AUTH_BIOMETRIC_STRONG | KeyProperties.AUTH_DEVICE_CREDENTIAL);
      } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        builder.setUserAuthenticationValidityDurationSeconds(
            SECURE_KEY_VALIDITY_SECONDS > 0 ? SECURE_KEY_VALIDITY_SECONDS : 0);
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        builder.setInvalidatedByBiometricEnrollment(true);
      }
    } else {
      builder.setUserAuthenticationRequired(false);
    }

    keyGenerator.init(builder.build());
    SecretKey key = keyGenerator.generateKey();
    log.log("AES-ключ сгенерирован");

    if (secure)
      cachedSecureKey = key;
    else
      cachedNormalKey = key;

    return key;
  }

  // ============================================================
  // Cipher helpers
  // ============================================================

  private Cipher initCipher(int mode, SecretKey key) throws Exception {
    Cipher cipher = Cipher.getInstance(TRANSFORMATION);
    cipher.init(mode, key);
    return cipher;
  }

  private Cipher initCipher(int mode, SecretKey key, byte[] iv) throws Exception {
    Cipher cipher = Cipher.getInstance(TRANSFORMATION);
    if (mode == Cipher.ENCRYPT_MODE) {
      cipher.init(Cipher.ENCRYPT_MODE, key);
    } else {
      if (iv == null)
        throw new IllegalArgumentException("Для decrypt нужен IV");
      GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
      cipher.init(Cipher.DECRYPT_MODE, key, spec);
    }
    return cipher;
  }
}