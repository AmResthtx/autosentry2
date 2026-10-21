package com.autosentry.app.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

import com.autosentry.app.obd.DTCReader;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * One key cycle's key-on-engine-off (KOEO) snapshot, taken before cranking,
 * plus how the crank went. Every value is measured from the truck or the
 * adapter; 0 / null means it didn't answer, not a reading of zero.
 */
@Entity(tableName = "koeo_reports")
public class KoeoReport {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public long timestamp;
    public double keyOnVolts;    // OBD-port voltage before cranking; 0 = no answer
    public String readings;      // "Label: value" lines for every reading the truck answered
    public String storedCodes;   // comma-separated; "" = none; null = no answer
    public String pendingCodes;
    public int crankAttempts;
    public double crankSeconds;  // last attempt; 0 = not measured
    public double minCrankVolts; // lowest voltage seen while cranking; 0 = not measured
    public boolean started;

    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(timestamp)))
                .append('\n');
        sb.append("Battery at key-on: ").append(keyOnVolts > 0 ? volts(keyOnVolts) : "no answer").append('\n');
        sb.append("Stored codes: ").append(codes(storedCodes)).append('\n');
        sb.append("Pending codes: ").append(codes(pendingCodes)).append('\n');
        if (readings != null && !readings.isEmpty()) sb.append(readings).append('\n');
        sb.append("Crank: ");
        if (started) {
            sb.append(crankSeconds > 0 ? String.format(Locale.US, "started after %.1f s", crankSeconds)
                    : "started (under one reading, < ~1 s)");
            if (crankAttempts > 1) sb.append(" on try ").append(crankAttempts);
        } else if (crankAttempts > 0) {
            sb.append("did NOT start (").append(crankAttempts).append(crankAttempts == 1 ? " try" : " tries");
            if (crankSeconds > 0) sb.append(String.format(Locale.US, ", last %.1f s", crankSeconds));
            sb.append(')');
        } else {
            sb.append("not cranked yet");
        }
        if (minCrankVolts > 0) sb.append(", lowest ").append(volts(minCrankVolts)).append(" while cranking");
        return sb.toString();
    }

    private static String volts(double v) {
        return String.format(Locale.US, "%.1f V", v);
    }

    private static String codes(String list) {
        if (list == null) return "no answer";
        if (list.isEmpty()) return "none";
        StringBuilder sb = new StringBuilder();
        for (String code : list.split(",")) {
            sb.append("\n    ").append(DTCReader.describe(code.trim()));
        }
        return sb.toString();
    }
}
