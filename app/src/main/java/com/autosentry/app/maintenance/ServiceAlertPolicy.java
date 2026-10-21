package com.autosentry.app.maintenance;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;

import com.autosentry.app.data.MaintenanceDao;
import com.autosentry.app.data.SessionDao;
import com.autosentry.app.data.VehicleProfile;
import com.autosentry.app.notifications.NotificationUtils;
import com.autosentry.app.settings.AppSettings;
import com.autosentry.app.ui.MainActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides when a service item earns a notification. The goal is "proactive,
 * never annoying":
 *
 *  - Runs when a trip ends (truck parked), never mid-drive.
 *  - SAFETY items alert at 80%, STANDARD items only once overdue, QUIET items
 *    never — they just show on the History tab.
 *  - Each item alerts once per stage (due soon → overdue). An overdue item
 *    gets at most one reminder every 14 days after that.
 *  - Everything due is bundled into ONE notification, with a "do together"
 *    hint for related jobs that are close anyway.
 *  - Items the owner hid ("doesn't apply to my truck") never alert.
 */
public final class ServiceAlertPolicy {
    public static final int NOTIFICATION_ID = 2000;
    private static final long OVERDUE_REMINDER_MS = 14L * 24 * 60 * 60 * 1000;
    private static final double BUNDLE_HINT_PERCENT = 50.0;

    private ServiceAlertPolicy() {}

    public static void checkAndNotify(Context context, VehicleProfile profile, MaintenanceDao dao, SessionDao sessions) {
        if (profile == null) return;
        long now = System.currentTimeMillis();
        long trackingStart = AppSettings.getMaintenanceTrackingStart(context);
        Set<String> hidden = AppSettings.getHiddenServiceItems(context);

        List<ServiceStatus> all = MaintenanceScheduleEngine.computeAll(profile, dao, trackingStart,
                sessions.milesSince(trackingStart));
        List<ServiceStatus> toAlert = new ArrayList<>();
        for (ServiceStatus status : all) {
            String key = status.type.name();
            if (hidden.contains(key)) continue;

            int stage = alertStage(status);
            int sentStage = AppSettings.getServiceAlertStage(context, key);
            if (stage == 0) {
                if (sentStage != 0) AppSettings.setServiceAlertState(context, key, 0, 0); // serviced — rearm
                continue;
            }
            boolean escalated = stage > sentStage;
            boolean reminderDue = stage == 2 && now - AppSettings.getServiceAlertTime(context, key) >= OVERDUE_REMINDER_MS;
            if (escalated || reminderDue) {
                toAlert.add(status);
                AppSettings.setServiceAlertState(context, key, stage, now);
            }
        }
        if (toAlert.isEmpty()) return;
        post(context, toAlert, all, hidden);
    }

    /** 0 = nothing to say, 1 = due soon (SAFETY items only), 2 = overdue. */
    private static int alertStage(ServiceStatus status) {
        switch (status.interval.alertLevel) {
            case SAFETY:
                return status.overdue ? 2 : (status.dueSoon ? 1 : 0);
            case STANDARD:
                return status.overdue ? 2 : 0;
            default:
                return 0;
        }
    }

    private static void post(Context context, List<ServiceStatus> alerts, List<ServiceStatus> all, Set<String> hidden) {
        boolean anyOverdue = false;
        for (ServiceStatus s : alerts) anyOverdue |= s.overdue;

        String title = alerts.size() == 1
                ? (anyOverdue ? "Overdue: " : "Coming up: ") + alerts.get(0).displayName
                : alerts.size() + " services need attention";

        NotificationCompat.InboxStyle style = new NotificationCompat.InboxStyle();
        for (ServiceStatus s : alerts) {
            style.addLine(String.format(Locale.US, "%s — %s", s.displayName, s.remainingText()));
        }
        String hint = bundleHint(alerts, all, hidden);
        if (hint != null) style.setSummaryText(hint);

        String text = alerts.size() == 1 ? alerts.get(0).remainingText() : "Tap to see the service list";
        if (hint != null && alerts.size() == 1) text += " · " + hint;

        Intent open = new Intent(context, MainActivity.class)
                .putExtra(MainActivity.EXTRA_OPEN_SERVICE_TAB, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(context, NOTIFICATION_ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, NotificationUtils.CHANNEL_ALERTS)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(style)
                .setContentIntent(pending)
                .setPriority(anyOverdue ? NotificationCompat.PRIORITY_HIGH : NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);

        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, builder.build());
    }

    /** "Do together: Fuel filter (85%)" — related jobs already past half their life. */
    private static String bundleHint(List<ServiceStatus> alerts, List<ServiceStatus> all, Set<String> hidden) {
        List<String> names = new ArrayList<>();
        for (ServiceStatus alert : alerts) {
            for (ServiceItemType related : alert.interval.whileInThere) {
                if (hidden.contains(related.name())) continue;
                ServiceStatus rs = find(all, related);
                if (rs == null || rs.percentOfLifeUsed < BUNDLE_HINT_PERCENT || contains(alerts, related)) continue;
                String label = String.format(Locale.US, "%s (%.0f%%)", rs.displayName, rs.percentOfLifeUsed);
                if (!names.contains(label)) names.add(label);
            }
        }
        if (names.isEmpty()) return null;
        return "Do together: " + android.text.TextUtils.join(", ", names);
    }

    private static ServiceStatus find(List<ServiceStatus> list, ServiceItemType type) {
        for (ServiceStatus s : list) if (s.type == type) return s;
        return null;
    }

    private static boolean contains(List<ServiceStatus> list, ServiceItemType type) {
        return find(list, type) != null;
    }
}
