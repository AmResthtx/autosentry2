package com.autosentry.app.notifications;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import androidx.core.app.NotificationCompat;

public final class NotificationUtils {
    public static final String CHANNEL_TRACKING = "tracking";
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
    }

    public static NotificationCompat.Builder trackingNotificationBuilder(Context context) {
        return new NotificationCompat.Builder(context, CHANNEL_TRACKING)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentTitle("AutoSentry tracking trip");
    }
}
