package com.autosentry.app.ui;

import com.autosentry.app.diagnostics.PidToleranceEngine;

/**
 * Dial scale and status for one reading, from its tolerance band. The dial spans the
 * critical limits, with the normal band marked inside it. Plain Java so it can be unit tested.
 */
public final class GaugeScale {
    public enum Level { OK, WATCH, CRITICAL }

    // Counters like engine run time have limits too wide to draw as a dial.
    private static final double MAX_DIAL_SPAN = 100_000;

    public final double min;
    public final double max;
    public final double normalMin;
    public final double normalMax;

    private GaugeScale(double min, double max, double normalMin, double normalMax) {
        this.min = min;
        this.max = max;
        this.normalMin = normalMin;
        this.normalMax = normalMax;
    }

    /** The scale for a PID, or null when it has no tolerance band or the band is too wide to draw. */
    public static GaugeScale forPid(int pidId) {
        PidToleranceEngine.Tolerance tol = PidToleranceEngine.get(pidId);
        if (tol == null) return null;
        // A -1 critical floor on a 0..100 reading is a sentinel, not a scale.
        double min = tol.minCritical < 0 && tol.minNormal >= 0 ? 0 : tol.minCritical;
        double max = tol.maxCritical;
        if (!(max > min) || max - min > MAX_DIAL_SPAN) return null;
        return new GaugeScale(min, max, tol.minNormal, tol.maxNormal);
    }

    /** Where the value falls on the dial, 0 to 1. Missing or NaN readings sit at 0. */
    public double fraction(double value) {
        if (Double.isNaN(value)) return 0;
        return Math.max(0, Math.min(1, (value - min) / (max - min)));
    }

    public static Level level(int pidId, double value) {
        switch (PidToleranceEngine.checkTolerance(pidId, value)) {
            case "warning": return Level.WATCH;
            case "critical": return Level.CRITICAL;
            default: return Level.OK;
        }
    }
}
