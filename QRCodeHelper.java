package com.my.mealse.util;

import android.graphics.Bitmap;
import android.graphics.Color;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.my.mealse.model.TrustedNode;
import java.util.HashMap;
import java.util.Map;

public class QRCodeHelper {

    public static Bitmap generateQR(String content, int size) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 1);

            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix result = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints);

            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    bitmap.setPixel(x, y, result.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return bitmap;
        } catch (WriterException e) {
            e.printStackTrace();
            return null;
        }
    }

    // Формат: mealse://node?id=...&ip=...&port=...&name=...
    public static String formatNodeData(TrustedNode node) {
        return String.format("mealse://node?id=%s&ip=%s&port=%d&name=%s&device=%s",
                node.getId(),
                node.getIpAddress(),
                node.getPort(),
                node.getName().replace("&", "%26"),
                node.getDeviceId());
    }

    public static TrustedNode parseNodeData(String data) {
        if (!data.startsWith("mealse://node?")) return null;

        // Простая разборка строки (в реальном проекте — использовать Uri.parse)
        String[] pairs = data.substring(14).split("&");
        String id = null, ip = null, name = null, deviceId = null;
        int port = 8081;

        for (String pair : pairs) {
            if (pair.startsWith("id=")) id = pair.substring(3);
            else if (pair.startsWith("ip=")) ip = pair.substring(3);
            else if (pair.startsWith("port=")) port = Integer.parseInt(pair.substring(5));
            else if (pair.startsWith("name=")) name = pair.substring(5).replace("%26", "&");
            else if (pair.startsWith("device=")) deviceId = pair.substring(7);
        }

        if (id == null || ip == null || name == null || deviceId == null) return null;

        return new TrustedNode(id, name, ip, port, deviceId);
    }
}
