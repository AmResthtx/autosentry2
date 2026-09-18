package com.autosentry.app.receiver;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.autosentry.app.service.TrackingService;
import com.autosentry.app.settings.AppSettings;

/**
 * The "effortless" half of tracking: this is a manifest-registered receiver,
 * so Android delivers it Bluetooth ACL connect/disconnect broadcasts even
 * if the app isn't running. Walk up to the truck with the phone in your
 * pocket, the ELM327 auto-connects (it's already paired), Android fires
 * ACL_CONNECTED, and this starts TrackingService. Walk away / shut the
 * truck off, ACL_DISCONNECTED fires and the trip is closed out.
 *
 * Only reacts to the specific adapter address saved via AppSettings (set
 * once through the "Pair OBD Adapter" flow) — ignores every other Bluetooth
 * device connect/disconnect (headphones, etc).
 */
public class BluetoothConnectionReceiver extends BroadcastReceiver {
    private static final String TAG = "BtConnectionReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        if (!action.equals(BluetoothDevice.ACTION_ACL_CONNECTED)
                && !action.equals(BluetoothDevice.ACTION_ACL_DISCONNECTED)) {
            return;
        }

        if (!AppSettings.isAutoTrackingEnabled(context)) return;

        String savedAddress = AppSettings.getObdAdapterAddress(context);
        if (savedAddress == null) return; // no adapter paired yet, nothing to auto-react to

        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (device == null || device.getAddress() == null) return;
        if (!device.getAddress().equalsIgnoreCase(savedAddress)) return; // some other BT device

        if (action.equals(BluetoothDevice.ACTION_ACL_CONNECTED)) {
            Log.i(TAG, "OBD adapter connected, auto-starting tracking");
            Intent serviceIntent = new Intent(context, TrackingService.class);
            serviceIntent.putExtra(TrackingService.EXTRA_ADAPTER_ADDRESS, savedAddress);
            context.startForegroundService(serviceIntent);
        } else {
            Log.i(TAG, "OBD adapter disconnected, auto-stopping tracking");
            context.stopService(new Intent(context, TrackingService.class));
        }
    }
}
