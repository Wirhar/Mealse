package com.my.mealse.messenger.transport;

import androidx.annotation.NonNull;

/**
 * Интерфейс отправки одного envelope через конкретный транспорт.
 *
 * Реализации:
 *   HttpMessageSender        — HTTP POST /signal/message через ConnectionManager
 *   WifiDirectMessageSender  — WifiDirectManager.sendSignal(json)
 *
 * MessageTransport выбирает реализацию по target.mode:
 *   WIFI_DIRECT → WifiDirectMessageSender
 *   остальное   → HttpMessageSender
 *
 * Callback вызывается в фоновом потоке (не UI!).
 * MessageTransport/DeliveryScheduler сами переключаются на UI при необходимости.
 */
public interface MessageSender {

    /**
     * Отправить envelope на targetNodeKey ("ip:port").
     *
     * @param targetNodeKey "ip:port" получателя
     * @param envelopeJson  envelope.toJson()
     * @param callback      результат (в фоновом потоке)
     */
    void send(@NonNull String targetNodeKey,
              @NonNull String envelopeJson,
              @NonNull Callback callback);

    interface Callback {
        void onResult(@NonNull SendResult result);
    }
}