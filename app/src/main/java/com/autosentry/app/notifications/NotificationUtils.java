package com.autosentry.app.notifications;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import androidx.core.app.NotificationCompat;

public final class NotificationUtils {
    public static final String CHANNEL_TRACKING = "tracking";
    public static final String CHANNEL_WAITING = "waiting";
    public static final String CHANNEL_ALERTS = "service_alerts";
    public static final int TRACKING_NOTIFICATION_ID = 1001;

    private NotificationUtils() {}

    public static void ensureChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_TRACKING, "Trip Tracking", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Live OBD + GPS tracking while a trip is active");
        manager.createNotificationChannel(channel);

        // Truck off: the service must stay up to notice key-on, but out of the way.
        NotificationChannel waiting = new NotificationChannel(
                CHANNEL_WAITING, "Waiting for Key-On", NotificationManager.IMPORTANCE_MIN);
        waiting.setDescription("Shown while the truck is off and AutoSentry checks for key-on");
        manager.createNotificationChannel(waiting);

        // Separate, louder channel: service warnings must actually get noticed,
        // unlike the always-on tracking notification which stays quiet.
        NotificationChannel alerts = new NotificationChannel(
                CHANNEL_ALERTS, "Service Alerts", NotificationManager.IMPORTANCE_HIGH);
        alerts.setDescription("Warns when a maintenance item is approaching its service interval");
        manager.createNotificationChannel(alerts);
    }

    public static NotificationCompat.Builder trackingNotificationBuilder(Context context, boolean tripActive) {
        return new NotificationCompat.Builder(context, tripActive ? CHANNEL_TRACKING : CHANNEL_WAITING)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                // Show right away instead of Android 12+'s default 10 s delay.
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentTitle(tripActive ? "AutoSentry tracking trip" : "AutoSentry: truck off");
    }
}
