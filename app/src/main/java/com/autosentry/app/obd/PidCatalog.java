package com.autosentry.app.obd;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every reading the dashboard can show. "Standard" entries are OBD-II Mode 01
 * PIDs read from the truck; "computed" entries (ids 0x1000+) are worked out by
 * the app from those readings. Values come out in US display units.
 *
 * Which standard PIDs a given truck actually answers is discovered at connect
 * time (see ELM327Adapter.readSupportedPids) and the editor only offers those.
 */
public final class PidCatalog {
    public static final int RPM = 0x0C;
    public static final int SPEED = 0x0D;
    public static final int COOLANT = 0x05;
    public static final int MAF = 0x10;
    public static final int FUEL_RATE = 0x5E;

    public static final int COMPUTED_INSTANT_MPG = 0x1000;
    public static final int COMPUTED_TRIP_MPG = 0x1001;
    public static final int COMPUTED_TRIP_MILES = 0x1002;
    public static final int COMPUTED_ODOMETER = 0x1003;

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
            return String.format(java.util.Locale.US, format, value);
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

    private static void std(int id, String name, String unit, int bytes, String fmt, Decoder d) {
        ALL.put(id, new Pid(id, name, unit, bytes, fmt, false, d));
    }

    private static void computed(int id, String name, String unit, String fmt) {
        ALL.put(id, new Pid(id, name, unit, 0, fmt, true, null));
    }

    static {
        std(0x0C, "Engine RPM", "rpm", 2, "%.0f", b -> word(b) / 4.0);
        std(0x0D, "Vehicle Speed", "mph", 1, "%.0f", b -> b[0] * 0.621371);
        std(0x05, "Coolant Temp", "°F", 1, "%.0f", b -> cToF(b[0] - 40));
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

        computed(COMPUTED_INSTANT_MPG, "Instant MPG", "mpg", "%.1f");
        computed(COMPUTED_TRIP_MPG, "Trip Average MPG", "mpg", "%.1f");
        computed(COMPUTED_TRIP_MILES, "Trip Distance", "mi", "%.1f");
        computed(COMPUTED_ODOMETER, "Odometer", "mi", "%.1f");
    }

    private PidCatalog() {}

    public static Pid get(int id) {
        return ALL.get(id);
    }

    public static Collection<Pid> all() {
        return ALL.values();
    }

    /**
     * What the editor should offer: computed items always, plus standard ones
     * the truck has actually answered. Before the first successful scan
     * {@code supported} is empty, so only the computed items are offered —
     * offering unconfirmed PIDs just leads to tiles stuck on "--" forever.
     */
    public static List<Pid> available(java.util.Set<Integer> supported) {
        List<Pid> result = new ArrayList<>();
        for (Pid pid : ALL.values()) {
            if (pid.computed || supported.contains(pid.id)) result.add(pid);
        }
        return result;
    }
}
