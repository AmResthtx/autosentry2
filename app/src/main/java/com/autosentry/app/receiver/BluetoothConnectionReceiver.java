package com.autosentry.app.receiver;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.autosentry.app.service.TrackingService;
import com.autosentry.app.settings.AppSettings;

/**
 * Backup trigger only. An OBD adapter never opens the Bluetooth link on its
 * own — the tablet has to — so ACL_CONNECTED usually means TrackingService
 * (which runs all the time and keeps retrying) just connected. If something
 * else opened the link while tracking was down, this starts it.
 *
 * Disconnects are deliberately ignored: a dropped link mid-drive is retried
 * by the service, and the trip is closed by the service once the link stays
 * down. Stopping here used to end tracking for the rest of the drive.
 */
public class BluetoothConnectionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!BluetoothDevice.ACTION_ACL_CONNECTED.equals(intent.getAction())) return;

        String savedAddress = AppSettings.getObdAdapterAddress(context);
        if (savedAddress == null) return; // no adapter paired yet, nothing to auto-react to

        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (device == null || device.getAddress() == null) return;
        if (!device.getAddress().equalsIgnoreCase(savedAddress)) return; // some other BT device

        TrackingService.startIfEnabled(context, "adapter connected");
    }
}
