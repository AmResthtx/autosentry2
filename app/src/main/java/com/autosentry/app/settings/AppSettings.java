package com.autosentry.app.settings;

import android.content.Context;
import android.content.SharedPreferences;

import com.autosentry.app.obd.DiagnosticCodes;
import com.autosentry.app.obd.PidCatalog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private static final String KEY_SIMULATOR_MODE_ENABLED = "simulator_mode_enabled";
    private static final String KEY_DASHBOARD_PIDS = "dashboard_pids";
    private static final String KEY_SUPPORTED_PIDS = "supported_pids";
    private static final String KEY_TRACKING_START = "maintenance_tracking_start";
    private static final String KEY_HIDDEN_SERVICES = "hidden_service_items";
    private static final String KEY_LAST_CODE_SCAN = "last_code_scan";
    private static final String KEY_ALERT_STAGE_PREFIX = "service_alert_stage_";
    private static final String KEY_ALERT_TIME_PREFIX = "service_alert_time_";

    private AppSettings() {}

    /**
     * When AutoSentry started watching the maintenance schedule. Items with no
     * logged service count their months from here (their miles already count
     * from 0, since the app odometer starts at install).
     */
    public static long getMaintenanceTrackingStart(Context context) {
        SharedPreferences p = prefs(context);
        long start = p.getLong(KEY_TRACKING_START, 0);
        if (start == 0) {
            start = System.currentTimeMillis();
            p.edit().putLong(KEY_TRACKING_START, start).apply();
        }
        return start;
    }

    /** Service items the owner long-pressed away ("doesn't apply to my truck"). Never shown or alerted. */
    public static Set<String> getHiddenServiceItems(Context context) {
        String stored = prefs(context).getString(KEY_HIDDEN_SERVICES, "");
        Set<String> hidden = new HashSet<>();
        for (String part : stored.split(",")) {
            if (!part.trim().isEmpty()) hidden.add(part.trim());
        }
        return hidden;
    }

    public static void setServiceItemHidden(Context context, String type, boolean hidden) {
        Set<String> current = getHiddenServiceItems(context);
        if (hidden) current.add(type); else current.remove(type);
        prefs(context).edit().putString(KEY_HIDDEN_SERVICES, android.text.TextUtils.join(",", current)).apply();
    }

    public static void clearHiddenServiceItems(Context context) {
        prefs(context).edit().remove(KEY_HIDDEN_SERVICES).apply();
    }

    /** Last alert stage sent for an item (0 none, 1 due soon, 2 overdue) — survives app restarts so trips don't re-nag. */
    public static int getServiceAlertStage(Context context, String type) {
        return prefs(context).getInt(KEY_ALERT_STAGE_PREFIX + type, 0);
    }

    public static long getServiceAlertTime(Context context, String type) {
        return prefs(context).getLong(KEY_ALERT_TIME_PREFIX + type, 0);
    }

    public static void setServiceAlertState(Context context, String type, int stage, long time) {
        prefs(context).edit()
                .putInt(KEY_ALERT_STAGE_PREFIX + type, stage)
                .putLong(KEY_ALERT_TIME_PREFIX + type, time)
                .apply();
    }

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

    public static void setSimulatorModeEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_SIMULATOR_MODE_ENABLED, enabled).apply();
    }

    public static boolean isSimulatorModeEnabled(Context context) {
        return prefs(context).getBoolean(KEY_SIMULATOR_MODE_ENABLED, false);
    }

    /** Which readings the dashboard shows, in display order. Set from the "Edit Dashboard" screen. */
    public static List<Integer> getDashboardPids(Context context) {
        String stored = prefs(context).getString(KEY_DASHBOARD_PIDS, null);
        if (stored == null) {
            List<Integer> defaults = new ArrayList<>();
            defaults.add(PidCatalog.RPM);
            defaults.add(PidCatalog.SPEED);
            defaults.add(PidCatalog.COOLANT);
            defaults.add(PidCatalog.COMPUTED_INSTANT_MPG);
            defaults.add(PidCatalog.COMPUTED_TRIP_MPG);
            defaults.add(PidCatalog.COMPUTED_TRIP_MILES);
            return defaults;
        }
        return parseIds(stored);
    }

    public static void setDashboardPids(Context context, List<Integer> pids) {
        prefs(context).edit().putString(KEY_DASHBOARD_PIDS, joinIds(pids)).apply();
    }

    /** PIDs the truck said it answers on the last successful scan; empty if never scanned. */
    public static Set<Integer> getSupportedPids(Context context) {
        String stored = prefs(context).getString(KEY_SUPPORTED_PIDS, "");
        return new HashSet<>(parseIds(stored));
    }

    public static void setSupportedPids(Context context, Set<Integer> pids) {
        prefs(context).edit().putString(KEY_SUPPORTED_PIDS, joinIds(new ArrayList<>(pids))).apply();
    }

    /** The last trouble-code scan, so the Codes tab has something to show with the truck off. */
    public static DiagnosticCodes.Scan getLastCodeScan(Context context) {
        return DiagnosticCodes.Scan.deserialize(prefs(context).getString(KEY_LAST_CODE_SCAN, null));
    }

    public static void setLastCodeScan(Context context, DiagnosticCodes.Scan scan) {
        prefs(context).edit().putString(KEY_LAST_CODE_SCAN, scan.serialize()).apply();
    }

    private static List<Integer> parseIds(String stored) {
        List<Integer> ids = new ArrayList<>();
        if (stored == null || stored.isEmpty()) return ids;
        for (String part : stored.split(",")) {
            try {
                ids.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
            }
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
