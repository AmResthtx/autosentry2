package com.autosentry.app.maintenance;

import java.util.Locale;

public class ServiceStatus {
    public final ServiceItemType type;
    public final ServiceInterval interval;
    public final String displayName;
    public final double milesSinceService;
    public final int intervalMiles;   // 0 = not tracked by miles
    public final double monthsSinceService;
    public final int intervalMonths;  // 0 = not tracked by time
    public final double percentOfLifeUsed; // worse of miles/time; 0-100+, >100 if overdue
    public final boolean hasRecord;   // false = counting from when AutoSentry started tracking
    public final boolean dueSoon;   // >= 80%
    public final boolean overdue;   // >= 100%

    public ServiceStatus(ServiceInterval interval, double milesSinceService, int intervalMiles,
                         double monthsSinceService, int intervalMonths, boolean hasRecord) {
        this.type = interval.type;
        this.interval = interval;
        this.displayName = interval.displayName;
        this.milesSinceService = milesSinceService;
        this.intervalMiles = intervalMiles;
        this.monthsSinceService = monthsSinceService;
        this.intervalMonths = intervalMonths;
        this.hasRecord = hasRecord;
        double byMiles = intervalMiles > 0 ? milesSinceService / intervalMiles * 100.0 : 0;
        double byTime = intervalMonths > 0 ? monthsSinceService / intervalMonths * 100.0 : 0;
        this.percentOfLifeUsed = Math.max(byMiles, byTime);
        this.dueSoon = percentOfLifeUsed >= MaintenanceScheduleEngine.DUE_SOON_THRESHOLD_PERCENT;
        this.overdue = percentOfLifeUsed >= 100.0;
    }

    public double milesRemaining() {
        return Math.max(0, intervalMiles - milesSinceService);
    }

    private boolean timeIsLimiting() {
        if (intervalMonths <= 0) return false;
        if (intervalMiles <= 0) return true;
        return monthsSinceService / intervalMonths >= milesSinceService / intervalMiles;
    }

    /** "1,250 mi left", "4 mo left", "600 mi over", etc. — whichever limit is closer. */
    public String remainingText() {
        if (timeIsLimiting()) {
            double monthsLeft = intervalMonths - monthsSinceService;
            if (monthsLeft < 0) return String.format(Locale.US, "%s over", formatMonths(-monthsLeft));
            return String.format(Locale.US, "%s left", formatMonths(monthsLeft));
        }
        if (intervalMiles <= 0) return "";
        double left = intervalMiles - milesSinceService;
        if (left < 0) return String.format(Locale.US, "%,.0f mi over", -left);
        return String.format(Locale.US, "%,.0f mi left", left);
    }

    /** Full interval as a sentence fragment, e.g. "every 3,000 mi or 3 mo". */
    public String intervalText() {
        StringBuilder sb = new StringBuilder("every ");
        if (intervalMiles > 0) sb.append(String.format(Locale.US, "%,d mi", intervalMiles));
        if (intervalMiles > 0 && intervalMonths > 0) sb.append(" or ");
        if (intervalMonths > 0) sb.append(formatMonths(intervalMonths));
        return sb.toString();
    }

    private static String formatMonths(double months) {
        if (months >= 24) return String.format(Locale.US, "%.0f yr", months / 12.0);
        if (months < 1) return String.format(Locale.US, "%.0f days", Math.max(0, months * 30.4));
        return String.format(Locale.US, "%.0f mo", months);
    }
}
