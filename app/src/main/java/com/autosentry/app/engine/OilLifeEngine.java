package com.autosentry.app.engine;

/**
 * Degrades oil life % as the vehicle accumulates miles and engine hours.
 * The old app computed this once and never called it again during a trip —
 * this class exists so callers (TrackingService) can feed it small deltas
 * on every poll tick and get a monotonically-decreasing percentage back.
 *
 * Baseline interval: 7,500 miles OR 250 engine hours, whichever comes first
 * (typical severe-duty diesel interval). A severe-duty multiplier shortens
 * the effective interval when the engine is run hot or idled heavily, since
 * that degrades oil faster per mile/hour than steady highway driving.
 */
public final class OilLifeEngine {
    public static final double BASE_INTERVAL_MILES = 7500.0;
    public static final double BASE_INTERVAL_HOURS = 250.0;

    // Coolant temp (F) above which we treat driving as severe duty.
    private static final double SEVERE_COOLANT_TEMP_F = 220.0;
    // RPM below which (while engine running) counts as idling, also severe duty.
    private static final double IDLE_RPM_THRESHOLD = 900.0;

    private OilLifeEngine() {}

    /**
     * @param currentPercent      oil life % before this tick (0-100)
     * @param milesDelta          miles driven since last tick
     * @param engineHoursDelta    engine hours accumulated since last tick
     * @param avgRpm              average RPM during this tick (for severe-duty detection)
     * @param avgCoolantTempF     average coolant temp (F) during this tick
     * @return new oil life %, clamped to [0, 100]
     */
    public static double degrade(double currentPercent, double milesDelta, double engineHoursDelta,
                                  double avgRpm, double avgCoolantTempF) {
        double severity = severityMultiplier(avgRpm, avgCoolantTempF);

        double milesFraction = (milesDelta * severity) / BASE_INTERVAL_MILES;
        double hoursFraction = (engineHoursDelta * severity) / BASE_INTERVAL_HOURS;

        double percentUsed = (milesFraction + hoursFraction) * 100.0;
        double result = currentPercent - percentUsed;
        return Math.max(0.0, Math.min(100.0, result));
    }

    /** 1.0 = normal duty, up to 1.5 for hot/idling conditions. */
    public static double severityMultiplier(double avgRpm, double avgCoolantTempF) {
        double multiplier = 1.0;
        if (avgCoolantTempF > SEVERE_COOLANT_TEMP_F) multiplier += 0.25;
        if (avgRpm > 0 && avgRpm < IDLE_RPM_THRESHOLD) multiplier += 0.25;
        return multiplier;
    }

    public static boolean needsChange(double oilLifePercent) {
        return oilLifePercent <= 5.0;
    }
}
