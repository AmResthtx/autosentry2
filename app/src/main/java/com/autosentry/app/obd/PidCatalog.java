package com.autosentry.app.obd;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every reading the dashboard can show. "Standard" entries are OBD-II Mode 01
 * PIDs read from the truck; "computed" entries (ids 0x1000+) are worked out by
 * the app from those readings. Values come out in US display units.
 *
 * Which standard PIDs a given truck actually answers is discovered at connect
 * time (see ELM327Adapter.readSupportedPids); the editor labels the rest.
 */
public final class PidCatalog {
    public static final int RPM = 0x0C;
    public static final int SPEED = 0x0D;
    public static final int MAF = 0x10;
    public static final int FUEL_RATE = 0x5E;
    /** Standard Mode 01 id; on the 7.3L the value comes from Ford's enhanced PID instead. */
    public static final int ENGINE_OIL_TEMP = 0x5C;

    public static final int COMPUTED_INSTANT_MPG = 0x1000;
    public static final int COMPUTED_TRIP_MPG = 0x1001;
    public static final int COMPUTED_TRIP_MILES = 0x1002;
    public static final int COMPUTED_ODOMETER = 0x1003;
    /** Seconds since the trip started (engine on), shown as h:mm:ss. */
    public static final int COMPUTED_TRIP_TIME = 0x1004;

    /** Ford enhanced (Mode 22) readings on the 7.3L's J1850 PWM bus; not standard Mode 01 ids. */
    public static final int FORD_ICP = 0x2000;
    public static final int FORD_IPR = 0x2001;

    private static final String DURATION_FORMAT = "duration";

    public interface Decoder {
        double decode(int[] b);
    }

    public static final class Pid {
        public final int id;
        public final String name;
        public final String unit;
        public final int byteCount;
        public final String format;
        public final boolean computed;
        private final Decoder decoder;
        /** Dial scale for readings with no tolerance band; NaN when the reading has no sensible range. */
        public double displayMin = Double.NaN;
        public double displayMax = Double.NaN;

        Pid(int id, String name, String unit, int byteCount, String format, boolean computed, Decoder decoder) {
            this.id = id;
            this.name = name;
            this.unit = unit;
            this.byteCount = byteCount;
            this.format = format;
            this.computed = computed;
            this.decoder = decoder;
        }

        /** NaN when there was no answer or too few bytes. */
        public double decode(int[] data) {
            if (computed || decoder == null || data == null || data.length < byteCount) return Double.NaN;
            return decoder.decode(data);
        }

        public String formatValue(double value) {
            if (Double.isNaN(value)) return "--";
            if (DURATION_FORMAT.equals(format)) {
                long seconds = Math.round(value);
                return String.format(java.util.Locale.US, "%d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
            }
            return String.format(java.util.Locale.US, format, value);
        }

        /** Gives a reading with no tolerance band a dial scale. */
        Pid range(double min, double max) {
            displayMin = min;
            displayMax = max;
            return this;
        }

        public boolean isEnhanced() {
            return id == FORD_ICP || id == FORD_IPR;
        }

        public String label() {
            return unit.isEmpty() ? name : name + " (" + unit + ")";
        }
    }

    private static final Map<Integer, Pid> ALL = new LinkedHashMap<>();

    private static double cToF(double c) {
        return c * 9.0 / 5.0 + 32.0;
    }

    private static double word(int[] b) {
        return b[0] * 256.0 + b[1];
    }

    private static Pid std(int id, String name, String unit, int bytes, String fmt, Decoder d) {
        Pid pid = new Pid(id, name, unit, bytes, fmt, false, d);
        ALL.put(id, pid);
        return pid;
    }

    /** Ford enhanced reading: not a Mode 01 id, read by the tracking service over Mode 22. */
    private static Pid enhanced(int id, String name, String unit, String fmt) {
        Pid pid = new Pid(id, name, unit, 0, fmt, false, null);
        ALL.put(id, pid);
        return pid;
    }

    private static void computed(int id, String name, String unit, String fmt) {
        ALL.put(id, new Pid(id, name, unit, 0, fmt, true, null));
    }

