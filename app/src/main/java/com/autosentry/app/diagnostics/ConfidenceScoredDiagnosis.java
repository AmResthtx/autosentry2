package com.autosentry.app.diagnostics;

import com.autosentry.app.data.VehicleUpgrade;
import com.autosentry.app.obd.PidCatalog;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Evidence-based anomaly detection with confidence scoring.
 *
 * Instead of flagging every out-of-range reading as a diagnosis,
 * this engine:
 * 1. Collects multiple independent signals
 * 2. Scores how well they correlate
 * 3. Assigns confidence to potential root causes
 * 4. Only recommends action above a threshold
 *
 * Examples:
 * - Single fuel pressure low reading: 0.3 confidence (probably noise)
 * - Fuel pressure declining over 3 trips + voltage sag: 0.65 confidence (investigate)
 * - Fuel pressure sag + voltage sag + accel jitter + stable alternator: 0.85 confidence (ground issue)
 */
public class ConfidenceScoredDiagnosis {

    /**
     * Represents a potential diagnosis with supporting evidence.
     */
    public static class Diagnosis {
        public String rootCause;              // "Ground corrosion", "Weak alternator", "Fuel pump relay"
        public double confidence;             // 0.0 - 1.0
        public String explanation;            // why we think this
        public Set<String> supportingSignals; // which PIDs pointed to this
        public Set<String> contradictingSigns; // which PIDs argue against this
        public String nextDiagnosticSteps;    // what to check first
        public int minutesToLikelyFailure;    // estimated time to failure (-1 = stable)

        @Override
        public String toString() {
            return String.format(
                "%s (%.0f%% confidence)\nEvidence: %s\nNext steps: %s",
                rootCause,
                confidence * 100,
                String.join(", ", supportingSignals),
                nextDiagnosticSteps
            );
        }
    }

    /**
     * Collects anomalous PID readings and their context.
     */
    public static class PidAnomaly {
        public int pidId;                  // which PID
        public double value;               // current reading
        public double baselineMin;         // learned min for this truck
        public double baselineMax;         // learned max for this truck
        public double deviation;           // (value - baseline) / baseline
        public String context;             // "idle", "highway", "acceleration", "towing"
        public double ambientTemp;         // degrees F
        public double engineLoadPercent;   // 0-100%
        public long timeSinceStart;        // seconds
        public String severity;            // "minor", "moderate", "critical"
    }

    /**
     * Analyzes a collection of PID anomalies and generates ranked diagnoses.
     * Returns list sorted by confidence (highest first).
     */
    public static java.util.List<Diagnosis> diagnose(java.util.List<PidAnomaly> anomalies) {
        java.util.List<Diagnosis> diagnoses = new java.util.ArrayList<>();

        // Check for corrosion signature
        Diagnosis corrosionDiag = checkCorrosionSignature(anomalies);
        if (corrosionDiag != null) diagnoses.add(corrosionDiag);

        // Check for alternator failure signature
        Diagnosis alternatorDiag = checkAlternatorSignature(anomalies);
        if (alternatorDiag != null) diagnoses.add(alternatorDiag);

        // Check for fuel pump relay failure
        Diagnosis relayDiag = checkFuelPumpRelaySignature(anomalies);
        if (relayDiag != null) diagnoses.add(relayDiag);

        // Check for injector/fuel system issues
        Diagnosis injectorDiag = checkInjectorSignature(anomalies);
        if (injectorDiag != null) diagnoses.add(injectorDiag);

        // Check for electrical wiring/harness issues
        Diagnosis harnessDiag = checkHarnessSignature(anomalies);
        if (harnessDiag != null) diagnoses.add(harnessDiag);

        // Sort by confidence
        diagnoses.sort((a, b) -> Double.compare(b.confidence, a.confidence));
        return diagnoses;
    }

