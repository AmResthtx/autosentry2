package com.autosentry.app.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.autosentry.app.service.TrackingService;

/** Restarts tracking after the tablet reboots or the app is updated, without opening the app. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        TrackingService.startIfEnabled(context, String.valueOf(intent.getAction()));
    }
}
