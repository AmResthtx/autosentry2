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
    public String storedCodes;   // comma-separated codes the truck sent; "" = none; null = no answer
    public String pendingCodes;
    // Why that read failed or fell short ("couldn't read after 3 tries: the truck didn't answer");
    // null when it answered. A failed read can still keep the codes that came through.
    public String storedCodesProblem;
    public String pendingCodesProblem;
    public int crankAttempts;
    public double crankSeconds;  // last attempt; 0 = not measured
    public double minCrankVolts; // lowest voltage seen while cranking; 0 = not measured
    public boolean started;

    private static final String COULDNT_READ = "couldn't read";

    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(timestamp)))
                .append('\n');
        sb.append("Battery at key-on: ").append(keyOnVolts > 0 ? volts(keyOnVolts) : "no answer").append('\n');
        sb.append("Stored codes: ").append(codes(storedCodes, storedCodesProblem)).append('\n');
        sb.append("Pending codes: ").append(codes(pendingCodes, pendingCodesProblem)).append('\n');
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

    /** Records one mode of a code read: the codes that came through, and why it fell short if it did. */
    public void setCodes(DTCReader.Result result) {
        StringBuilder joined = new StringBuilder();
        for (String code : result.codes) {
            if (joined.length() > 0) joined.append(',');
            joined.append(code);
        }
        String codes = joined.toString();
        String problem = result.describeProblem();
        if (result.mode == DTCReader.Mode.STORED) {
            storedCodes = problem != null && codes.isEmpty() ? null : codes;
            storedCodesProblem = problem;
        } else {
            pendingCodes = problem != null && codes.isEmpty() ? null : codes;
            pendingCodesProblem = problem;
        }
    }

    static String codes(String list, String problem) {
        StringBuilder sb = new StringBuilder();
        if (problem != null) {
            // Shouted like "did NOT start": a failed read must never be skimmed as "none".
            sb.append(problem.startsWith(COULDNT_READ)
                    ? COULDNT_READ.toUpperCase(Locale.US) + problem.substring(COULDNT_READ.length()) : problem);
        } else if (list == null) {
            return "no answer";
        } else if (list.isEmpty()) {
            return "none";
        }
        if (list != null && !list.isEmpty()) {
            for (String code : list.split(",")) {
                sb.append("\n    ").append(DTCReader.describe(code.trim()));
            }
        }
        return sb.toString();
    }
}
