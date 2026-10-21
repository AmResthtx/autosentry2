package com.autosentry.app.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.autosentry.app.service.TrackingService;

/** Restarts tracking after the tablet reboots or the app is updated, without opening the app. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return; // ignore anything that isn't a system boot/update broadcast
        }
        TrackingService.startIfEnabled(context, action);
    }
}
