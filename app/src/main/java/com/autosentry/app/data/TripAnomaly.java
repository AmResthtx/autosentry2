package com.autosentry.app.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * One flagged anomaly within a trip: a PID reading that deviated from
 * its expected operating tolerance. Examples:
 * - RPM hunting 200–400 RPM at idle (should be stable ~600 ± 50)
 * - Fuel pressure drops under acceleration (should stay 18–20 psi)
 * - Voltage sag during crank (healthy: > 9.5 V, weak: < 8.5 V)
 * - Accelerator pedal noise/jitter (sign of corroded sensor connector)
 */
@Entity(tableName = "trip_anomalies")
public class TripAnomaly {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public long sessionId;         // which trip
    public long timestamp;         // when the anomaly was detected
    public int pidId;              // which PID was out of spec
    public String pidLabel;        // "RPM", "Fuel Pressure", "Battery Voltage"
    public double observedValue;   // what we saw
    public double expectedMin;     // tolerance band
    public double expectedMax;
    public String severity;        // "warning", "critical", "informational"
    public String explanation;     // "RPM hunting at idle—sticky IAC valve or fuel delivery variance?"
    public int occurrences;        // how many times this anomaly was flagged in the trip (rolling count)
}
