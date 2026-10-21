package com.autosentry.app.engine;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.ToDoubleFunction;

/**
 * Everything read while the starter turns the engine: RPM and how much it
 * swung since the previous reading, injection control pressure (ICP), IPR
 * duty and battery voltage. A missing reading is NaN.
 */
public final class CrankLog {
    // The 7.3L doesn't fire its injectors until ICP reaches 500 psi.
    static final double MIN_START_ICP_PSI = 500;

    private static final class Sample {
        final long time;
        final double rpm, icpPsi, iprPercent, volts;

        Sample(long time, double rpm, double icpPsi, double iprPercent, double volts) {
            this.time = time;
            this.rpm = rpm;
            this.icpPsi = icpPsi;
            this.iprPercent = iprPercent;
            this.volts = volts;
        }
    }

    private final long startedAt;
    private final List<Sample> samples = new ArrayList<>();

    public CrankLog(long startedAt) {
        this.startedAt = startedAt;
    }

    public long startedAt() {
        return startedAt;
    }

    public void add(long time, double rpm, double icpPsi, double iprPercent, double volts) {
        samples.add(new Sample(time, rpm, icpPsi, iprPercent, volts));
    }

    /** Shown live while cranking, e.g. "Cranking 2.4 s: 180 rpm (+30), ICP 420 psi, IPR 60%, 10.8 V". */
    public String liveLine() {
        if (samples.isEmpty()) return "Cranking";
        int last = samples.size() - 1;
        Sample s = samples.get(last);
        List<String> parts = new ArrayList<>();
        if (!Double.isNaN(s.rpm)) {
            double change = rpmChange(last);
            parts.add(String.format(Locale.US, "%.0f rpm", s.rpm)
                    + (Double.isNaN(change) ? "" : String.format(Locale.US, " (%+.0f)", change)));
        }
        if (!Double.isNaN(s.icpPsi)) parts.add(String.format(Locale.US, "ICP %.0f psi", s.icpPsi));
        if (!Double.isNaN(s.iprPercent)) parts.add(String.format(Locale.US, "IPR %.0f%%", s.iprPercent));
        if (!Double.isNaN(s.volts)) parts.add(String.format(Locale.US, "%.1f V", s.volts));
        String head = String.format(Locale.US, "Cranking %.1f s", seconds(s.time));
        return parts.isEmpty() ? head : head + ": " + String.join(", ", parts);
    }

    /** One line for the alert and the debug log. */
    public String summary(boolean started, long endedAt) {
        StringBuilder sb = new StringBuilder(started ? "Started after " : "No start after ");
        sb.append(String.format(Locale.US, "%.1f s of cranking", seconds(endedAt)));
        List<String> parts = new ArrayList<>();
        if (!Double.isNaN(minRpm())) parts.add(String.format(Locale.US, "%.0f-%.0f rpm", minRpm(), maxRpm()));
        if (!Double.isNaN(peakIcp())) parts.add(String.format(Locale.US, "peak ICP %.0f psi", peakIcp()));
        if (!Double.isNaN(lowestVolts())) parts.add(String.format(Locale.US, "lowest %.1f V", lowestVolts()));
        if (!parts.isEmpty()) sb.append(": ").append(String.join(", ", parts));
        if (icpTooLow(started)) sb.append(". ICP never reached the 500 psi needed to fire the injectors");
        return sb.toString();
    }

    /** CSV: summary rows, then every reading with its RPM swing. */
    public String report(boolean started, long endedAt) {
        StringBuilder sb = new StringBuilder("AutoSentry crank report\n");
        sb.append("Cranking began,")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(startedAt))).append('\n');
        sb.append("Result,").append(started ? "Started" : "No start").append('\n');
        sb.append("Seconds cranking,").append(String.format(Locale.US, "%.1f", seconds(endedAt))).append('\n');
        sb.append("Lowest RPM,").append(fmt("%.0f", minRpm())).append('\n');
        sb.append("Highest RPM,").append(fmt("%.0f", maxRpm())).append('\n');
        sb.append("Peak ICP psi,").append(fmt("%.0f", peakIcp())).append('\n');
        sb.append("Peak IPR %,").append(fmt("%.0f", peakIpr())).append('\n');
        sb.append("Lowest battery V,").append(fmt("%.1f", lowestVolts())).append('\n');
        if (icpTooLow(started)) {
            sb.append("Note,ICP never reached the 500 psi needed to fire the injectors\n");
        }
        sb.append("\nSeconds,RPM,RPM change,ICP psi,IPR %,Battery V\n");
        for (int i = 0; i < samples.size(); i++) {
            Sample s = samples.get(i);
            sb.append(String.format(Locale.US, "%.1f", seconds(s.time))).append(',')
                    .append(fmt("%.0f", s.rpm)).append(',')
                    .append(fmt("%+.0f", rpmChange(i))).append(',')
                    .append(fmt("%.0f", s.icpPsi)).append(',')
                    .append(fmt("%.0f", s.iprPercent)).append(',')
                    .append(fmt("%.1f", s.volts)).append('\n');
        }
        return sb.toString();
    }

    private boolean icpTooLow(boolean started) {
        return !started && !Double.isNaN(peakIcp()) && peakIcp() < MIN_START_ICP_PSI;
    }

    /** RPM swing since the previous reading that had one; NaN when there's nothing to compare. */
    private double rpmChange(int index) {
        double rpm = samples.get(index).rpm;
        if (Double.isNaN(rpm)) return Double.NaN;
        for (int i = index - 1; i >= 0; i--) {
            if (!Double.isNaN(samples.get(i).rpm)) return rpm - samples.get(i).rpm;
        }
        return Double.NaN;
    }

    private double seconds(long time) {
        return (time - startedAt) / 1000.0;
    }

    private double minRpm() {
        return extreme(s -> s.rpm > 0 ? s.rpm : Double.NaN, false);
    }

    private double maxRpm() {
        return extreme(s -> s.rpm > 0 ? s.rpm : Double.NaN, true);
    }

    private double peakIcp() {
        return extreme(s -> s.icpPsi, true);
    }

    private double peakIpr() {
        return extreme(s -> s.iprPercent, true);
    }

    private double lowestVolts() {
        return extreme(s -> s.volts, false);
    }

    /** Highest or lowest value of one reading across the crank, skipping gaps; NaN if it never answered. */
    private double extreme(ToDoubleFunction<Sample> field, boolean highest) {
        double result = Double.NaN;
        for (Sample s : samples) {
            double value = field.applyAsDouble(s);
            if (Double.isNaN(value)) continue;
            if (Double.isNaN(result) || (highest ? value > result : value < result)) result = value;
        }
        return result;
    }

    private static String fmt(String format, double value) {
        return Double.isNaN(value) ? "" : String.format(Locale.US, format, value);
    }
}
