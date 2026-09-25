package com.autosentry.app.maintenance;

public class ServiceStatus {
    public final ServiceItemType type;
    public final String displayName;
    public final double milesSinceService;
    public final int intervalMiles;
    public final double percentOfLifeUsed; // 0-100+, can exceed 100 if overdue
    public final boolean dueSoon;   // >= 80%
    public final boolean overdue;   // >= 100%
    public final boolean hasRecord; // false = never logged, so wear is unknown rather than 0%

    public ServiceStatus(ServiceItemType type, String displayName, double milesSinceService,
                          int intervalMiles, double percentOfLifeUsed, boolean hasRecord) {
        this.type = type;
        this.displayName = displayName;
        this.milesSinceService = milesSinceService;
        this.intervalMiles = intervalMiles;
        this.percentOfLifeUsed = percentOfLifeUsed;
        this.hasRecord = hasRecord;
        this.dueSoon = hasRecord && percentOfLifeUsed >= 80.0;
        this.overdue = hasRecord && percentOfLifeUsed >= 100.0;
    }

    public double milesRemaining() {
        return Math.max(0, intervalMiles - milesSinceService);
    }
}
