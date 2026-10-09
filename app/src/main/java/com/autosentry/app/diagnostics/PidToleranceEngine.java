package com.autosentry.app.diagnostics;

import java.util.HashMap;
import java.util.Map;

/**
 * Defines normal operating ranges (tolerance bands) for every PID.
 * Used to detect when a reading drifts outside expected behavior.
 *
 * Thresholds are tuned for a 2000 F-250 7.3L Power Stroke diesel.
 * Ranges account for normal variation, sensor accuracy, and load conditions.
 */
public final class PidToleranceEngine {
    private static final Map<Integer, Tolerance> TOLERANCES = new HashMap<>();

    public static final class Tolerance {
        public final int pidId;
        public final String label;
        public final double minNormal;
        public final double maxNormal;
        public final double minCritical;   // below this = alarm
        public final double maxCritical;   // above this = alarm
        public final String unit;

        Tolerance(int pidId, String label, double minNormal, double maxNormal,
                  double minCritical, double maxCritical, String unit) {
            this.pidId = pidId;
            this.label = label;
            this.minNormal = minNormal;
            this.maxNormal = maxNormal;
            this.minCritical = minCritical;
            this.maxCritical = maxCritical;
            this.unit = unit;
        }
    }

    static {
        // RPM: idle ~600, normal driving 1000–3000, redline ~4200
        tol(0x0C, "RPM", 500, 3500, 100, 4500, "rpm");
        
        // Vehicle speed: 0–100 mph in normal driving
        tol(0x0D, "Vehicle Speed", 0, 100, -1, 130, "mph");
        
        // Engine load: 0–100% by definition; highlight continuous high load
        tol(0x04, "Engine Load", 0, 80, -1, 120, "%");
        
        // Throttle position: should vary with pedal; constant 0% or 100% is suspicious
        tol(0x11, "Throttle Position", 0, 100, -1, 120, "%");
        
        // Accelerator pedal: should vary smoothly; jitter > 5% = corroded connector
        tol(0x49, "Accelerator Pedal", 0, 100, -1, 120, "%");
        
        // Intake manifold pressure (vacuum): normal 5–15 psi, loaded 15–25 psi
        // Below 0 = sensor issue, above 30 = wastegate stuck
        tol(0x0B, "Intake Manifold Pressure", 5, 25, 2, 30, "psi");
        
        // Intake air temp: normal 40–100°F, extreme < 0°F or > 150°F = sensor failure
        tol(0x0F, "Intake Air Temp", 40, 120, 0, 160, "°F");
        
        // Mass air flow: 7.3L diesel at idle ~5–10 g/s, loaded ~100–150 g/s
        // Below 0 or above 300 = sensor noise
        tol(0x10, "Mass Air Flow", 5, 200, 0, 300, "g/s");
        
        // Fuel rate: typically 0–30 gal/h; above 35 = runaway injection, below 0 = sensor error
        tol(0x5E, "Fuel Rate", 0, 35, -1, 50, "gal/h");
        
        // Fuel pressure: idle ~18 psi, loaded ~20–22 psi; below 16 = pump ground, above 25 = regulator stuck
        tol(0x0A, "Fuel Pressure", 16, 22, 10, 30, "psi");
        
        // Fuel rail pressure (diesel high-pressure): normal 5000–26000 psi; varies with load
        // Below 3000 or above 30000 = fuel system failure
        tol(0x23, "Fuel Rail Pressure", 5000, 26000, 3000, 30000, "psi");
        
        // Fuel level: 0–100%; erratic reading = sender issue
        tol(0x2F, "Fuel Level", 0, 100, -1, 120, "%");
        
        // Engine oil temp: normal 180–220°F, critical > 240°F = overheat, < 120°F = not warmed
        tol(0x5C, "Engine Oil Temp", 120, 220, 80, 260, "°F");
        
        // Coolant temp: 7.3L thermostat opens near 190°F; sustained > 230°F = cooling system trouble
        tol(0x05, "Engine Coolant Temp", 160, 215, -40, 245, "°F");

        // Barometric pressure: 14–15 psi at sea level, ~12 psi at altitude
        // Below 10 or above 16 = sensor error
        tol(0x33, "Barometric Pressure", 10, 16, 8, 20, "psi");
        
        // Ambient air temp: -20–120°F; outside = sensor issue
        tol(0x46, "Ambient Air Temp", -20, 120, -50, 150, "°F");
        
        // Battery voltage (KOEO): 12.6 V ± 0.5 V; (cranking): > 9.5 V (critical: < 8.0 V)
        // Low voltage = corroded battery posts, weak alternator, or ground issue
        tol(0x42, "Battery Voltage", 12, 14.5, 9.5, 16, "V");
        
        // Engine run time: should only increase; negative = sensor error
        tol(0x1F, "Engine Run Time", 0, 999999, -1, 10000000, "min");
    }

