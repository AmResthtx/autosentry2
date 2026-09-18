package com.autosentry.app.settings;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Stores the one thing auto-tracking needs to know: which paired Bluetooth
 * device is the OBD adapter. Set once via the "Pair OBD Adapter" flow, then
 * BluetoothConnectionReceiver uses it to recognize the adapter on every
 * future connect/disconnect without any user action.
 */
public final class AppSettings {
    private static final String PREFS_NAME = "autosentry_settings";
    private static final String KEY_ADAPTER_ADDRESS = "obd_adapter_address";
    private static final String KEY_ADAPTER_NAME = "obd_adapter_name";
    private static final String KEY_AUTO_TRACKING_ENABLED = "auto_tracking_enabled";

    private AppSettings() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static void setObdAdapter(Context context, String address, String name) {
        prefs(context).edit()
                .putString(KEY_ADAPTER_ADDRESS, address)
                .putString(KEY_ADAPTER_NAME, name)
                .apply();
    }

    public static String getObdAdapterAddress(Context context) {
        return prefs(context).getString(KEY_ADAPTER_ADDRESS, null);
    }

    public static String getObdAdapterName(Context context) {
        return prefs(context).getString(KEY_ADAPTER_NAME, null);
    }

    public static boolean hasObdAdapterConfigured(Context context) {
        return getObdAdapterAddress(context) != null;
    }

    public static void setAutoTrackingEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_AUTO_TRACKING_ENABLED, enabled).apply();
    }

    public static boolean isAutoTrackingEnabled(Context context) {
        return prefs(context).getBoolean(KEY_AUTO_TRACKING_ENABLED, true);
    }
}
