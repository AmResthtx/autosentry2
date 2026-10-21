package com.autosentry.app.diagnostics;

import com.autosentry.app.data.VehicleProfile;
import com.autosentry.app.data.VehicleUpgrade;
import com.autosentry.app.obd.PidCatalog;

import java.util.HashMap;
import java.util.Map;

/**
 * Learns per-vehicle baseline for each PID under various conditions.
 *
 * Instead of using universal thresholds, AutoSentry learns:
 * - This truck's fuel pressure at idle
 * - This truck's RPM at idle
 * - This truck's voltage during cold start
 * - This truck's boost target when tuned
 *
 * After 5 representative trips, the app has a solid baseline.
 * After that, anomalies are "deviation from this truck's normal," not absolute values.
 */
public class VehicleBaselineProfile {

    /**
     * Statistics for a single PID under specific conditions.
     */
    public static class PidBaseline {
        public int pidId;
        public String condition;              // "idle", "highway 60mph", "wot", "cold_start"
        public double minObserved;            // minimum value recorded
        public double maxObserved;            // maximum value recorded
        public double avgObserved;            // average value
        public double stdDeviation;           // variance
        public int sampleCount;               // how many trips contributed
        public long lastUpdated;              // when this was last updated

        public double acceptableMin() {
            return minObserved - (stdDeviation * 0.5);  // Allow ±0.5 std dev
        }

        public double acceptableMax() {
            return maxObserved + (stdDeviation * 0.5);
        }

        public boolean isAnomaly(double value) {
            return value < acceptableMin() || value > acceptableMax();
        }

        public double deviationPercent(double value) {
            return Math.abs(value - avgObserved) / avgObserved * 100.0;
        }
    }

    private final Map<String, PidBaseline> baselines = new HashMap<>();  // key = "pidId_condition"
    private int trialsCompleted = 0;                                      // how many representative trips
    private final int TRIALS_FOR_CONFIDENCE = 5;                         // need at least 5 trips

    /**
     * Records a PID reading during a specific driving condition.
     * Accumulates data to build per-truck baseline.
     */
    public void recordReading(int pidId, double value, String condition) {
        String key = pidId + "_" + condition;
        PidBaseline baseline = baselines.computeIfAbsent(key, k -> {
            PidBaseline b = new PidBaseline();
            b.pidId = pidId;
            b.condition = condition;
            b.minObserved = value;
            b.maxObserved = value;
            b.avgObserved = value;
            b.stdDeviation = 0;
            b.sampleCount = 1;
            b.lastUpdated = System.currentTimeMillis();
            return b;
        });

        // Update running statistics (Welford's algorithm for numerically stable std dev)
        double delta = value - baseline.avgObserved;
        baseline.avgObserved += delta / (baseline.sampleCount + 1);
        baseline.stdDeviation += delta * (value - baseline.avgObserved);
        baseline.minObserved = Math.min(baseline.minObserved, value);
        baseline.maxObserved = Math.max(baseline.maxObserved, value);
        baseline.sampleCount++;
        baseline.lastUpdated = System.currentTimeMillis();

        if (baseline.sampleCount >= TRIALS_FOR_CONFIDENCE) {
            trialsCompleted = Math.min(trialsCompleted + 1, TRIALS_FOR_CONFIDENCE);
        }
    }

    /**
     * Returns baseline for a PID under specific conditions.
     * Returns null if not enough data yet.
     */
    public PidBaseline getBaseline(int pidId, String condition) {
        return baselines.get(pidId + "_" + condition);
    }

    /**
     * Checks if a reading is anomalous for this truck.
     * Requires baseline to have at least 3 samples before making judgment.
     */
    public boolean isAnomalous(int pidId, double value, String condition) {
        PidBaseline baseline = getBaseline(pidId, condition);
        if (baseline == null || baseline.sampleCount < 3) {
            return false;  // Not enough data; don't flag as anomalous
        }
        return baseline.isAnomaly(value);
    }

    /**
     * Returns deviation from baseline as percentage.
     * Returns 0.0 if no baseline exists.
     */
    public double getDeviationPercent(int pidId, double value, String condition) {
        PidBaseline baseline = getBaseline(pidId, condition);
        if (baseline == null) {
            return 0.0;
        }
        return baseline.deviationPercent(value);
    }

    /**
     * Returns confidence in this baseline (0.0 - 1.0).
     * 0.0 = just started, 1.0 = seen 10+ trips.
     */
    public double getConfidence() {
        return Math.min(trialsCompleted / (double) TRIALS_FOR_CONFIDENCE, 1.0);
    }

    /**
     * Returns true if baseline is mature enough to trust for diagnostics.
     */
    public boolean isReadyForDiagnostics() {
        return trialsCompleted >= TRIALS_FOR_CONFIDENCE;
    }

    /**
     * Adjusts baseline based on known vehicle upgrades.
     * E.g., if truck has 160cc injectors, fuel rate max should be higher.
     */
    public void applyUpgradeAdjustments(VehicleUpgrade upgrade) {
        // Fuel rate: larger injectors = higher peak
        if (upgrade.injectorSize != null && upgrade.injectorSize.contains("160")) {
            PidBaseline fuelRateBase = getBaseline(0x5E, "wot");
            if (fuelRateBase != null) {
                fuelRateBase.maxObserved = Math.max(fuelRateBase.maxObserved, 50);
            }
        }

        // Boost: tuned trucks run higher boost
        if (upgrade.boostTargetPsi != null && upgrade.boostTargetPsi.contains("25")) {
            PidBaseline boostBase = getBaseline(0x0B, "wot");
            if (boostBase != null) {
                boostBase.maxObserved = Math.max(boostBase.maxObserved, 25);
            }
        }

        // Fuel pressure: aftermarket pumps run higher
        if (upgrade.fuelPumpType != null && !upgrade.fuelPumpType.contains("stock")) {
            PidBaseline fuelPressBase = getBaseline(0x0A, "idle");
            if (fuelPressBase != null) {
                fuelPressBase.maxObserved = Math.max(fuelPressBase.maxObserved, 23);
            }
        }
    }

    /**
     * Exports baseline as human-readable string for debugging.
     */
    public String exportSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Vehicle Baseline Profile\n");
        sb.append(String.format("Confidence: %.0f%% (%d trips recorded)\n", getConfidence() * 100, trialsCompleted));
        sb.append("\n");

        for (Map.Entry<String, PidBaseline> entry : baselines.entrySet()) {
            PidBaseline b = entry.getValue();
            sb.append(String.format("PID 0x%02X (%s):\n", b.pidId, b.condition));
            sb.append(String.format("  Normal Range: %.1f - %.1f\n", b.acceptableMin(), b.acceptableMax()));
            sb.append(String.format("  Observed: min=%.1f, avg=%.1f, max=%.1f\n",
                b.minObserved, b.avgObserved, b.maxObserved));
            sb.append(String.format("  Std Dev: %.2f (samples: %d)\n", b.stdDeviation, b.sampleCount));
            sb.append("\n");
        }

        return sb.toString();
    }
}
