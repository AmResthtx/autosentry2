package com.autosentry.app.obd;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The in-app version of tools/obd_probe.py: finds out what this truck will
 * actually talk about, instead of guessing from forum posts. Reports the
 * adapter, the bus, every module that answers, every standard reading with
 * its live value, freeze frame / VIN / trouble-code support, the Ford
 * enhanced PIDs AutoSentry knows of, and a sweep of the PCM's Mode 22 space.
 *
 * Every request here only reads (Modes 01, 02, 03, 07, 09, 22). The scan
 * changes adapter headers along the way, so the caller must reconnect
 * afterwards, which resets the adapter with ATZ.
 */
public final class TruckProbe {

    public interface Progress {
        void update(String step);
    }

    public interface CancelCheck {
        boolean cancelled();
    }

    public static final class Result {
        public final String report;
        /** True when the adapter dropped mid-scan; the report is partial. */
        public final boolean linkLost;

        Result(String report, boolean linkLost) {
            this.report = report;
            this.linkLost = linkLost;
        }
    }

    private static final long AT_MS = 2500L;
    private static final long QUERY_MS = 5000L;
    private static final long BUS_WAKE_MS = 15000L;
    private static final long SWEEP_MS = 1500L;

    /** Ford Mode 22 PIDs to try by name: the one AutoSentry reads, plus the candidates from tools/obd_probe.py. */
    private static final String[][] FORD_CANDIDATES = {
            {"194F", "Engine oil temp (AutoSentry reads this)"},
            {"1440", "ICP, forum guess"},
            {"1442", "ICP, forum guess 2"},
            {"1130", "IPR duty cycle, forum guess"},
            {"F446", "Ambient air temp (6.7L list)"},
            {"F45C", "Engine oil temp (6.7L list)"},
            {"F42F", "Fuel level (6.7L list)"},
            {"1E1C", "Transmission fluid temp (6.7L list)"},
    };

    /**
     * PCM Mode 22 ranges to sweep: the neighborhoods of every Ford PID
     * AutoSentry already knows of (1130, 1440/1442, 194F, 1E1C). Widen them
     * if the truck answers near an edge.
     */
    private static final int[][] SWEEP_RANGES = {
            {0x1100, 0x14FF}, {0x1600, 0x16FF}, {0x1900, 0x19FF}, {0x1E00, 0x1EFF},
    };

    private TruckProbe() {}

