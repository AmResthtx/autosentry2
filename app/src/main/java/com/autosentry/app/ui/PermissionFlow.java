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
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Toast.makeText(activity, "Please pair your OBD adapter in Settings > Bluetooth", Toast.LENGTH_LONG).show();
        }
    }
}
