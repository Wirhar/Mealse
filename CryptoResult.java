package com.my.mealse.messenger.crypto;

import androidx.annotation.Nullable;

/**
 * Результат крипто-операции.
 *
 * Удобно, чтобы не бросать исключения из MessageCipher в транспорт/репозитории:
 *   CryptoResult r = MessageCipher.encrypt(ctx, text);
 *   if (!r.isSuccess()) { log(r.getErrorMessage()); ... }
 *
 * Для MVP (шифрование отключено):
 *   encrypt(...) → CryptoResult.disabled() с payload = null,
 *                  но isSuccess() == true (это не ошибка, а «фича выключена»).
 *   decrypt(...) → CryptoResult.disabled() с plaintext = null.
 *
 * Для будущего E2E:
 *   encrypt(...) → CryptoResult.ok(blob)
 *   decrypt(...) → CryptoResult.ok(plaintext)
 *   при ошибке   → CryptoResult.error("bad tag")
 */
public final class CryptoResult {

    private final boolean success;
    private final boolean disabled;
    private final byte[] blob;          // для encrypt
    private final String plaintext;     // для decrypt
    private final String errorMessage;

    private CryptoResult(boolean success, boolean disabled, byte[] blob, String plaintext,
                         String errorMessage) {
        this.success = success;
        this.disabled = disabled;
        this.blob = blob;
        this.plaintext = plaintext;
        this.errorMessage = errorMessage;
    }

    public static CryptoResult ok(byte[] blob) {
        return new CryptoResult(true, false, blob, null, null);
    }

    public static CryptoResult ok(String plaintext) {
        return new CryptoResult(true, false, null, plaintext, null);
    }

    public static CryptoResult error(String message) {
        return new CryptoResult(false, false, null, null, message);
    }

    /**
     * Шифрование/дешифрование выключено (MVP).
     * Это НЕ ошибка — просто фича ещё не включена.
     * Для encrypt: blob == null, для decrypt: plaintext == null.
     */
    public static CryptoResult disabled() {
        return new CryptoResult(true, true, null, null, null);
    }

    public boolean isSuccess() {
        return success;
    }

    public boolean isDisabled() {
        return disabled;
    }

    @Nullable
    public byte[] getBlob() {
        return blob;
    }

    @Nullable
    public String getPlaintext() {
        return plaintext;
    }

    @Nullable
    public String getErrorMessage() {
        return errorMessage;
    }

    @Override
    public String toString() {
        return "CryptoResult{success=" + success + ", disabled=" + disabled
            + ", blob=" + (blob != null ? blob.length + "B" : "null")
            + ", plaintext=" + (plaintext != null ? plaintext.length() + " chars" : "null")
            + ", error='" + errorMessage + "'}";
    }
}