package com.example.bluechats.util;

import android.graphics.Bitmap;
import android.graphics.Color;
import com.google.gson.Gson;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.HashMap;

public class QRUtils {
    public static Bitmap generateContactQr(String deviceId, String pubKeyBase64, String signPubBase64) throws Exception {
        HashMap<String, String> obj = new HashMap<>();
        obj.put("id", deviceId);
        obj.put("pk", pubKeyBase64);
        obj.put("sp", signPubBase64);
        String json = new Gson().toJson(obj);

        QRCodeWriter writer = new QRCodeWriter();
        BitMatrix bitMatrix = writer.encode(json, BarcodeFormat.QR_CODE, 512, 512);

        Bitmap bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565);
        for (int x = 0; x < 512; x++) {
            for (int y = 0; y < 512; y++) {
                bmp.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
            }
        }
        return bmp;
    }

    public static class ContactInfo {
        public String id;
        public String pk;
        public String sp;
    }

    public static ContactInfo parseQrContent(String qrContent) {
        try {
            return new Gson().fromJson(qrContent, ContactInfo.class);
        } catch (Exception e) {
            return null;
        }
    }
}
