package com.autosentry.app.settings;

import android.content.Context;
import android.content.SharedPreferences;

import com.autosentry.app.obd.PidCatalog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Persistent user and vehicle settings. Live vehicle data must come from a real adapter. */
public final class AppSettings {
    private static final String PREFS_NAME = "autosentry_settings";
    private static final String KEY_ADAPTER_ADDRESS = "obd_adapter_address";
    private static final String KEY_ADAPTER_NAME = "obd_adapter_name";
    private static final String KEY_AUTO_TRACKING_ENABLED = "auto_tracking_enabled";
    private static final String KEY_DASHBOARD_PIDS = "dashboard_pids";
    private static final String KEY_SUPPORTED_PIDS = "supported_pids";
    // Set once Trip Time has been put on a saved dashboard (or the user saved their own).
    private static final String KEY_TRIP_TIME_ADDED = "trip_time_added";
    // Standard coolant-temp PID, replaced on the dashboard by engine oil temp.
    private static final int REMOVED_COOLANT_PID = 0x05;

    private AppSettings() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static void setObdAdapter(Context context, String address, String name) {
        prefs(context).edit().putString(KEY_ADAPTER_ADDRESS, address).putString(KEY_ADAPTER_NAME, name).apply();
    }

    public static String getObdAdapterAddress(Context context) {
        return prefs(context).getString(KEY_ADAPTER_ADDRESS, null);
    }

    public static String getObdAdapterName(Context context) {
        return prefs(context).getString(KEY_ADAPTER_NAME, null);
    }

    public static boolean hasObdAdapterConfigured(Context context) {
        String address = getObdAdapterAddress(context);
        return address != null && !address.trim().isEmpty();
    }

    public static void setAutoTrackingEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_AUTO_TRACKING_ENABLED, enabled).apply();
    }

    public static boolean isAutoTrackingEnabled(Context context) {
        return prefs(context).getBoolean(KEY_AUTO_TRACKING_ENABLED, true);
    }

    public static List<Integer> getDashboardPids(Context context) {
        String stored = prefs(context).getString(KEY_DASHBOARD_PIDS, null);
        if (stored == null) {
            List<Integer> defaults = new ArrayList<>();
            defaults.add(PidCatalog.RPM);
            defaults.add(PidCatalog.SPEED);
            defaults.add(PidCatalog.ENGINE_OIL_TEMP);
            defaults.add(PidCatalog.COMPUTED_INSTANT_MPG);
            defaults.add(PidCatalog.COMPUTED_TRIP_MPG);
            defaults.add(PidCatalog.COMPUTED_TRIP_MILES);
            defaults.add(PidCatalog.COMPUTED_TRIP_TIME);
            return defaults;
        }
        List<Integer> ids = parseIds(stored);
        int coolant = ids.indexOf(REMOVED_COOLANT_PID);
        if (coolant >= 0) {
            if (ids.contains(PidCatalog.ENGINE_OIL_TEMP)) {
                ids.remove(coolant);
            } else {
                ids.set(coolant, PidCatalog.ENGINE_OIL_TEMP);
            }
        }
        if (!prefs(context).getBoolean(KEY_TRIP_TIME_ADDED, false)) {
            if (!ids.contains(PidCatalog.COMPUTED_TRIP_TIME)) ids.add(PidCatalog.COMPUTED_TRIP_TIME);
            setDashboardPids(context, ids);
        }
        return ids;
    }

    public static void setDashboardPids(Context context, List<Integer> pids) {
        prefs(context).edit().putString(KEY_DASHBOARD_PIDS, joinIds(pids))
                .putBoolean(KEY_TRIP_TIME_ADDED, true).apply();
    }

    public static Set<Integer> getSupportedPids(Context context) {
        return new HashSet<>(parseIds(prefs(context).getString(KEY_SUPPORTED_PIDS, "")));
    }

    public static void setSupportedPids(Context context, Set<Integer> pids) {
        prefs(context).edit().putString(KEY_SUPPORTED_PIDS, joinIds(new ArrayList<>(pids))).apply();
    }

    private static List<Integer> parseIds(String stored) {
        List<Integer> ids = new ArrayList<>();
        if (stored == null || stored.isEmpty()) return ids;
        for (String part : stored.split(",")) {
            try { ids.add(Integer.parseInt(part.trim())); }
            catch (NumberFormatException ignored) { }
        }
        return ids;
    }

    private static String joinIds(List<Integer> ids) {
        StringBuilder sb = new StringBuilder();
        for (int id : ids) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        return sb.toString();
    }
}
