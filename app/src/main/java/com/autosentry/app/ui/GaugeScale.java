package com.autosentry.app.ui;

import com.autosentry.app.diagnostics.PidToleranceEngine;
import com.autosentry.app.obd.PidCatalog;

import java.util.ArrayList;
import java.util.List;

/**
 * Dial scale and status for one reading. The dial spans the tolerance band's critical
 * limits when the reading has one, otherwise the range set in {@link PidCatalog}.
 * Plain Java so it can be unit tested.
 */
public final class GaugeScale {
    public enum Level { OK, WATCH, CRITICAL }

    // Counters like engine run time have limits too wide to draw as a dial.
    private static final double MAX_DIAL_SPAN = 100_000;
    private static final int DIVISIONS = 6;
    // Below normal here only means not warmed up yet, which is not a fault.
    private static final int[] WARM_UP_PIDS = {PidCatalog.ENGINE_OIL_TEMP, 0x05};

    public final double min;
    public final double max;
    /** NaN when the reading has no normal range, only a scale. */
    public final double normalMin;
    public final double normalMax;

    private GaugeScale(double min, double max, double normalMin, double normalMax) {
        this.min = min;
        this.max = max;
        this.normalMin = normalMin;
        this.normalMax = normalMax;
    }

    /** The scale for a PID, or null when it can't be drawn as a dial. */
    public static GaugeScale forPid(int pidId) {
        PidToleranceEngine.Tolerance tol = PidToleranceEngine.get(pidId);
        if (tol != null) {
            // A -1 critical floor on a 0..100 reading is a sentinel, not a scale.
            double min = tol.minCritical < 0 && tol.minNormal >= 0 ? 0 : tol.minCritical;
            double max = tol.maxCritical;
            if (!(max > min) || max - min > MAX_DIAL_SPAN) return null;
            return snapped(min, max, tol.minNormal, tol.maxNormal);
        }
        PidCatalog.Pid pid = PidCatalog.get(pidId);
        if (pid == null || Double.isNaN(pid.displayMin) || Double.isNaN(pid.displayMax)
                || !(pid.displayMax > pid.displayMin)) {
            return null;
        }
        return snapped(pid.displayMin, pid.displayMax, Double.NaN, Double.NaN);
    }

    /** Widens the scale to round numbers so the ticks fall evenly from end to end. */
    private static GaugeScale snapped(double min, double max, double normalMin, double normalMax) {
        double step = niceStep(max - min, DIVISIONS);
        double lo = Math.floor(min / step + 1e-9) * step;
        double hi = Math.ceil(max / step - 1e-9) * step;
        return new GaugeScale(lo, hi, normalMin, normalMax);
    }

    public boolean hasNormalRange() {
        return !Double.isNaN(normalMin) && !Double.isNaN(normalMax);
    }

    /** Where the value falls on the dial, 0 to 1. Missing or NaN readings sit at 0. */
    public double fraction(double value) {
        if (Double.isNaN(value)) return 0;
        return Math.max(0, Math.min(1, (value - min) / (max - min)));
    }

    /** A 1, 2, 2.5 or 5 times a power of ten step that gives about {@code target} divisions. */
    public static double niceStep(double span, int target) {
        double raw = span / target;
        double magnitude = Math.pow(10, Math.floor(Math.log10(raw)));
        double f = raw / magnitude;
        double nice = f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10;
        return nice * magnitude;
    }

    /** Round numbers to label along the dial, from one end to the other. */
    public double[] labelTicks() {
        double step = niceStep(max - min, DIVISIONS);
        List<Double> ticks = new ArrayList<>();
        for (double v = Math.ceil(min / step) * step; v <= max + step * 1e-9; v += step) ticks.add(v);
        double[] out = new double[ticks.size()];
        for (int i = 0; i < out.length; i++) out[i] = ticks.get(i);
        return out;
    }

    /** The scale ends plus the labeled ticks between them, in order, without duplicates. */
    public double[] stops() {
        double eps = (max - min) * 1e-6;
        List<Double> stops = new ArrayList<>();
        stops.add(min);
        for (double v : labelTicks()) {
            if (v > stops.get(stops.size() - 1) + eps && v < max - eps) stops.add(v);
        }
        stops.add(max);
        double[] out = new double[stops.size()];
        for (int i = 0; i < out.length; i++) out[i] = stops.get(i);
        return out;
    }

    public static Level level(int pidId, double value) {
        PidToleranceEngine.Tolerance tol = PidToleranceEngine.get(pidId);
        if (tol == null) return Level.OK;
        if (value < tol.minNormal && value >= tol.minCritical) {
            for (int id : WARM_UP_PIDS) {
                if (id == pidId) return Level.OK;
            }
        }
        switch (PidToleranceEngine.checkTolerance(pidId, value)) {
            case "warning": return Level.WATCH;
            case "critical": return Level.CRITICAL;
            default: return Level.OK;
        }
    }
}
