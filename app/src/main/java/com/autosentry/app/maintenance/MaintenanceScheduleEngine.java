package com.autosentry.app.maintenance;

import com.autosentry.app.data.MaintenanceDao;
import com.autosentry.app.data.MaintenanceEvent;
import com.autosentry.app.data.VehicleProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes %-of-service-life used for every tracked item, against the
 * manufacturer intervals in ServiceInterval. The 80% "dueSoon" threshold
 * is what TrackingService watches to fire the early-warning notification
 * the old app never had.
 */
public final class MaintenanceScheduleEngine {
    public static final double DUE_SOON_THRESHOLD_PERCENT = 80.0;

    private MaintenanceScheduleEngine() {}

    public static ServiceStatus computeStatus(ServiceItemType type, VehicleProfile profile, MaintenanceDao dao) {
        ServiceInterval interval = ServiceInterval.of(type);
        MaintenanceEvent lastEvent = dao.getMostRecentOfType(type.name());

        double baselineOdometer = lastEvent != null ? lastEvent.odometerAtEvent : 0;
        double milesSince = Math.max(0, profile.odometerMiles - baselineOdometer);

        int intervalMiles = interval.miles(profile.severeDuty);
        double percent = intervalMiles > 0 ? (milesSince / intervalMiles) * 100.0 : 0;

        return new ServiceStatus(type, interval.displayName, milesSince, intervalMiles, percent);
    }

    public static List<ServiceStatus> computeAll(VehicleProfile profile, MaintenanceDao dao) {
        List<ServiceStatus> statuses = new ArrayList<>();
        for (ServiceItemType type : ServiceInterval.allTypes()) {
            statuses.add(computeStatus(type, profile, dao));
        }
        return statuses;
    }

    public static List<ServiceStatus> dueSoonOrOverdue(VehicleProfile profile, MaintenanceDao dao) {
        List<ServiceStatus> result = new ArrayList<>();
        for (ServiceStatus status : computeAll(profile, dao)) {
            if (status.dueSoon) result.add(status);
        }
        return result;
    }
}
