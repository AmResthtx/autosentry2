package com.autosentry.app.engine;

import com.autosentry.app.obd.PidCatalog;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/** The last minute of every reading, kept so a stall report shows what led up to it. */
public final class StallLog {
    static final long WINDOW_MS = 60_000L;

    private static final class Snapshot {
        final long time;
        final Map<Integer, Double> values;

        Snapshot(long time, Map<Integer, Double> values) {
            this.time = time;
            this.values = values;
        }
    }

    private final ArrayDeque<Snapshot> snapshots = new ArrayDeque<>();

    public void add(long time, Map<Integer, Double> values) {
        snapshots.addLast(new Snapshot(time, new HashMap<>(values)));
        while (!snapshots.isEmpty() && time - snapshots.peekFirst().time > WINDOW_MS) {
            snapshots.removeFirst();
        }
    }

    /** CSV: one row per reading, timed in seconds from the moment RPM hit 0. */
    public String report(long stalledAt) {
        TreeSet<Integer> ids = new TreeSet<>();
        for (Snapshot s : snapshots) ids.addAll(s.values.keySet());

        StringBuilder sb = new StringBuilder("AutoSentry stall report\n");
        sb.append("RPM fell to 0 with the key on,")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(stalledAt))).append('\n');
        sb.append("\nSeconds from stall");
        for (int id : ids) {
            PidCatalog.Pid def = PidCatalog.get(id);
            sb.append(',').append(def != null ? def.label() : String.format(Locale.US, "PID %02X", id));
        }
        sb.append('\n');
        for (Snapshot s : snapshots) {
            sb.append(String.format(Locale.US, "%.1f", (s.time - stalledAt) / 1000.0));
            for (int id : ids) {
                Double value = s.values.get(id);
                PidCatalog.Pid def = PidCatalog.get(id);
                sb.append(',');
                if (value != null) sb.append(def != null ? def.formatValue(value) : String.valueOf(value));
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
