package com.autosentry.app.obd;

import java.util.Collections;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Critical PIDs for a 2000 F-250 7.3L Power Stroke that MUST be tracked
 * regardless of user dashboard preferences. These define the health baseline
 * and are necessary for anomaly detection, oil-life calculation, and
 * electrical diagnostics.
 *
 * Even if the user unchecks them from the dashboard editor, TrackingService
 * keeps polling these silently. This is the "trust" signal: AutoSentry never
 * stops watching what matters.
 */
public final class CriticalPidSet {
    private static final Set<Integer> CRITICAL = new HashSet<>(Arrays.asList(
        // Engine vitals: without these, we can't determine if the truck is running or in trouble
        PidCatalog.RPM,                    // 0x0C — mandatory for crank detection, oil life, trip start
        PidCatalog.SPEED,                  // 0x0D — mandatory for distance, MPG, trip detection
        PidCatalog.ENGINE_OIL_TEMP,        // 0x5C — oil-life thermal penalty, overheat detection
        PidCatalog.MAF,                    // 0x10 — fuel consumption, instantaneous MPG
        PidCatalog.FUEL_RATE,              // 0x5E — alternative fuel consumption when MAF unavailable
        
        // Electrical/Battery: corrosion and weak ground detection
        0x42,  // Battery voltage — voltage sag = bad ground, corroded posts, harness resistance
        
        // Fuel system: fuel pressure stability = fuel pump ground and harness integrity
        0x0A,  // Fuel pressure — drops under load = weak pump power (corrosion)
        0x23,  // Fuel rail pressure — diesel specifics
        
        // Manifold pressure: boost control and turbo behavior
        0x0B,  // Intake manifold pressure — steady boost = good wastegate, corrosion-free electronics
        
        // Load and throttle: pedal response and electrical noise on accelerator circuit
        0x04,  // Engine load — consistent response to pedal = clean accelerator wiring
        0x49,  // Accelerator pedal — jitter or lag = electrical noise from corroded connector
        
        // Temps: thermal anomalies from failing sensors or electrical noise
        0x0F,  // Intake air temp — sensor reading stability
        0x46   // Ambient air temp — sensor baseline
    ));

    private CriticalPidSet() {}

    /**
     * Returns the set of PIDs that must be polled every tick, regardless
     * of user dashboard preferences. Used by TrackingService to ensure
     * diagnostic data is always collected.
     */
    public static Set<Integer> all() {
        return Collections.unmodifiableSet(CRITICAL);
    }

    /**
     * True if a PID is critical and should be tracked even when unchecked
     * on the dashboard.
     */
    public static boolean isCritical(int pidId) {
        return CRITICAL.contains(pidId);
    }

    /**
     * Merges user's chosen dashboard PIDs with the critical set.
     * Returns a new set that includes both.
     */
    public static Set<Integer> withUserChoices(Set<Integer> userDashboardPids) {
        Set<Integer> merged = new HashSet<>(CRITICAL);
        if (userDashboardPids != null) {
            merged.addAll(userDashboardPids);
        }
        return merged;
    }
}
