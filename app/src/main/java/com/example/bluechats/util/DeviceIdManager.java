package com.example.bluechats.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

public class DeviceIdManager {
    private static final String PREFS_NAME = "bluechats_device_id";
    private static final String KEY_DEVICE_ID = "unique_device_id";
    private static final String KEY_DEVICE_NAME = "device_name";

    public static String getDeviceId(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String deviceId = prefs.getString(KEY_DEVICE_ID, null);

        if (deviceId == null) {
            deviceId = generateUniqueId();
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply();
        }

        return deviceId;
    }

    public static String getDeviceName(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String deviceName = prefs.getString(KEY_DEVICE_NAME, null);

        if (deviceName == null) {
            String deviceId = getDeviceId(context);
            deviceName = "User_" + deviceId.substring(0, 6);
            prefs.edit().putString(KEY_DEVICE_NAME, deviceName).apply();
        }

        return deviceName;
    }

    public static void setDeviceName(Context context, String name) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_DEVICE_NAME, name).apply();
    }

    private static String generateUniqueId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }

    public static String getShortId(String fullId) {
        return fullId.substring(0, Math.min(8, fullId.length()));
    }
}
