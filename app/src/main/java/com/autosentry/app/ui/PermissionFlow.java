package com.autosentry.app.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Only Bluetooth is required (to talk to the adapter). Location (GPS speed
 * fallback) and notifications (service alerts) are requested but optional,
 * so denying them never blocks tracking.
 */
public final class PermissionFlow {
    private static final int PERMISSION_REQ = 1001;

    private PermissionFlow() {}

    private static List<String> requiredPerms() {
        List<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        return perms;
    }

    private static List<String> optionalPerms() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN);
        }
        return perms;
    }

    private static boolean granted(Context context, String perm) {
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean hasRequiredPermissions(Context context) {
        for (String perm : requiredPerms()) {
            if (!granted(context, perm)) return false;
        }
        return true;
    }

    /** Asks for every missing permission, required and optional. */
    public static void requestMissingPermissions(Activity activity) {
        List<String> missing = new ArrayList<>();
        List<String> all = requiredPerms();
        all.addAll(optionalPerms());
        for (String perm : all) {
            if (!granted(activity, perm)) missing.add(perm);
        }
        if (!missing.isEmpty()) {
            ActivityCompat.requestPermissions(activity, missing.toArray(new String[0]), PERMISSION_REQ);
        }
    }

    /** Android stops showing the dialog after repeated denials, so say where to fix it. */
    public static void explainBluetoothDenied(Activity activity) {
        Toast.makeText(activity, "Bluetooth (Nearby devices) permission is required to talk to the OBD adapter. "
                + "Allow it in Settings > Apps > AutoSentry > Permissions.", Toast.LENGTH_LONG).show();
    }
}