    public static Result run(ELM327Adapter adapter, String phoneInfo, Progress progress, CancelCheck cancel) {
        StringBuilder r = new StringBuilder();
        long started = System.currentTimeMillis();
        r.append("AUTOSENTRY TRUCK SYSTEMS SCAN\n")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(started))).append('\n')
                .append("Phone: ").append(phoneInfo).append('\n')
                .append("Every request in this scan only reads. Nothing is written to the truck.\n");
        boolean linkLost = false;
        try {
            scan(adapter, r, progress, cancel);
        } catch (IOException e) {
            linkLost = true;
            r.append("\n!! Lost the adapter mid-scan (").append(e.getMessage()).append("). Everything above is real; the rest didn't run.\n");
        }
        if (cancel.cancelled()) r.append("\nStopped early by you.\n");
        long seconds = (System.currentTimeMillis() - started) / 1000;
        r.append(String.format(Locale.US, "\nScan took %dm %02ds.\n", seconds / 60, seconds % 60));
        return new Result(r.toString(), linkLost);
    }

    private static void scan(ELM327Adapter a, StringBuilder r, Progress progress, CancelCheck cancel) throws IOException {
        // ---- Adapter ----
        progress.update("Asking the adapter what it is");
        section(r, "ADAPTER");
        line(r, "Chip", firstLine(a.rawCommand("ATI", AT_MS)));
        line(r, "Device", firstLine(a.rawCommand("AT@1", AT_MS)));
        String stn = firstLine(a.rawCommand("STI", AT_MS));
        line(r, "STN firmware", stn.equals("?") || stn.isEmpty() ? "none (not an OBDLink/STN chip)" : stn);
        line(r, "Voltage at OBD port", firstLine(a.rawCommand("ATRV", AT_MS)));

        // ---- Bus ----
        progress.update("Waking the truck's bus");
        a.rawCommand("ATH0", AT_MS);
        String wake = a.rawCommand("0100", BUS_WAKE_MS);
        section(r, "BUS");
        line(r, "Protocol", firstLine(a.rawCommand("ATDP", AT_MS)));
        String proto = protocolNumber(a.rawCommand("ATDPN", AT_MS));
        boolean pwm = proto.equals("1");
        boolean can11 = proto.equals("6") || proto.equals("8");
        line(r, "Protocol number", proto + (pwm ? " (J1850 PWM, the 7.3L's bus)" : ""));
        if (DTCReader.splitMessages(wake).isEmpty()) {
            r.append("  The truck didn't answer the first request: ").append(oneLine(wake))
                    .append("\n  Turn the key on and run the scan again.\n");
            return;
        }
        String pcmHeader = pwm ? ELM327Adapter.PCM_HEADER : (can11 ? "7E0" : null);
        String obdHeader = pwm ? ELM327Adapter.OBD_HEADER : (can11 ? "7DF" : null);
        if (cancel.cancelled()) return;

        // ---- Standard readings ----
        progress.update("Reading every standard PID");
        Set<Integer> supported = a.readSupportedPids();
        TreeSet<Integer> readable = new TreeSet<>();
        for (int pid : supported) if (pid % 0x20 != 0) readable.add(pid); // skip the bitmap pages
        section(r, "STANDARD READINGS (MODE 01): " + readable.size() + " supported");
        for (int pid : readable) {
            if (cancel.cancelled()) return;
            r.append(String.format(Locale.US, "  %02X  ", pid)).append(describePid(pid, a.readPid(pid))).append('\n');
        }

        // ---- Freeze frame, VIN, trouble codes ----
        progress.update("Checking freeze frame, VIN, and code support");
        section(r, "OTHER STANDARD SERVICES");
        String freeze = a.rawCommand("020200", QUERY_MS);
        String freezeCode = parseFreezeFrameCode(freeze);
        line(r, "Freeze frame (Mode 02)", freezeCode != null
                ? "answers; saved for " + freezeCode
                : (answered(freeze, "42") ? "answers; nothing saved" : "no answer: " + oneLine(freeze)));
        String vinInfo = a.rawCommand("0900", QUERY_MS);
        String vin = parseVin(a.rawCommand("0902", 8000L));
        line(r, "Vehicle info (Mode 09)", answered(vinInfo, "49") ? "answers" : "no answer: " + oneLine(vinInfo));
        line(r, "VIN", vin != null ? vin : "not reported");
        for (DTCReader.Mode mode : DTCReader.Mode.values()) {
            String reply = a.requestTroubleCodes(mode);
            String header = String.format(Locale.US, "%02X", mode.request + 0x40);
            List<String> codes = DTCReader.parseCodes(reply, mode);
            String label = mode.label + " codes (Mode " + String.format(Locale.US, "%02X", mode.request) + ")";
            if (answered(reply, header)) {
                line(r, label, "answers; " + (codes.isEmpty() ? "none" : String.join(", ", codes)));
            } else {
                line(r, label, "no answer: " + oneLine(reply));
            }
        }
        if (cancel.cancelled()) return;

        // ---- Ford enhanced PIDs by name ----
        progress.update("Trying Ford enhanced PIDs");
        section(r, "FORD ENHANCED (MODE 22): KNOWN AND CANDIDATE PIDS");
        for (String[] candidate : FORD_CANDIDATES) {
            if (cancel.cancelled()) return;
            r.append("  ").append(candidate[0]).append("  ").append(candidate[1]).append(": ")
                    .append(tryFordPid(a, candidate[0], pcmHeader, obdHeader)).append('\n');
        }

        // ---- Who's on the bus ----
        progress.update("Listing modules that answer");
        section(r, "MODULES THAT ANSWER STANDARD OBD-II");
        a.rawCommand("ATH1", AT_MS);
        Map<String, String> responders = new LinkedHashMap<>();
        for (String message : DTCReader.splitMessages(a.rawCommand("0100", QUERY_MS))) {
            String source = sourceAddress(message, proto);
            if (source != null) responders.put(source, message);
        }
        if (responders.isEmpty()) r.append("  none (with headers on)\n");
        for (Map.Entry<String, String> e : responders.entrySet()) {
            r.append("  ").append(e.getKey()).append("  ").append(moduleName(e.getKey(), proto))
                    .append("  [").append(e.getValue()).append("]\n");
        }

        // The two sweeps are thousands of requests; the report has their results, so keep them out of the debug log.
        ELM327Adapter.Trace trace = a.getTrace();
        a.setTrace(null);
        try {
            sweeps(a, r, progress, cancel, proto, pwm, pcmHeader, obdHeader);
        } finally {
            a.setTrace(trace);
        }
    }

    private static void sweeps(ELM327Adapter a, StringBuilder r, Progress progress, CancelCheck cancel,
                               String proto, boolean pwm, String pcmHeader, String obdHeader) throws IOException {
        if (pwm) {
            section(r, "FORD MODULE SWEEP (every J1850 address, Mode 22 read)");
            Map<String, String> modules = new LinkedHashMap<>();
            try {
                for (int target = 0x00; target <= 0xFF; target++) {
                    if (cancel.cancelled()) break;
                    if (target == 0xF1) continue; // that's us, the scan tool
                    progress.update(String.format(Locale.US, "Module sweep: address %02X of FF", target));
                    a.rawCommand(String.format(Locale.US, "ATSHC4%02XF1", target), AT_MS);
                    for (String message : DTCReader.splitMessages(a.rawCommand("221100", SWEEP_MS))) {
                        String source = sourceAddress(message, proto);
                        if (source != null && !modules.containsKey(source)) {
                            modules.put(source, String.format(Locale.US, "asked %02X, reply [%s]", target, message));
                        }
                    }
                }
            } finally {
                a.rawCommand("ATSH" + obdHeader, AT_MS);
            }
            if (modules.isEmpty()) r.append("  nothing answered a direct request\n");
            for (Map.Entry<String, String> e : modules.entrySet()) {
                r.append("  ").append(e.getKey()).append("  ").append(moduleName(e.getKey(), proto))
                        .append("  ").append(e.getValue()).append('\n');
            }
            r.append("  (A reply starting 7F is a module saying \"I'm here, but not that PID.\" It still proves the module exists.)\n");
        }
        a.rawCommand("ATH0", AT_MS);
        if (cancel.cancelled()) return;

        // ---- PCM Mode 22 sweep ----
        if (pcmHeader == null) {
            section(r, "PCM ENHANCED SWEEP");
            r.append("  Skipped: only runs on J1850 PWM or 11-bit CAN.\n");
            return;
        }
        int total = 0;
        for (int[] range : SWEEP_RANGES) total += range[1] - range[0] + 1;
        StringBuilder ranges = new StringBuilder();
        for (int[] range : SWEEP_RANGES) {
            if (ranges.length() > 0) ranges.append(", ");
            ranges.append(String.format(Locale.US, "%04X-%04X", range[0], range[1]));
        }
        section(r, "PCM ENHANCED SWEEP (MODE 22, sent straight to the PCM): " + ranges);
        List<String> hits = new ArrayList<>();
        int done = 0;
        a.rawCommand("ATSH" + pcmHeader, AT_MS);
        try {
            sweep:
            for (int[] range : SWEEP_RANGES) {
                for (int pid = range[0]; pid <= range[1]; pid++) {
                    if (cancel.cancelled()) break sweep;
                    if (done % 16 == 0) progress.update("PCM sweep: " + done + " of " + total);
                    String hex = String.format(Locale.US, "%04X", pid);
                    int[] data = ELM327Adapter.parseReply(a.rawCommand("22" + hex, SWEEP_MS), "62" + hex);
                    if (data != null) hits.add(hex + " = " + hexBytes(data));
                    done++;
                }
            }
        } finally {
            a.rawCommand("ATSH" + obdHeader, AT_MS);
        }
        r.append("  ").append(hits.size()).append(" of ").append(done).append(" PIDs answered")
                .append(done < total ? " (sweep stopped at " + done + " of " + total + ")" : "").append('\n');
        for (String hit : hits) r.append("  ").append(hit).append('\n');
    }

    /** "answered (broadcast)", "answered only when sent straight to the PCM", a refusal, or no answer. */
    private static String tryFordPid(ELM327Adapter a, String pid, String pcmHeader, String obdHeader) throws IOException {
        String reply = a.rawCommand("22" + pid, QUERY_MS);
        int[] data = ELM327Adapter.parseReply(reply, "62" + pid);
        if (data != null) return "answers on the standard broadcast = " + hexBytes(data);
        String broadcast = oneLine(reply);
        if (pcmHeader == null) return "no answer: " + broadcast;
        a.rawCommand("ATSH" + pcmHeader, AT_MS);
        String direct;
        try {
            direct = a.rawCommand("22" + pid, QUERY_MS);
        } finally {
            a.rawCommand("ATSH" + obdHeader, AT_MS);
        }
        data = ELM327Adapter.parseReply(direct, "62" + pid);
        if (data != null) return "answers only when sent straight to the PCM = " + hexBytes(data);
        return "no answer (broadcast: " + broadcast + "; direct: " + oneLine(direct) + ")";
    }

    // ---------------------------------------------------------------- parsing helpers

    /** "1" for J1850 PWM etc., from ATDPN's reply ("A1" when auto-detected). */
    static String protocolNumber(String reply) {
        for (String raw : (reply == null ? "" : reply).split("[\\r\\n]+")) {
            String line = raw.replace(">", "").trim().toUpperCase(Locale.US);
            if (line.matches("A?[0-9A-C]")) return line.substring(line.length() - 1);
        }
        return "?";
    }

    /** Sender of a headers-on reply: J1850/ISO byte 3, an 11-bit CAN ID, or a 29-bit CAN ID's last byte. */
    static String sourceAddress(String message, String proto) {
        switch (proto) {
            case "6": case "8":
                return message.length() >= 3 ? message.substring(0, 3) : null;
            case "7": case "9":
                return message.length() >= 8 ? message.substring(6, 8) : null;
            default:
                return message.length() >= 6 ? message.substring(4, 6) : null;
        }
    }

    /** Names only what SAE address ranges make certain; everything else stays a plain address. */
    static String moduleName(String source, String proto) {
        if (proto.equals("6") || proto.equals("8")) {
            if (source.equals("7E8")) return "Engine computer (PCM)";
            if (source.equals("7E9")) return "Transmission computer";
            return "Module";
        }
        try {
            int address = Integer.parseInt(source, 16);
            if (address >= 0x10 && address <= 0x17) return "Engine computer (PCM)";
            if (address >= 0x18 && address <= 0x1F) return "Transmission computer";
        } catch (NumberFormatException ignored) {
        }
        return "Module";
    }

    /** The code a freeze frame was saved for ("42 02 00 XX XX"), or null when none or no answer. */
    static String parseFreezeFrameCode(String reply) {
        for (String message : DTCReader.splitMessages(reply == null ? "" : reply)) {
            if (message.startsWith("420200") && message.length() >= 10) {
                int a = Integer.parseInt(message.substring(6, 8), 16);
                int b = Integer.parseInt(message.substring(8, 10), 16);
                if (a != 0 || b != 0) return DTCReader.decode(a, b);
            }
        }
        return null;
    }

    /**
     * VIN from a Mode 09 PID 02 reply. Each J1850 frame is "4902" + a frame
     * number + 4 bytes; CAN is "4902" + an item count + 17 bytes in one block.
     * Either way: drop "4902" and one byte, keep the ASCII, take the last 17
     * VIN characters.
     */
    static String parseVin(String reply) {
        StringBuilder chars = new StringBuilder();
        for (String message : DTCReader.splitMessages(reply == null ? "" : reply)) {
            if (!message.startsWith("4902") || message.length() < 6) continue;
            for (int i = 6; i + 2 <= message.length(); i += 2) {
                char c = (char) Integer.parseInt(message.substring(i, i + 2), 16);
                if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z')) chars.append(c);
            }
        }
        return chars.length() >= 17 ? chars.substring(chars.length() - 17) : null;
    }

    private static boolean answered(String reply, String header) {
        for (String message : DTCReader.splitMessages(reply == null ? "" : reply)) {
            if (message.startsWith(header)) return true;
        }
        return false;
    }

    static String describePid(int pid, int[] data) {
        String name = pidName(pid);
        if (data == null) return name + ": advertised, but no answer";
        if (pid == 0x01) {
            DTCReader.MilStatus mil = DTCReader.parseMilStatus(data);
            return name + ": light " + (mil.milOn ? "ON" : "off") + ", " + mil.storedCount
                    + " stored code(s)  [raw " + hexBytes(data) + "]";
        }
        PidCatalog.Pid def = PidCatalog.get(pid);
        double value = def == null ? Double.NaN : def.decode(data);
        String shown = Double.isNaN(value) ? "" : def.formatValue(value) + (def.unit.isEmpty() ? "" : " " + def.unit) + "  ";
        return name + ": " + shown + "[raw " + hexBytes(data) + "]";
    }

    /** SAE J1979 Mode 01 names. */
    static String pidName(int pid) {
        String name = PID_NAMES.get(pid);
        return name != null ? name : String.format(Locale.US, "PID %02X", pid);
    }

    private static final Map<Integer, String> PID_NAMES = new LinkedHashMap<>();

    static {
        String[] names = {
                /*01*/ "Monitor status / check engine light", "Freeze frame trouble code", "Fuel system status",
                "Calculated engine load", "Coolant temperature", "Short-term fuel trim, bank 1", "Long-term fuel trim, bank 1",
                "Short-term fuel trim, bank 2", "Long-term fuel trim, bank 2", "Fuel pressure", "Intake manifold pressure",
                "Engine RPM", "Vehicle speed", "Timing advance", "Intake air temperature", "Mass air flow",
                /*11*/ "Throttle position", "Secondary air status", "Oxygen sensors present", "O2 sensor 1", "O2 sensor 2",
                "O2 sensor 3", "O2 sensor 4", "O2 sensor 5", "O2 sensor 6", "O2 sensor 7", "O2 sensor 8",
                "OBD standard this truck meets", "Oxygen sensors present (4 banks)", "Auxiliary input (PTO) status",
                "Run time since engine start",
                /*20*/ null,
                /*21*/ "Distance driven with check engine light on", "Fuel rail pressure (relative)", "Fuel rail pressure",
                "O2 sensor 1 (wide range)", "O2 sensor 2 (wide range)", "O2 sensor 3 (wide range)", "O2 sensor 4 (wide range)",
                "O2 sensor 5 (wide range)", "O2 sensor 6 (wide range)", "O2 sensor 7 (wide range)", "O2 sensor 8 (wide range)",
                "Commanded EGR", "EGR error", "Commanded evap purge", "Fuel tank level", "Warm-ups since codes cleared",
                /*31*/ "Distance since codes cleared", "Evap system vapor pressure", "Barometric pressure",
                "O2 sensor 1 (current)", "O2 sensor 2 (current)", "O2 sensor 3 (current)", "O2 sensor 4 (current)",
                "O2 sensor 5 (current)", "O2 sensor 6 (current)", "O2 sensor 7 (current)", "O2 sensor 8 (current)",
                "Catalyst temp, bank 1 sensor 1", "Catalyst temp, bank 2 sensor 1", "Catalyst temp, bank 1 sensor 2",
                "Catalyst temp, bank 2 sensor 2",
                /*40*/ null,
                /*41*/ "Monitor status this drive", "Control module voltage", "Absolute load", "Commanded air-fuel ratio",
                "Relative throttle position", "Ambient air temperature", "Absolute throttle position B",
                "Absolute throttle position C", "Accelerator pedal position D", "Accelerator pedal position E",
                "Accelerator pedal position F", "Commanded throttle actuator", "Time run with check engine light on",
                "Time since codes cleared", "Maximum sensor values",
                /*50*/ "Maximum mass air flow", "Fuel type", "Ethanol fuel %", "Absolute evap pressure", "Evap pressure",
                "Short-term secondary O2 trim, banks 1/3", "Long-term secondary O2 trim, banks 1/3",
                "Short-term secondary O2 trim, banks 2/4", "Long-term secondary O2 trim, banks 2/4",
                "Fuel rail absolute pressure", "Relative accelerator pedal position", "Hybrid battery remaining life",
                "Engine oil temperature", "Fuel injection timing", "Engine fuel rate", "Emission requirements",
                /*60*/ null,
                /*61*/ "Driver's demand torque %", "Actual engine torque %", "Engine reference torque",
                "Engine torque data", "Auxiliary inputs/outputs", "Mass air flow sensor", "Coolant temperature (2 sensors)",
                "Intake air temperature sensors", "EGR data", "Diesel intake air flow control", "EGR temperature",
                "Throttle actuator control", "Fuel pressure control", "Injection pressure control",
                "Turbo compressor inlet pressure",
                /*70*/ "Boost pressure control", "Variable geometry turbo control", "Wastegate control", "Exhaust pressure",
                "Turbo RPM", "Turbo temperature A", "Turbo temperature B", "Charge air cooler temperature",
                "Exhaust gas temperature, bank 1", "Exhaust gas temperature, bank 2", "Diesel particulate filter",
                "Diesel particulate filter, bank 2", "Diesel particulate filter temperature", "NOx NTE control area status",
                "PM NTE control area status", "Engine run time",
        };
        for (int i = 0; i < names.length; i++) {
            if (names[i] != null) PID_NAMES.put(i + 1, names[i]);
        }
    }

    // ---------------------------------------------------------------- formatting helpers

    private static void section(StringBuilder r, String title) {
        r.append('\n').append(title).append('\n');
    }

    private static void line(StringBuilder r, String label, String value) {
        r.append("  ").append(label).append(": ").append(value).append('\n');
    }

    private static String firstLine(String reply) {
        for (String raw : (reply == null ? "" : reply).split("[\\r\\n]+")) {
            String line = raw.replace(">", "").trim();
            if (!line.isEmpty()) return line;
        }
        return "";
    }

    /** A reply squeezed onto one line for the report, e.g. "NO DATA" or "SEARCHING... | UNABLE TO CONNECT". */
    public static String oneLine(String reply) {
        StringBuilder sb = new StringBuilder();
        for (String raw : (reply == null ? "" : reply).split("[\\r\\n]+")) {
            String line = raw.replace(">", "").trim();
            if (line.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" | ");
            sb.append(line);
        }
        return sb.length() == 0 ? "(silence)" : sb.toString();
    }

    static String hexBytes(int[] data) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "%02X", data[i]));
        }
        return sb.toString();
    }
}