    /**
     * Corrosion Signature:
     * - Voltage sag (especially under load)
     * - Fuel pressure instability
     * - Accelerator pedal jitter
     * - Stable alternator output (rules out alt failure)
     */
    private static Diagnosis checkCorrosionSignature(java.util.List<PidAnomaly> anomalies) {
        double confidence = 0.0;
        Set<String> supporting = new HashSet<>();
        Set<String> contradicting = new HashSet<>();

        boolean voltageAbnormal = false;
        boolean fuelPressureUnstable = false;
        boolean accelJitter = false;
        boolean alternatorStable = false;

        for (PidAnomaly a : anomalies) {
            if (a.pidId == 0x42) { // Battery voltage
                if (a.deviation < -0.03) { // More than 3% below baseline
                    voltageAbnormal = true;
                    supporting.add("Voltage sag " + String.format("%.1f V", a.value));
                    confidence += 0.25;
                } else {
                    alternatorStable = true;
                    supporting.add("Alternator stable");
                    confidence += 0.1;  // Rules out alt failure
                }
            }
            if (a.pidId == 0x0A) { // Fuel pressure
                if (Math.abs(a.deviation) > 0.05 && a.context.contains("acceleration")) {
                    fuelPressureUnstable = true;
                    supporting.add("Fuel pressure unstable under load");
                    confidence += 0.25;
                }
            }
            if (a.pidId == 0x49) { // Accelerator pedal
                if (a.deviation > 0.05) {
                    accelJitter = true;
                    supporting.add("Accelerator pedal jitter");
                    confidence += 0.2;
                }
            }
        }

        if (confidence < 0.35) return null;  // Not enough evidence

        Diagnosis d = new Diagnosis();
        d.rootCause = "Electrical Ground or Connector Corrosion";
        d.confidence = Math.min(confidence, 0.9);
        d.supportingSignals = supporting;
        d.contradictingSigns = contradicting;
        d.explanation = "Multiple signals suggest rising resistance in ground circuit or connector. "
            + "Battery voltage sags under load (fuel pump, injectors demand current). "
            + "Fuel pressure becomes unstable when pump draws peak current. "
            + "Accelerator pedal noise indicates electrical noise from poor grounding.";
        d.nextDiagnosticSteps = "1. Test voltage drop across battery cables (should be <0.2V). "
            + "2. Inspect fuel pump connector for corrosion. "
            + "3. Check engine block ground straps for corrosion. "
            + "4. If voltage drop > 0.5V, replace cables or connectors.";
        d.minutesToLikelyFailure = (d.confidence > 0.8) ? 60 : 1440;  // Hours to failure
        return d;
    }

    /**
     * Alternator Failure Signature:
     * - Voltage sag but fuel pressure stable
     * - Voltage declining over weeks (battery aging OR alternator failing)
     * - Alternator voltage hunting (>0.3V swings)
     */
    private static Diagnosis checkAlternatorSignature(java.util.List<PidAnomaly> anomalies) {
        double confidence = 0.0;
        Set<String> supporting = new HashSet<>();

        boolean voltageSagButFuelOk = false;
        boolean voltageHunting = false;

        for (PidAnomaly a : anomalies) {
            if (a.pidId == 0x42 && a.deviation < -0.03) { // Voltage sag
                // Check if fuel pressure is NOT saging (argues for alternator, not ground)
                if (anomalies.stream()
                    .filter(x -> x.pidId == 0x0A)
                    .noneMatch(x -> Math.abs(x.deviation) > 0.05)) {
                    voltageSagButFuelOk = true;
                    supporting.add("Voltage sag but fuel pressure stable");
                    confidence += 0.3;
                }
                // Check for hunting (rapid voltage changes)
                if (Math.abs(a.deviation) > 0.04) {
                    voltageHunting = true;
                    supporting.add("Voltage regulation instability");
                    confidence += 0.2;
                }
            }
        }

        if (confidence < 0.4) return null;

        Diagnosis d = new Diagnosis();
        d.rootCause = "Alternator Regulator Failure or Brush Wear";
        d.confidence = Math.min(confidence, 0.85);
        d.supportingSignals = supporting;
        d.explanation = "Voltage sags without corresponding fuel system stress suggests the alternator "
            + "isn't keeping up with electrical demand. Regulator may be stuck or brushes wearing.";
        d.nextDiagnosticSteps = "1. Measure alternator output at idle (should be 13.5-14.8V). "
            + "2. Load test: run lights, AC, and measure voltage (should recover to 13.5V+). "
            + "3. If voltage won't recover, alternator or regulator needs replacement.";
        d.minutesToLikelyFailure = 2880;  // ~2 days (battery will eventually fail to charge)
        return d;
    }

