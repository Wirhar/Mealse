package com.my.mealse.messenger.crypto;

import android.content.Context;

import androidx.annotation.Nullable;

import com.my.mealse.util.MealseLogger;
import com.my.mealse.util.SecureAuthManager;

/**
 * Шифрование payload сообщений.
 *
 * ★ MVP (текущее состояние):
 *   Шифрование ВЫКЛЮЧЕНО. encrypt() возвращает CryptoResult.disabled()
 *   с blob = null. decrypt() возвращает CryptoResult.disabled()
 *   с plaintext = null. Транспорт и репозитории в этом случае работают
 *   с Message.body как plaintext.
 *
 * ★ Почему выключено:
 *   SecureAuthManager.encrypt/decrypt использует ЛОКАЛЬНЫЙ Android Keystore.
 *   Ключ не экспортируется. Значит, зашифровать сообщение для ДРУГОГО
 *   устройства этим API нельзя — получатель не сможет расшифровать
 *   (у него другой Keystore).
 *
 *   Для настоящего E2E нужен ECDH-обмен публичными ключами через
 *   сигналинг (/ping отдаёт публичный ключ, приватный остаётся в Keystore).
 *   Это отдельный большой блок работы (Шаг «E2E» после MVP).
 *
 * ★ API финальный:
 *   Когда включим E2E, поменяется только тело encrypt()/decrypt().
 *   Транспорт (MessageEnvelope, HttpMessageSender, WifiDirectMessageSender)
 *   и репозитории (MessageRepository) уже будут вызывать MessageCipher
 *   так, как будто шифрование работает:
 *
 *     CryptoResult r = MessageCipher.encrypt(ctx, plaintext);
 *     if (r.isSuccess()) {
 *       byte[] blob = r.getBlob();  // null в MVP, iv+ciphertext в E2E
 *       ...
 *     }
 *
 *   Поэтому включать E2E можно будет без переписывания транспорта.
 *
 * ★ Где сейчас «шифруется» в MVP:
 *   Нигде. Message.body = plaintext. Message.encryptedBody = null.
 *   PayloadCodec.encode(payload) → JSON → Message.body.
 *
 * ★ Что можно использовать уже сейчас:
 *   SecureAuthManager.getDeviceFingerprint() — стабильно работает,
 *   используется для идентификации отправителя в envelope.
 *   SecureAuthManager.encrypt/decrypt с secure=false — работает,
 *   но только для «своих» данных (например, локальный кэш),
 *   не для cross-device сообщений.
 */
public final class MessageCipher {

    private static final String TAG = "MessengerCipher";

    /** Флаг: включено ли E2E. В MVP = false. */
    private static final boolean E2E_ENABLED = false;

    private MessageCipher() {
    }

    /**
     * Шифрует payload.
     *
     * MVP: возвращает CryptoResult.disabled() (isSuccess() == true,
     * isDisabled() == true, blob == null).
     *
     * E2E (будущее): AES-GCM через SecureAuthManager + ECDH-ключ сессии.
     *
     * @param context   контекст (для SecureAuthManager)
     * @param plaintext JSON-payload (PayloadCodec.encode(...))
     * @return CryptoResult; при isSuccess() == true и isDisabled() == false
     *         — getBlob() содержит iv+ciphertext
     */
    public static CryptoResult encrypt(Context context, String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return CryptoResult.error("empty plaintext");
        }

        if (!E2E_ENABLED) {
            // MVP: шифрование выключено, payload пойдёт как plaintext
            return CryptoResult.disabled();
        }

        // === Будущее: E2E ===
        // try {
        //     SecureAuthManager auth = new SecureAuthManager(context);
        //     // В E2E здесь будет ECDH-ключ сессии, а не локальный Keystore.
        //     // Пока — заглушка, которая НЕ будет работать для cross-device.
        //     String b64 = auth.encrypt(plaintext, false);
        //     if (b64 == null) return CryptoResult.error("encrypt returned null");
        //     byte[] blob = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP);
        //     return CryptoResult.ok(blob);
        // } catch (Exception e) {
        //     return CryptoResult.error(e.getMessage());
        // }