    private static void tol(int pidId, String label, double minNormal, double maxNormal,
                             double minCrit, double maxCrit, String unit) {
        TOLERANCES.put(pidId, new Tolerance(pidId, label, minNormal, maxNormal, minCrit, maxCrit, unit));
    }

    private PidToleranceEngine() {}

    /**
     * Returns the tolerance band for a PID.
     * null if no tolerance is defined (shouldn't happen for standard PIDs).
     */
    public static Tolerance get(int pidId) {
        return TOLERANCES.get(pidId);
    }

    /**
     * Checks if a value is within the normal operating range.
     * Returns:
     *   "ok" — within normal band
     *   "warning" — outside normal but within critical
     *   "critical" — outside critical band (likely failure or sensor malfunction)
     *   "unknown" — no tolerance defined for this PID
     */
    public static String checkTolerance(int pidId, double value) {
        Tolerance tol = get(pidId);
        if (tol == null) return "unknown";
        
        if (value >= tol.minNormal && value <= tol.maxNormal) return "ok";
        if (value >= tol.minCritical && value <= tol.maxCritical) return "warning";
        return "critical";
    }

    /**
     * Returns a human-readable explanation of why a reading is out of spec.
     */
    public static String explainAnomaly(int pidId, double value) {
        Tolerance tol = get(pidId);
        if (tol == null) return "Unknown PID " + pidId;
        
        String status = checkTolerance(pidId, value);
        String reading = String.format("%.1f %s", value, tol.unit);
        String expected = String.format("%.1f–%.1f %s", tol.minNormal, tol.maxNormal, tol.unit);
        
        if (status.equals("ok")) return "Normal";
        if (status.equals("warning")) return reading + " (expected " + expected + ")";
        
        // Critical: provide diagnostics hint
        switch (pidId) {
            case 0x0C: // RPM
                if (value < 100) return "RPM too low or stalled—check ignition, fuel delivery";
                if (value > 4500) return "RPM overspeed—check governor, throttle";
                return "Erratic RPM—sensor or fuel system noise";
            case 0x0D: // Speed
                return "Vehicle speed erratic—check speedometer sensor";
            case 0x0F: // Intake air temp
                return "Air temp sensor failure—reading " + reading + " " + (value < 0 ? "(freezing?" : "(boiling?") + ")";
            case 0x10: // MAF
                if (value < 0) return "Mass air flow negative—sensor error";
                if (value > 300) return "MAF too high—intake leak, sensor malfunction";
                return "MAF erratic—check intake ducts and sensor cleanliness";
            case 0x0A: // Fuel pressure
                if (value < 14) return "Fuel pressure LOW—check fuel pump power (ground/harness), regulator";
                if (value > 25) return "Fuel pressure HIGH—regulator stuck or blocked fuel return";
                return "Fuel pressure unstable—pump mechanical wear";
            case 0x23: // Fuel rail pressure (diesel)
                if (value < 3000) return "Fuel rail pressure LOW—check fuel pump, injector seals";
                if (value > 30000) return "Fuel rail pressure HIGH—regulator failure";
                return "Fuel rail pressure erratic—check fuel system";
            case 0x0B: // Intake manifold (vacuum/boost)
                if (value < 2) return "Manifold vacuum HIGH—vacuum leak, bad sensor, or turbo malfunction";
                if (value > 30) return "Manifold pressure HIGH—wastegate stuck or electronic control failure";
                return "Manifold pressure erratic—turbo / intercooler issue";
            case 0x5C: // Engine oil temp
                if (value > 240) return "Engine oil temp CRITICAL—overheat, cooling system failure";
                if (value < 100) return "Engine oil temp low—thermostat stuck open or cold start";
                return "Oil temp reading erratic—sensor malfunction";
            case 0x42: // Battery voltage
                if (value < 9.5) return "Battery voltage LOW—cranking power loss from corroded battery posts, bad ground, weak alternator";
                if (value > 16) return "Battery voltage HIGH—alternator regulator failure";
                return "Battery voltage unstable—loose cable, corroded connector";
            case 0x46: // Ambient air temp
                if (value < -40 || value > 150) return "Ambient air temp sensor failure—reading " + reading;
                return "Ambient temperature unstable";
            default:
                return "Out of spec: " + reading + " (expected " + expected + ")";
        }
    }
}