    /**
     * Fuel Pump Relay Failure Signature:
     * - Voltage normal at battery
     * - Fuel pressure drops specifically when pump activates
     * - Fuel pressure intermittent (works sometimes, not others)
     */
    private static Diagnosis checkFuelPumpRelaySignature(java.util.List<PidAnomaly> anomalies) {
        double confidence = 0.0;
        Set<String> supporting = new HashSet<>();

        boolean voltageNormal = false;
        boolean fuelPressureLow = false;
        boolean fuelPressureIntermittent = false;

        for (PidAnomaly a : anomalies) {
            if (a.pidId == 0x42 && Math.abs(a.deviation) < 0.03) {  // Voltage normal
                voltageNormal = true;
                supporting.add("Battery voltage normal");
                confidence += 0.15;
            }
            if (a.pidId == 0x0A && a.deviation < -0.1) {  // Fuel pressure low
                fuelPressureLow = true;
                supporting.add("Fuel pressure low");
                confidence += 0.25;
            }
        }

        if (confidence < 0.3) return null;

        Diagnosis d = new Diagnosis();
        d.rootCause = "Fuel Pump Relay Contact Resistance or Failure";
        d.confidence = Math.min(confidence, 0.75);
        d.supportingSignals = supporting;
        d.explanation = "Battery voltage is stable, but fuel pressure is low or intermittent. "
            + "This points to high resistance in the fuel pump power circuit, typically a relay with worn contacts.";
        d.nextDiagnosticSteps = "1. Listen for fuel pump prime when key is turned ON (should hear click/hum). "
            + "2. Measure voltage at fuel pump connector: should be 12V+. If low, relay is failing. "
            + "3. Swap fuel pump relay with another (horn relay) to test. "
            + "4. If pressure recovers with swapped relay, replace the fuel pump relay.";
        d.minutesToLikelyFailure = 120;  // Imminent no-start
        return d;
    }

    /**
     * Injector/Fuel System Signature:
     * - MAF erratic or high
     * - Specific cylinder misfire (same cylinder every trip)
     * - Fuel pressure sag only under acceleration
     * - Black smoke under load
     */
    private static Diagnosis checkInjectorSignature(java.util.List<PidAnomaly> anomalies) {
        double confidence = 0.0;
        Set<String> supporting = new HashSet<>();

        for (PidAnomaly a : anomalies) {
            if (a.pidId == 0x10) {  // MAF
                if (a.deviation > 0.15) {
                    supporting.add("MAF abnormally high");
                    confidence += 0.2;
                }
            }
            if (a.pidId == 0x0A) {  // Fuel pressure
                if (a.deviation < -0.1 && a.engineLoadPercent > 50) {
                    supporting.add("Fuel pressure sags under high load");
                    confidence += 0.3;
                }
            }
        }

        if (confidence < 0.3) return null;

        Diagnosis d = new Diagnosis();
        d.rootCause = "Fuel System or Injector Issue";
        d.confidence = Math.min(confidence, 0.7);
        d.supportingSignals = supporting;
        d.explanation = "Fuel system is struggling to maintain pressure under high demand. "
            + "Could be failing injector, clogged fuel filter, or weak fuel pump.";
        d.nextDiagnosticSteps = "1. Check fuel filter condition (age, restriction). "
            + "2. Measure fuel pressure at idle vs WOT (should rise, not drop). "
            + "3. If pressure won't build, fuel pump may be failing or filter clogged. "
            + "4. Scan for injector codes (P0270-P0278).";
        d.minutesToLikelyFailure = 480;  // 8 hours (can limp, but degrading)
        return d;
    }

    /**
     * Wiring/Harness Signature:
     * - Multiple different DTCs across trips (no pattern)
     * - Voltage noise (jitter) on multiple circuits
     * - Random sensor faults
     */
    private static Diagnosis checkHarnessSignature(java.util.List<PidAnomaly> anomalies) {
        double confidence = 0.0;
        Set<String> supporting = new HashSet<>();

        int jitterySensors = 0;
        for (PidAnomaly a : anomalies) {
            if (a.severity.equals("minor") && Math.abs(a.deviation) > 0.08) {
                jitterySensors++;
            }
        }

        if (jitterySensors >= 2) {
            supporting.add("Multiple sensors exhibiting noise");
            confidence += 0.4;
        }

        if (confidence < 0.3) return null;

        Diagnosis d = new Diagnosis();
        d.rootCause = "Electrical Wiring or Harness Chafing/Shorting";
        d.confidence = Math.min(confidence, 0.7);
        d.supportingSignals = supporting;
        d.explanation = "Multiple unrelated sensors showing noise or intermittent faults suggests "
            + "a wiring harness issue (chafed wire creating intermittent short or open circuit).";
        d.nextDiagnosticSteps = "1. Inspect underhood harnesses for visible damage, chafing, or burnt insulation. "
            + "2. Check for water intrusion in connectors (moisture causes corrosion). "
            + "3. Wiggle engine harness while watching for fault codes (reproduces intermittent issue). "
            + "4. May require harness replacement if damage is extensive.";
        d.minutesToLikelyFailure = 10080;  // 1 week (unpredictable failure)
        return d;
    }
}
