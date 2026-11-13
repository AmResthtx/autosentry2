package com.autosentry.app.obd;

import java.io.IOException;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes the chosen readings to a CSV file, one row a second, for a spreadsheet. The
 * columns are fixed when recording starts. A reading with no value at that moment leaves
 * its cell empty: nothing is carried forward or filled in. Unproven readings say so in
 * their column title.
 */
public final class LiveRecorder {
    public static final long ROW_INTERVAL_MS = 1000L;

    private final Writer out;
    private final List<PidCatalog.Pid> columns = new ArrayList<>();
    private final long startedAt;
    private final SimpleDateFormat clock = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private long lastRow = Long.MIN_VALUE / 2;
    private int rows;

    public LiveRecorder(Writer out, List<Integer> pidIds, long now) throws IOException {
        this.out = out;
        this.startedAt = now;
        StringBuilder header = new StringBuilder("Time,Seconds");
        for (int id : pidIds) {
            PidCatalog.Pid def = PidCatalog.get(id);
            if (def == null) continue;
            columns.add(def);
            header.append(',').append(cell(columnTitle(def)));
        }
        out.write(header.append('\n').toString());
        out.flush();
    }

    /** "Injection Control Pressure (psi) [published]": the name, the unit, and how far to trust it. */
    static String columnTitle(PidCatalog.Pid def) {
        return def.label() + " [" + def.trust.shortLabel + "]";
    }

    /** Writes a row if a second has passed since the last one. Returns true when it wrote. */
    public boolean tick(long now, Map<Integer, Double> values) throws IOException {
        if (now - lastRow < ROW_INTERVAL_MS) return false;
        lastRow = now;
        StringBuilder row = new StringBuilder(clock.format(new Date(now)))
                .append(',').append(String.format(Locale.US, "%.1f", (now - startedAt) / 1000.0));
        for (PidCatalog.Pid def : columns) {
            Double value = values.get(def.id);
            row.append(',');
            if (value != null && !value.isNaN()) row.append(String.format(Locale.US, "%.3f", value));
        }
        out.write(row.append('\n').toString());
        rows++;
        if (rows % 10 == 0) out.flush(); // a crash or a dead battery loses at most ten seconds
        return true;
    }

    public int rows() {
        return rows;
    }

    public long startedAt() {
        return startedAt;
    }

    public void close() throws IOException {
        out.close();
    }

    private static String cell(String text) {
        return text.contains(",") || text.contains("\"") ? "\"" + text.replace("\"", "\"\"") + "\"" : text;
    }
}
