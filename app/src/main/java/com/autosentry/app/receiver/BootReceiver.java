package com.autosentry.app.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.autosentry.app.BuildConfig;
import com.autosentry.app.service.TrackingService;
import com.autosentry.app.util.AppLog;

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
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            // The update killed the old process, tracking service and watchdog included. The
            // watchdog is re-armed by App.onCreate, which ran to deliver this broadcast.
            AppLog.i(context, "BootReceiver", "App updated to build " + BuildConfig.VERSION_CODE + "; restarting tracking");
        }
        TrackingService.startIfEnabled(context, action);
    }
}
