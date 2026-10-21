package com.autosentry.app.engine;

/**
 * Degrades oil life % as the vehicle accumulates miles. The old app
 * computed this once and never called it again during a trip — this class
 * exists so callers (TrackingService) can feed it small mileage deltas on
 * every poll tick and get a monotonically-decreasing percentage back.
 *
 * Baseline interval is Ford's own published 7.3L Power Stroke figure
 * (F-250–F-550 Power Stroke Diesel Maintenance Intervals flyer):
 *   Normal service: 5,000 miles
 *   Special/severe service: 3,000 miles
 * Ford does not publish an engine-hour oil interval for the 7.3L (that
 * only appears on the 6.0L/6.4L), so hours are used here purely as a
 * signal for detecting extra-severe conditions within a trip (heavy
 * idling), not as a second baseline clock.
 */
public final class OilLifeEngine {
    public static final int NORMAL_INTERVAL_MILES = 5000;
    public static final int SEVERE_INTERVAL_MILES = 3000;

    // Engine oil temp (F) above which we treat driving as extra severe duty.
    private static final double SEVERE_OIL_TEMP_F = 220.0;
    // RPM below which (while engine running) counts as idling, also extra severe.
    private static final double IDLE_RPM_THRESHOLD = 900.0;

    private OilLifeEngine() {}

    /**
     * @param currentPercent   oil life % before this tick (0-100)
     * @param milesDelta       miles driven since last tick
     * @param severeDuty       whether the vehicle profile is set to Ford's severe-service schedule
     * @param avgRpm           average RPM during this tick (extra severe-duty detection: idling)
     * @param avgEngineTempF   average engine oil temp (F) during this tick (extra severe-duty detection: overheating)
     * @return new oil life %, clamped to [0, 100]
     */
    public static double degrade(double currentPercent, double milesDelta, boolean severeDuty,
                                   double avgRpm, double avgEngineTempF) {
        double result = currentPercent - percentUsed(milesDelta, severeDuty, avgRpm, avgEngineTempF);
        return Math.max(0.0, Math.min(100.0, result));
    }

    /** Oil life % consumed by one tick, before clamping; TrackingService accumulates these. */
    public static double percentUsed(double milesDelta, boolean severeDuty, double avgRpm, double avgEngineTempF) {
        int baseIntervalMiles = severeDuty ? SEVERE_INTERVAL_MILES : NORMAL_INTERVAL_MILES;
        return (milesDelta * severityMultiplier(avgRpm, avgEngineTempF) / baseIntervalMiles) * 100.0;
    }

    /** 1.0 = normal duty, up to 1.5 for hot/idling conditions beyond the already-severe baseline. */
    public static double severityMultiplier(double avgRpm, double avgEngineTempF) {
        double multiplier = 1.0;
        if (avgEngineTempF > SEVERE_OIL_TEMP_F) multiplier += 0.25;
        if (avgRpm > 0 && avgRpm < IDLE_RPM_THRESHOLD) multiplier += 0.25;
        return multiplier;
    }

    public static boolean needsChange(double oilLifePercent) {
        return oilLifePercent <= 5.0;
    }

    public static boolean isDueSoon(double oilLifePercent) {
        return oilLifePercent <= 20.0; // i.e. >= 80% of service life used
    }
}
