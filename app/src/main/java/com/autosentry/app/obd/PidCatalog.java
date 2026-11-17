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
    /** Manifold pressure above outside air pressure; see TrackingService for where each half comes from. */
    public static final int COMPUTED_BOOST = 0x1005;

    /** Ford enhanced (Mode 22) readings on the 7.3L's J1850 PWM bus; not standard Mode 01 ids. */
    public static final int FORD_ICP = 0x2000;
    public static final int FORD_IPR = 0x2001;
    /**
     * Experimental Ford readings (one published source each, see docs/FORD_73_PIDS_AND_KOEO.md);
     * absolute pressures in psia, so boost is MAP - BARO. Check on the truck before trusting.
     */
    public static final int FORD_MAP = 0x2002;
    public static final int FORD_BARO = 0x2003;
    public static final int FORD_EBP = 0x2004;
    public static final int FORD_FPW = 0x2005;
    public static final int FORD_MFDES = 0x2006;
    public static final int FORD_TFT = 0x2007;

    /**
     * How far a reading can be trusted. Shown next to every reading in the picker and its
     * detail sheet, so an unproven number is never mistaken for a proven one. Nothing is
     * CONFIRMED until its raw replies have been captured on this truck and compared with a
     * known-good value (docs/PROVENANCE.md says how to promote one).
     */
    public enum Trust {
        CONFIRMED("Confirmed on this truck", "confirmed"),
        STANDARD("Defined by the OBD-II standard (SAE J1979). Not yet compared on this truck", "standard"),
        PUBLISHED("Ford-specific, published by several independent sources. Not yet confirmed on this truck", "published"),
        UNVERIFIED("Ford-specific, one public source only. Treat the number as a guess until checked", "unverified"),
        CALCULATED("Worked out by AutoSentry from other readings", "calculated");

        public final String explanation;
        public final String shortLabel;

        Trust(String explanation, String shortLabel) {
            this.explanation = explanation;
            this.shortLabel = shortLabel;
        }
    }

    /** Readings that change slowly are asked for this often instead of on every pass. */
    static final long SLOW_MS = 2000L;
    /** Ford readings need two header changes per batch, so they share one batch a second. */
    static final long FORD_MS = 1000L;

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
        /** Four-hex Mode 22 PID read from the PCM and decoded by {@link #decode}; null for Mode 01 readings. */
        public String fordCode;
        public Trust trust = Trust.STANDARD;
        /** Where the request and the formula come from. */
        public String source = "SAE J1979";
        /** The formula in words, A and B being the first and second data bytes. */
        public String formula = "";
        /** How often to ask, in ms; 0 means on every pass. */
        public long pollMs = 0;

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
            return fordCode != null;
        }

        /** What is sent to the truck, e.g. "01 0C" or "22 1446 to the PCM (header C4 10 F1)"; null when calculated. */
        public String request() {
            if (computed) return null;
            if (fordCode != null) {
                return "22 " + fordCode.substring(0, 2) + " " + fordCode.substring(2) + " to the PCM (header C4 10 F1)";
            }
            return String.format(java.util.Locale.US, "01 %02X", id);
        }

        Pid slow() {
            pollMs = SLOW_MS;
            return this;
        }

        Pid formula(String text) {
            formula = text;
            return this;
        }

        Pid from(Trust trust, String source) {
            this.trust = trust;
            this.source = source;
            return this;
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

    /**
     * A Ford Mode 22 reading, read straight from the PCM. Adding one is this single line plus
     * a row in docs/PROVENANCE.md: tools/obd_probe.py --catalog prints both from a capture.
     */
    private static Pid ford(int id, String code, int bytes, String name, String unit, String fmt, Decoder d) {
        Pid pid = new Pid(id, name, unit, bytes, fmt, false, d);
        pid.fordCode = code;
        pid.pollMs = FORD_MS;
        pid.trust = Trust.UNVERIFIED;
        ALL.put(id, pid);
        return pid;
    }

    private static Pid computed(int id, String name, String unit, String fmt) {
        Pid pid = new Pid(id, name, unit, 0, fmt, true, null);
        pid.trust = Trust.CALCULATED;
        pid.source = "AutoSentry";
        ALL.put(id, pid);
        return pid;
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

        // Slow movers: asked every couple of seconds so the fast ones get the bus.
        for (int id : new int[]{0x0F, 0x2F, 0x5C, 0x33, 0x46, 0x42, 0x1F, 0x05, 0x01, 0x21, 0x30, 0x31, 0x4E, 0x63}) {
            ALL.get(id).slow();
        }

        // Ford 7.3L readings (J1850 PWM only). Sources and raw captures: docs/PROVENANCE.md.
        String scanGauge = "ScanGauge X-Gauge codes for the 7.3L (public forum posts); github.com/shulltronics/obd_trucker";
        String oneSource = "github.com/shulltronics/obd_trucker PIDs.py, one author";
        ford(FORD_ICP, "1446", 2, "Injection Control Pressure", "psi", "%.0f", b -> word(b) * 0.56).range(0, 4000)
                .formula("(A*256+B) x 0.56 psi (sources say 0.56 to 0.57)").from(Trust.PUBLISHED, scanGauge);
        ford(FORD_IPR, "1434", 1, "IPR Duty Cycle", "%", "%.0f", b -> b[0] * 100.0 / 255.0).range(0, 100)
                .formula("A x 100 / 255 %").from(Trust.PUBLISHED, scanGauge);

        ford(FORD_MAP, "1440", 2, "MAP (unverified)", "psi", "%.1f", b -> word(b) * 0.03625).range(0, 50)
                .formula("(A*256+B) x 0.03625 psi absolute").from(Trust.UNVERIFIED, oneSource);
        ford(FORD_BARO, "1442", 2, "Barometric (unverified)", "psi", "%.1f", b -> word(b) * 0.03625).range(0, 20)
                .formula("(A*256+B) x 0.03625 psi absolute").from(Trust.UNVERIFIED, oneSource).slow();
        ford(FORD_EBP, "1445", 2, "Exhaust Back Pressure (unverified)", "psi", "%.1f", b -> word(b) * 0.03625).range(0, 50)
                .formula("(A*256+B) x 0.03625 psi absolute").from(Trust.UNVERIFIED, oneSource);
        ford(FORD_FPW, "1410", 2, "Fuel Pulse Width (unverified)", "ms", "%.2f", b -> word(b) * 0.008).range(0, 5)
                .formula("(A*256+B) x 0.008 ms (a x 0.004 claim also exists)").from(Trust.UNVERIFIED, oneSource);
        ford(FORD_MFDES, "1412", 2, "Fuel Desired (unverified)", "mg/stroke", "%.1f", b -> word(b) / 16.0).range(0, 100)
                .formula("(A*256+B) / 16 mg per stroke").from(Trust.UNVERIFIED, oneSource);
        ford(FORD_TFT, "11BD", 2, "Trans Fluid Temp (unverified)", "°F", "%.0f", b -> word(b) * -0.0036 + 212.98).range(0, 300)
                .formula("(A*256+B) x -0.0036 + 212.98 °F, fitted on a 1996 E4OD; may not hold for the 4R100")
                .from(Trust.UNVERIFIED, "github.com/shulltronics/OBD-trucker, fitted curve").slow();

        // The 7.3L answers Ford's oil-temp PID, not the standard one; TrackingService picks.
        ALL.get(ENGINE_OIL_TEMP).formula("Standard: A - 40 °C. On the 7.3L: Ford 22 1310, (A*256+B)/100 - 40 °C")
                .from(Trust.PUBLISHED, "SAE J1979; on the 7.3L " + scanGauge);

        computed(COMPUTED_INSTANT_MPG, "Instant MPG", "mpg", "%.1f").formula("speed / fuel flow");
        computed(COMPUTED_TRIP_MPG, "Trip Average MPG", "mpg", "%.1f").formula("trip miles / trip gallons");
        computed(COMPUTED_TRIP_MILES, "Trip Distance", "mi", "%.1f").formula("speed x engine-on time");
        computed(COMPUTED_TRIP_TIME, "Trip Time", "", DURATION_FORMAT);
        computed(COMPUTED_ODOMETER, "Odometer", "mi", "%.1f").formula("the odometer you entered + miles driven since");
        computed(COMPUTED_BOOST, "Boost", "psi", "%.1f").range(-5, 35)
                .formula("manifold pressure (01 0B) minus outside air pressure: Ford 22 1442 when it answers, "
                        + "otherwise the manifold pressure seen with the engine off this key cycle");
    }

    private PidCatalog() {}

    public static Pid get(int id) {
        return ALL.get(id);
    }

    public static Collection<Pid> all() {
        return ALL.values();
    }
}
