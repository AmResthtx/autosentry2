package com.autosentry.app.maintenance;

import com.autosentry.app.data.MaintenanceDao;
import com.autosentry.app.data.MaintenanceEvent;
import com.autosentry.app.data.VehicleProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes %-of-service-life used for every tracked item against the
 * intervals in ServiceInterval — by miles AND by months, whichever is worse.
 *
 * Items with no logged service count from when AutoSentry started tracking:
 * months from that date, and only the miles AutoSentry itself recorded on
 * trips since then. The odometer can't be the baseline, because the owner
 * sets it to the truck's real reading at setup, and a high-mile truck would
 * be flooded with "8,000% overdue" on day one. The row says "not logged yet"
 * so the owner knows to record the last time it was really done.
 */
public final class MaintenanceScheduleEngine {
    public static final double DUE_SOON_THRESHOLD_PERCENT = 80.0;
    private static final double MS_PER_MONTH = 30.4375 * 24 * 60 * 60 * 1000;

    private MaintenanceScheduleEngine() {}

    /**
     * @param trackingStartMillis   when AutoSentry started watching the schedule (AppSettings)
     * @param milesTrackedSinceStart trip miles AutoSentry recorded since then (SessionDao.milesSince)
     */
    public static ServiceStatus computeStatus(ServiceItemType type, VehicleProfile profile, MaintenanceDao dao,
                                              long trackingStartMillis, double milesTrackedSinceStart) {
        ServiceInterval interval = ServiceInterval.of(type);
        MaintenanceEvent lastEvent = dao.getMostRecentOfType(type.name());

        long baselineTime = lastEvent != null ? lastEvent.timestamp : trackingStartMillis;
        double milesSince = lastEvent != null
                ? Math.max(0, profile.odometerMiles - lastEvent.odometerAtEvent)
                : Math.max(0, milesTrackedSinceStart);
        double monthsSince = Math.max(0, (System.currentTimeMillis() - baselineTime) / MS_PER_MONTH);

        return new ServiceStatus(interval, milesSince, interval.miles(profile.severeDuty),
                monthsSince, interval.months(profile.severeDuty), lastEvent != null);
    }

    public static List<ServiceStatus> computeAll(VehicleProfile profile, MaintenanceDao dao,
                                                 long trackingStartMillis, double milesTrackedSinceStart) {
        List<ServiceStatus> statuses = new ArrayList<>();
        for (ServiceItemType type : ServiceInterval.allTypes()) {
            statuses.add(computeStatus(type, profile, dao, trackingStartMillis, milesTrackedSinceStart));
        }
        return statuses;
    }
}