    static {
        std(0x0C, "Engine RPM", "rpm", 2, "%.0f", b -> word(b) / 4.0);
        std(0x0D, "Vehicle Speed", "mph", 1, "%.0f", b -> b[0] * 0.621371);
        std(0x04, "Engine Load", "%", 1, "%.0f", b -> b[0] * 100.0 / 255.0);
        std(0x11, "Throttle Position", "%", 1, "%.0f", b -> b[0] * 100.0 / 255.0);
        std(0x49, "Accelerator Pedal", "%", 1, "%.0f", b -> b[0] * 100.0 / 255.0);
        std(0x0B, "Intake Manifold Pressure", "psi", 1, "%.1f", b -> b[0] * 0.145038);
        std(0x0F, "Intake Air Temp", "°F", 1, "%.0f", b -> cToF(b[0] - 40));
        std(0x10, "Mass Air Flow", "g/s", 2, "%.1f", b -> word(b) / 100.0);
        std(0x5E, "Fuel Rate", "gal/h", 2, "%.2f", b -> word(b) / 20.0 * 0.264172);
        std(0x0A, "Fuel Pressure", "psi", 1, "%.0f", b -> b[0] * 3 * 0.145038);
        std(0x23, "Fuel Rail Pressure", "psi", 2, "%.0f", b -> word(b) * 10 * 0.145038);
        std(0x2F, "Fuel Level", "%", 1, "%.0f", b -> b[0] * 100.0 / 255.0);
        std(0x5C, "Engine Oil Temp", "°F", 1, "%.0f", b -> cToF(b[0] - 40));
        std(0x33, "Barometric Pressure", "psi", 1, "%.1f", b -> b[0] * 0.145038);
        std(0x46, "Ambient Air Temp", "°F", 1, "%.0f", b -> cToF(b[0] - 40));
        std(0x42, "Battery / Module Voltage", "V", 2, "%.1f", b -> word(b) / 1000.0);
        std(0x1F, "Engine Run Time", "min", 2, "%.0f", b -> word(b) / 60.0);

        // More standard Mode 01 readings. The connect-time scan decides which of these the truck answers.
        std(0x05, "Coolant Temp", "°F", 1, "%.0f", b -> cToF(b[0] - 40));
        std(0x43, "Absolute Load", "%", 2, "%.0f", b -> word(b) * 100.0 / 255.0).range(0, 100);
        std(0x45, "Relative Throttle", "%", 1, "%.0f", b -> b[0] * 100.0 / 255.0).range(0, 100);
        std(0x4A, "Accelerator Pedal E", "%", 1, "%.0f", b -> b[0] * 100.0 / 255.0).range(0, 100);
        std(0x5A, "Relative Accel Pedal", "%", 1, "%.0f", b -> b[0] * 100.0 / 255.0).range(0, 100);
        std(0x4C, "Commanded Throttle", "%", 1, "%.0f", b -> b[0] * 100.0 / 255.0).range(0, 100);
        std(0x2C, "Commanded EGR", "%", 1, "%.0f", b -> b[0] * 100.0 / 255.0).range(0, 100);
        std(0x2D, "EGR Error", "%", 1, "%.0f", b -> b[0] * 100.0 / 128.0 - 100.0).range(-100, 100);
        std(0x5D, "Injection Timing", "°", 2, "%.1f", b -> word(b) / 128.0 - 210.0).range(-20, 40);
        std(0x61, "Driver Demand Torque", "%", 1, "%.0f", b -> b[0] - 125.0).range(0, 100);
        std(0x62, "Actual Torque", "%", 1, "%.0f", b -> b[0] - 125.0).range(0, 100);
        std(0x63, "Reference Torque", "Nm", 2, "%.0f", b -> word(b));
        std(0x01, "Trouble Code Count", "", 4, "%.0f", b -> b[0] & 0x7F);
        std(0x21, "Distance With Check Engine On", "mi", 2, "%.0f", b -> word(b) * 0.621371);
        std(0x30, "Warm-ups Since Codes Cleared", "", 1, "%.0f", b -> b[0]);
        std(0x31, "Distance Since Codes Cleared", "mi", 2, "%.0f", b -> word(b) * 0.621371);
        std(0x4E, "Time Since Codes Cleared", "min", 2, "%.0f", b -> word(b));

        // Ford 7.3L enhanced readings (J1850 PWM only); the decoders live in ELM327Adapter.
        enhanced(FORD_ICP, "Injection Control Pressure", "psi", "%.0f").range(0, 4000);
        enhanced(FORD_IPR, "IPR Duty Cycle", "%", "%.0f").range(0, 100);

        computed(COMPUTED_INSTANT_MPG, "Instant MPG", "mpg", "%.1f");
        computed(COMPUTED_TRIP_MPG, "Trip Average MPG", "mpg", "%.1f");
        computed(COMPUTED_TRIP_MILES, "Trip Distance", "mi", "%.1f");
        computed(COMPUTED_TRIP_TIME, "Trip Time", "", DURATION_FORMAT);
        computed(COMPUTED_ODOMETER, "Odometer", "mi", "%.1f");
    }

    private PidCatalog() {}

    public static Pid get(int id) {
        return ALL.get(id);
    }

    public static Collection<Pid> all() {
        return ALL.values();
    }
}