        return CryptoResult.disabled();
    }

    /**
     * Расшифровывает payload.
     *
     * MVP: возвращает CryptoResult.disabled() (isSuccess() == true,
     * isDisabled() == true, plaintext == null).
     *
     * E2E (будущее): AES-GCM decrypt через SecureAuthManager + ECDH-ключ.
     *
     * @param context контекст
     * @param blob    iv+ciphertext (или null в MVP — тогда decrypt не нужен)
     * @return CryptoResult; при isSuccess() == true и isDisabled() == false
     *         — getPlaintext() содержит JSON-payload
     */
    public static CryptoResult decrypt(Context context, @Nullable byte[] blob) {
        if (blob == null || blob.length == 0) {
            // MVP: encryptedBody == null → расшифровывать нечего
            return CryptoResult.disabled();
        }

        if (!E2E_ENABLED) {
            return CryptoResult.disabled();
        }

        // === Будущее: E2E ===
        // try {
        //     SecureAuthManager auth = new SecureAuthManager(context);
        //     String b64 = android.util.Base64.encodeToString(blob, android.util.Base64.NO_WRAP);
        //     String plaintext = auth.decrypt(b64, false);
        //     if (plaintext == null) return CryptoResult.error("decrypt returned null");
        //     return CryptoResult.ok(plaintext);
        // } catch (Exception e) {
        //     return CryptoResult.error(e.getMessage());
        // }

        return CryptoResult.disabled();
    }

    /**
     * Включено ли E2E. Для диагностики и UI (например, показать «🔒 E2E»
     * в ChatActivity, когда фича включится).
     */
    public static boolean isE2eEnabled() {
        return E2E_ENABLED;
    }

    /**
     * Быстрый хелпер: закодировать payload в JSON и (если E2E включён)
     * зашифровать. Возвращает пару (plaintextBody, encryptedBlob):
     *  - MVP: plaintextBody = JSON, encryptedBlob = null
     *  - E2E: plaintextBody = null, encryptedBlob = iv+ciphertext
     *
     * Если encode вернул null — возвращает null (ошибка).
     *
     * Используется в MessageCommands (Шаг 4) и MessageReceiver (Шаг 6),
     * чтобы не дублировать логику «encode + encrypt».
     */
    @Nullable
    public static EncodedPayload encodeAndEncrypt(Context context, MessagePayload payload) {
        if (payload == null) return null;

        String json = PayloadCodec.encode(payload);
        if (json == null) return null;

        CryptoResult r = encrypt(context, json);
        if (!r.isSuccess()) {
            MealseLogger.get(context, TAG)
                .warn("encodeAndEncrypt: encrypt failed: " + r.getErrorMessage());
            return null;
        }

        if (r.isDisabled()) {
            // MVP: payload как plaintext
            return new EncodedPayload(json, null);
        }

        // E2E: payload зашифрован
        return new EncodedPayload(null, r.getBlob());
    }

    /**
     * Обратная операция: из (plaintextBody, encryptedBlob) получить payload.
     *  - MVP: encryptedBlob == null → decode(plaintextBody)
     *  - E2E: encryptedBlob != null → decrypt → decode
     *
     * Если оба null — возвращает null.
     */
    @Nullable
    public static MessagePayload decodeAndDecrypt(Context context,
                                                  @Nullable String plaintextBody,
                                                  @Nullable byte[] encryptedBlob) {
        // MVP-путь: plaintext
        if (encryptedBlob == null || encryptedBlob.length == 0) {
            if (plaintextBody == null || plaintextBody.isEmpty()) {
                return null;
            }
            return PayloadCodec.decode(plaintextBody);
        }

        // E2E-путь: decrypt
        CryptoResult r = decrypt(context, encryptedBlob);
        if (!r.isSuccess() || r.getPlaintext() == null) {
            return null;
        }
        return PayloadCodec.decode(r.getPlaintext());
    }

    /**
     * Пара (plaintextBody, encryptedBlob) — результат encodeAndEncrypt.
     * Ровно одно из полей non-null:
     *  - MVP: plaintextBody != null, encryptedBlob == null
     *  - E2E: plaintextBody == null, encryptedBlob != null
     */
    public static final class EncodedPayload {
        @Nullable
        public final String plaintextBody;
        @Nullable
        public final byte[] encryptedBlob;

        public EncodedPayload(@Nullable String plaintextBody, @Nullable byte[] encryptedBlob) {
            this.plaintextBody = plaintextBody;
            this.encryptedBlob = encryptedBlob;
        }

        public boolean isPlaintext() {
            return plaintextBody != null;
        }

        public boolean isEncrypted() {
            return encryptedBlob != null && encryptedBlob.length > 0;
        }

        @Override
        public String toString() {
            return "EncodedPayload{plaintext=" + (plaintextBody != null ? plaintextBody.length() + " chars" : "null")
                + ", encrypted=" + (encryptedBlob != null ? encryptedBlob.length + "B" : "null") + '}';
        }
    }
}