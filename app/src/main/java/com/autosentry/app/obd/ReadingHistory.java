package com.autosentry.app.obd;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The last several minutes of each reading, kept in memory for the graphs. Nothing here is
 * saved: recording to a file is LiveRecorder's job. One fixed-size ring per reading, so a
 * long drive can't grow it.
 */
public final class ReadingHistory {
    /** Twenty minutes at two points a second. */
    public static final int CAPACITY = 2400;
    /** Points closer together than this are skipped. */
    public static final long MIN_GAP_MS = 400L;

    /** A copy of one reading's points, oldest first. */
    public static final class Series {
        public final long[] times;
        public final double[] values;

        Series(long[] times, double[] values) {
            this.times = times;
            this.values = values;
        }

        public int size() {
            return times.length;
        }

        public double min() {
            double m = Double.NaN;
            for (double v : values) if (Double.isNaN(m) || v < m) m = v;
            return m;
        }

        public double max() {
            double m = Double.NaN;
            for (double v : values) if (Double.isNaN(m) || v > m) m = v;
            return m;
        }
    }

    private static final class Ring {
        final long[] times = new long[CAPACITY];
        final float[] values = new float[CAPACITY];
        int next;
        int count;
    }

    private final Map<Integer, Ring> rings = new ConcurrentHashMap<>();

    public void add(int id, long time, double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return;
        Ring ring = rings.get(id);
        if (ring == null) {
            ring = new Ring();
            rings.put(id, ring);
        }
        synchronized (ring) {
            if (ring.count > 0) {
                long last = ring.times[(ring.next + CAPACITY - 1) % CAPACITY];
                if (time - last < MIN_GAP_MS) return;
            }
            ring.times[ring.next] = time;
            ring.values[ring.next] = (float) value;
            ring.next = (ring.next + 1) % CAPACITY;
            if (ring.count < CAPACITY) ring.count++;
        }
    }

    /** Points at or after {@code since}, oldest first. Empty when there are none. */
    public Series since(int id, long since) {
        Ring ring = rings.get(id);
        if (ring == null) return new Series(new long[0], new double[0]);
        synchronized (ring) {
            int start = (ring.next + CAPACITY - ring.count) % CAPACITY;
            int skip = 0;
            while (skip < ring.count && ring.times[(start + skip) % CAPACITY] < since) skip++;
            int n = ring.count - skip;
            long[] times = new long[n];
            double[] values = new double[n];
            for (int i = 0; i < n; i++) {
                int at = (start + skip + i) % CAPACITY;
                times[i] = ring.times[at];
                values[i] = ring.values[at];
            }
            return new Series(times, values);
        }
    }

    public void clear() {
        rings.clear();
    }
}
