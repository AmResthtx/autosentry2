package com.autosentry.app.ui;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public final class PermissionFlow {
    private static final int PERMISSION_REQ = 1001;

    private PermissionFlow() {}

    private static String[] requiredPerms() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        perms.add(Manifest.permission.POST_NOTIFICATIONS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
            perms.add(Manifest.permission.BLUETOOTH_SCAN);
        } else {
            perms.add(Manifest.permission.BLUETOOTH);
            perms.add(Manifest.permission.BLUETOOTH_ADMIN);
        }
        return perms.toArray(new String[0]);
    }

    public static boolean hasAllPermissions(MainActivity activity) {
        for (String perm : requiredPerms()) {
            if (ContextCompat.checkSelfPermission(activity, perm) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    public static void requestAllPermissions(MainActivity activity) {
        List<String> missing = new ArrayList<>();
        for (String perm : requiredPerms()) {
            if (ContextCompat.checkSelfPermission(activity, perm) != PackageManager.PERMISSION_GRANTED) {
                missing.add(perm);
            }
        }
        if (!missing.isEmpty()) {
            ActivityCompat.requestPermissions(activity, missing.toArray(new String[0]), PERMISSION_REQ);
            // BLUETOOTH_CONNECT (needed below by adapter.isEnabled() on API 31+) was
            // just requested, not yet granted — permission results are asynchronous,
            // so checking Bluetooth state now would crash with a SecurityException
            // on a fresh install. Bail here; MainActivity re-checks once the app is
            // actually used (e.g. Pair OBD Adapter), by which point the permission
            // request has been answered.
            return;
        }

        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                Toast.makeText(activity, "Please pair your OBD adapter in Settings > Bluetooth", Toast.LENGTH_LONG).show();
            }
        } catch (SecurityException e) {
            // Defensive: some OEM builds enforce BLUETOOTH_CONNECT even in paths
            // Android's own docs don't require it for. Not fatal either way.
        }
    }
}
