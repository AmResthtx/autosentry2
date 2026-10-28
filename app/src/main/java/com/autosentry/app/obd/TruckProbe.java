package com.autosentry.app.obd;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The in-app version of tools/obd_probe.py: finds out what this truck
 * actually talks about, instead of guessing from forum posts.
 *
 * Two modes. Listen-only (the default) sends the truck nothing: the adapter
 * monitors the bus and the report shows who broadcasts what, and how often.
 * Active also asks: every module that answers, every standard reading with
 * its live value and response time, freeze frame / VIN / trouble codes, the
 * Ford enhanced PIDs, and a sweep of the PCM's Mode 22 space.
 *
 * Either way a {@link ReadOnlyGuard} sits on the adapter for the whole scan,
 * so only reads can go out, and the report lists every frame that did. The
 * scan changes adapter settings along the way, so the caller must
 * reinitialize the adapter afterwards (ATZ).
 */
public final class TruckProbe {

    public interface Progress {
        void update(String step);
    }

    public interface CancelCheck {
        boolean cancelled();
    }

    public static final class Options {
        /** Send read requests to the truck. Off is listen-only: nothing goes out on the bus. */
        public boolean active = false;
        /** Put the VIN in the report. Hidden by default. */
        public boolean includeVin = false;
        /** How long listen-only mode listens. */
        public long listenMs = 30_000L;
        // Request pacing; tests shorten it.
        long minIntervalMs = 100L;
        long backoffMs = 250L;
    }

    public static final class Result {
        /** The report people read. */
        public final String report;
        /** The same report as JSON. */
        public final String json;
        /** True when the adapter dropped mid-scan; the report is partial. */
        public final boolean linkLost;

        Result(String report, String json, boolean linkLost) {
            this.report = report;
            this.json = json;
            this.linkLost = linkLost;
        }
    }

    /** What the scan learned, for the summary and the data-flow comparison. */
    private static final class Findings {
        String protocol = "?";
        String protocolName = "unknown";
        final Set<String> modules = new LinkedHashSet<>();
        final TreeSet<Integer> supported = new TreeSet<>();
        final TreeSet<Integer> answered = new TreeSet<>();
        final Map<String, Boolean> fordAnswered = new LinkedHashMap<>();
        final List<String> codes = new ArrayList<>();
        final List<String> problems = new ArrayList<>();
        boolean readingsChecked = false;
        int streams = 0;
    }

    private static final long AT_MS = 2500L;
    private static final long QUERY_MS = 5000L;
    private static final long BUS_WAKE_MS = 15000L;
    private static final long SWEEP_MS = 1500L;

    /** Ford Mode 22 PIDs to try by name beyond the ones AutoSentry reads. */
    private static final String[][] FORD_CANDIDATES = {
            {"194F", "Engine oil temp, older guess"},
            {"1130", "IPR duty cycle, forum guess"},
            {"F446", "Ambient air temp (6.7L list)"},
            {"F45C", "Engine oil temp (6.7L list)"},
            {"F42F", "Fuel level (6.7L list)"},
            {"1E1C", "Transmission fluid temp (6.7L list)"},
    };

    /**
     * PCM Mode 22 ranges to sweep: the neighborhoods of every Ford PID
     * AutoSentry already knows of (1130, 1310, 14xx, 194F, 1E1C). Widen them
     * if the truck answers near an edge.
     */
    private static final int[][] SWEEP_RANGES = {
            {0x1100, 0x14FF}, {0x1600, 0x16FF}, {0x1900, 0x19FF}, {0x1E00, 0x1EFF},
    };

    private TruckProbe() {}

    /** Ford Mode 22 PIDs AutoSentry reads, with what it calls them. */
    static Map<String, String> appFordPids() {
        Map<String, String> pids = new LinkedHashMap<>();
        pids.put("1310", "Engine oil temp");
        pids.put("1446", "Injection control pressure");
        pids.put("1434", "IPR duty cycle");
        for (PidCatalog.Pid p : PidCatalog.all()) {
            if (p.fordCode != null) pids.put(p.fordCode, p.name);
        }
        return pids;
    }

    /** Standard Mode 01 PIDs AutoSentry can decode and show. */
    static TreeSet<Integer> appMode01Pids() {
        TreeSet<Integer> pids = new TreeSet<>();
        for (PidCatalog.Pid p : PidCatalog.all()) {
            if (!p.computed && !p.isEnhanced() && p.id <= 0xFF) pids.add(p.id);
        }
        return pids;
    }

    public static Result run(ELM327Adapter adapter, String tabletInfo, Options options, Progress progress, CancelCheck cancel) {
        ScanReport r = new ScanReport();
        long started = System.currentTimeMillis();
        r.title("AUTOSENTRY TRUCK SYSTEMS SCAN");
        r.title(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(started)));
        r.title("Tablet: " + tabletInfo);
        r.title(options.active
                ? "Active scan: sends read requests only. A read-only lock refuses anything else."
                : "Listen-only scan: nothing is sent to the truck.");
        r.put("tool", "AutoSentry truck systems scan");
        r.put("started", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(new Date(started)));
        r.put("tablet", tabletInfo);
        r.put("mode", options.active ? "active" : "listen-only");

        ReadOnlyGuard guard = new ReadOnlyGuard(!options.active, options.minIntervalMs, options.backoffMs,
                ReadOnlyGuard.SYSTEM_CLOCK);
        Findings f = new Findings();
        String outcome = "complete";
        boolean linkLost = false;
        ReadOnlyGuard previous = adapter.getGuard();
        adapter.setGuard(guard);
        try {
            if (options.active) {
                scan(adapter, r, f, guard, options, progress, cancel);
            } else {
                listen(adapter, r, f, options, progress, cancel);
            }
            if (cancel.cancelled()) outcome = "stopped early by you";
        } catch (ReadOnlyGuard.Blocked e) {
            outcome = "stopped: the read-only lock " + e.getMessage();
        } catch (ReadOnlyGuard.Abort e) {
            outcome = "stopped safely: " + e.getMessage();
        } catch (IOException e) {
            linkLost = true;
            outcome = "the adapter dropped mid-scan (" + e.getMessage() + "); what's above is real, the rest didn't run";
        } finally {
            adapter.setGuard(previous);
        }

        dataFlow(r, f, options);
        problems(r, f, guard, outcome);
        commandsSent(r, guard, options);

        long seconds = (System.currentTimeMillis() - started) / 1000;
        r.summarySection();
        r.line("Mode", options.active ? "active (read requests only)" : "listen-only (nothing sent)");
        r.line("Outcome", outcome);
        r.line("Took", String.format(Locale.US, "%dm %02ds", seconds / 60, seconds % 60));
        r.line("Bus", f.protocolName + " (protocol " + f.protocol + ")");
        r.line("Modules found", f.modules.isEmpty() ? "none" : String.join(", ", f.modules));
        if (options.active) {
            r.line("Standard readings answered", f.answered.size() + " of " + f.supported.size() + " advertised");
            r.line("Trouble codes", f.codes.isEmpty() ? "none" : String.join(", ", f.codes));
        } else {
            r.line("Message streams heard", f.streams);
        }
        r.line("Requests sent to the truck", guard.busFrames().size());
        r.line("Commands refused by the read-only lock", guard.rejected().size());
        return new Result(r.text(), r.json(), linkLost);
    }

    // ---------------------------------------------------------------- shared steps

    private static void adapterSection(ELM327Adapter a, ScanReport r, Progress progress) throws IOException {
        progress.update("Asking the adapter what it is");
        r.section("adapter", "ADAPTER");
        r.line("Chip", firstLine(a.rawCommand("ATI", AT_MS)));
        r.line("Device", firstLine(a.rawCommand("AT@1", AT_MS)));
        String stn = firstLine(a.rawCommand("STI", AT_MS));
        r.line("STN firmware", stn.equals("?") || stn.isEmpty() ? "none (not an OBDLink/STN chip)" : stn);
        r.line("Voltage at OBD port", firstLine(a.rawCommand("ATRV", AT_MS)));
    }

    private static void busLines(ELM327Adapter a, ScanReport r, Findings f) throws IOException {
        f.protocolName = firstLine(a.rawCommand("ATDP", AT_MS));
        f.protocol = protocolNumber(a.rawCommand("ATDPN", AT_MS));
        r.line("Protocol", f.protocolName);
        r.line("Protocol number", f.protocol + (f.protocol.equals("1") ? " (J1850 PWM, the 7.3L's bus)" : ""));
    }

    // ---------------------------------------------------------------- listen-only

    private static void listen(ELM327Adapter a, ScanReport r, Findings f, Options options,
                               Progress progress, CancelCheck cancel) throws IOException {
        adapterSection(a, r, progress);
        progress.update("Checking which bus the adapter is on");
        r.section("bus", "BUS");
        busLines(a, r, f);
        if (!f.protocol.matches("[1-9]")) {
            r.note("The adapter hasn't settled on the truck's bus, and listen-only can't search for it (searching transmits).");
            r.note("Turn the key on, wait until the dashboard shows live readings, then scan again.");
            f.problems.add("No bus to listen to: the adapter reports protocol " + f.protocol);
            return;
        }
        a.rawCommand("ATH1", AT_MS);
        if (f.protocol.equals("1") || f.protocol.equals("2")) a.rawCommand("ATIFR0", AT_MS);
        if (isCan(f.protocol)) a.rawCommand("ATCSM1", AT_MS);
        if (cancel.cancelled()) return;

        long seconds = options.listenMs / 1000;
        Movement movement = new Movement(f.protocol);
        long start = System.currentTimeMillis();
        progress.update("Listening for " + seconds + " s (sending nothing)");
        a.monitor(options.listenMs, (at, line) -> {
            movement.add(at, line);
            progress.update(String.format(Locale.US, "Listening: %d of %d s, %d messages heard",
                    (at - start) / 1000, seconds, movement.total));
            return !cancel.cancelled();
        });
        movement.report(r, f, start, Math.min(System.currentTimeMillis(), start + options.listenMs));
    }

    /** Who's broadcasting what, how often, from a listen-only capture. */
    static final class Movement {
        private final String protocol;
        final Map<String, Stream> streams = new TreeMap<>();
        final List<String> otherLines = new ArrayList<>();
        int total = 0;

        Movement(String protocol) {
            this.protocol = protocol;
        }

        void add(long at, String raw) {
            String line = raw.replaceAll("\\s", "").toUpperCase(Locale.US);
            if (!line.matches("([0-9A-F]{2})+|[0-9A-F]{3}([0-9A-F]{2})+")) {
                if (otherLines.size() < 20) otherLines.add(raw.trim());
                return;
            }
            int idLength = idLength(protocol);
            if (line.length() <= idLength) return;
            String id = line.substring(0, idLength);
            String data = line.substring(idLength);
            if (!isCan(protocol) && data.length() >= 4) data = data.substring(0, data.length() - 2); // checksum
            total++;
            Stream s = streams.get(id);
            if (s == null) {
                s = new Stream(id);
                streams.put(id, s);
            }
            s.add(at, data);
        }

        /** Sending module, by protocol: J1850/ISO byte 3, an 11-bit CAN ID, or a 29-bit ID's last byte. */
        String source(String id) {
            if (protocol.equals("6") || protocol.equals("8")) return id;
            if (protocol.equals("7") || protocol.equals("9")) return id.substring(6, 8);
            return id.substring(4, 6);
        }

        /** Who it's addressed to; 11-bit CAN IDs don't say. */
        String target(String id) {
            if (protocol.equals("6") || protocol.equals("8")) return "all";
            if (protocol.equals("7") || protocol.equals("9")) return id.substring(4, 6);
            return id.substring(2, 4);
        }

        void report(ScanReport r, Findings f, long start, long end) {
            long seconds = Math.max(1, (end - start) / 1000);
            r.section("dataMovement", "DATA MOVEMENT (" + seconds + " s of listening, " + total + " messages)");
            if (streams.isEmpty()) {
                r.note("Heard nothing. With the key off (or on some trucks, the engine off) the bus can be quiet.");
                f.problems.add("Listen-only heard no traffic");
            }
            for (Stream s : streams.values()) {
                String from = source(s.id);
                f.modules.add(from);
                long quietAtEnd = end - s.last;
                long median = s.medianInterval();
                String pattern = s.pattern();
                r.item(String.format(Locale.US, "%s  from %s (%s) to %s: %d msgs, %.1f/s, %s%s, max gap %d ms, %d distinct, last [%s]",
                                s.id, from, moduleName(from, protocol), target(s.id), s.count, s.rate(), pattern,
                                pattern.equals("periodic") ? " every ~" + median + " ms" : "", s.maxGap,
                                s.distinct.size(), s.lastData),
                        ScanReport.fields("id", s.id, "source", from, "sourceName", moduleName(from, protocol),
                                "target", target(s.id), "messages", s.count, "perSecond", s.rate(),
                                "pattern", pattern, "medianIntervalMs", median, "maxGapMs", s.maxGap,
                                "gaps", s.gaps(), "quietAtEndMs", quietAtEnd,
                                "distinctPayloads", s.distinct.size(), "lastPayload", s.lastData));
                if (s.gaps() > 0) {
                    f.problems.add(String.format(Locale.US, "%s skipped %d time(s) (longest gap %d ms)", s.id, s.gaps(), s.maxGap));
                }
                if (pattern.equals("periodic") && median > 0 && quietAtEnd > 3 * median && quietAtEnd > 500) {
                    f.problems.add(String.format(Locale.US, "%s went quiet %d ms before the end", s.id, quietAtEnd));
                }
            }
            for (String line : otherLines) {
                r.note("Not a frame: " + line);
                if (line.toUpperCase(Locale.US).contains("BUFFER FULL")) {
                    f.problems.add("Adapter buffer overflowed: some traffic was missed");
                }
            }
            f.streams = streams.size();
            r.section("modules", "MODULES HEARD");
            if (f.modules.isEmpty()) r.note("none");
            for (String m : f.modules) {
                r.item(m + "  " + moduleName(m, protocol), ScanReport.fields("address", m, "name", moduleName(m, protocol)));
            }
        }
    }

    static final class Stream {
        final String id;
        int count = 0;
        long first = -1;
        long last = -1;
        long maxGap = 0;
        String lastData = "";
        final List<Long> intervals = new ArrayList<>();
        final Set<String> distinct = new LinkedHashSet<>();

        Stream(String id) {
            this.id = id;
        }

        void add(long at, String data) {
            if (count > 0) {
                long gap = at - last;
                intervals.add(gap);
                maxGap = Math.max(maxGap, gap);
            } else {
                first = at;
            }
            last = at;
            count++;
            lastData = data;
            if (distinct.size() < 1000) distinct.add(data);
        }

        double rate() {
            return count < 2 || last <= first ? 0 : Math.round((count - 1) * 10000.0 / (last - first)) / 10.0;
        }

        long medianInterval() {
            if (intervals.isEmpty()) return 0;
            List<Long> sorted = new ArrayList<>(intervals);
            Collections.sort(sorted);
            return sorted.get(sorted.size() / 2);
        }

        /** Periodic when most gaps are close to the typical one. */
        String pattern() {
            if (count < 4) return "sporadic";
            long median = medianInterval();
            if (median <= 0) return "burst";
            int steady = 0;
            for (long gap : intervals) {
                if (gap >= median / 2 && gap <= median * 3 / 2) steady++;
            }
            return steady * 10 >= intervals.size() * 8 ? "periodic" : "sporadic";
        }

        /** Gaps more than three times the typical interval: missed messages. */
        int gaps() {
            if (!pattern().equals("periodic")) return 0;
            long median = medianInterval();
            int n = 0;
            for (long gap : intervals) {
                if (gap > 3 * median) n++;
            }
            return n;
        }
    }

    // ---------------------------------------------------------------- active

    private static void scan(ELM327Adapter a, ScanReport r, Findings f, ReadOnlyGuard guard, Options options,
                             Progress progress, CancelCheck cancel) throws IOException {
        adapterSection(a, r, progress);

        // ---- Bus ----
        progress.update("Waking the truck's bus");
        a.rawCommand("ATH0", AT_MS);
        String wake = a.rawCommand("0100", BUS_WAKE_MS);
        r.section("bus", "BUS");
        busLines(a, r, f);
        String proto = f.protocol;
        boolean pwm = proto.equals("1");
        boolean can11 = proto.equals("6") || proto.equals("8");
        if (DTCReader.splitMessages(wake).isEmpty()) {
            r.note("The truck didn't answer the first request: " + oneLine(wake));
            r.note("Turn the key on and run the scan again.");
            f.problems.add("No answer to the first request (0100)");
            return;
        }
        String pcmHeader = pwm ? ELM327Adapter.PCM_HEADER : (can11 ? "7E0" : null);
        String obdHeader = pwm ? ELM327Adapter.OBD_HEADER : (can11 ? "7DF" : null);
        if (cancel.cancelled()) return;

        // ---- Standard readings ----
        progress.update("Reading every standard PID");
        for (int pid : a.readSupportedPids()) {
            if (pid % 0x20 != 0) f.supported.add(pid); // skip the bitmap pages
        }
        f.readingsChecked = true;
        r.section("standardReadings", "STANDARD READINGS (MODE 01): " + f.supported.size() + " supported");
        for (int pid : f.supported) {
            if (cancel.cancelled()) return;
            int[] data = a.readPid(pid);
            long ms = guard.lastResponseMs();
            if (data != null) f.answered.add(pid);
            else f.problems.add(String.format(Locale.US, "PID %02X advertised but didn't answer", pid));
            PidCatalog.Pid def = PidCatalog.get(pid);
            double value = def == null ? Double.NaN : def.decode(data);
            r.item(String.format(Locale.US, "%02X  %s  (%d ms)", pid, describePid(pid, data), ms),
                    ScanReport.fields("pid", String.format(Locale.US, "%02X", pid), "name", pidName(pid),
                            "raw", data == null ? null : hexBytes(data), "value", value,
                            "unit", def == null || Double.isNaN(value) ? null : def.unit,
                            "responseMs", ms, "answered", data != null));
        }

        // ---- Freeze frame, vehicle info, trouble codes ----
        progress.update("Checking freeze frame, vehicle info, and trouble codes");
        r.section("otherServices", "FREEZE FRAME, VEHICLE INFO, TROUBLE CODES");
        String freeze = a.rawCommand("020200", QUERY_MS);
        String freezeCode = parseFreezeFrameCode(freeze);
        r.line("Freeze frame (Mode 02)", freezeCode != null
                ? "answers; saved for " + freezeCode
                : (answered(freeze, "42") ? "answers; nothing saved" : "no answer: " + oneLine(freeze)));
        String vinInfo = a.rawCommand("0900", QUERY_MS);
        r.line("Vehicle info (Mode 09)", answered(vinInfo, "49") ? "answers" : "no answer: " + oneLine(vinInfo));
        // The VIN reply stays out of the debug log; the report shows it only when asked.
        ELM327Adapter.Trace trace = a.getTrace();
        a.setTrace(null);
        String vin;
        try {
            vin = parseVin(a.rawCommand("0902", 8000L));
        } finally {
            a.setTrace(trace);
        }
        r.line("VIN", vin == null ? "not reported"
                : options.includeVin ? vin : "reported, hidden (tick Include VIN to show it)");
        String calibration = parseAscii(a.rawCommand("0904", 8000L), "4904");
        r.line("Calibration ID (Mode 09 04)", calibration != null ? calibration : "not reported");
        for (DTCReader.Mode mode : DTCReader.Mode.values()) {
            String reply = a.requestTroubleCodes(mode);
            String header = String.format(Locale.US, "%02X", mode.request + 0x40);
            List<String> codes = DTCReader.parseCodes(reply, mode);
            String label = mode.label + " codes (Mode " + String.format(Locale.US, "%02X", mode.request) + ")";
            if (answered(reply, header)) {
                r.line(label, codes.isEmpty() ? "none" : String.join(", ", codes));
                for (String code : codes) f.codes.add(code + " (" + mode.label.toLowerCase(Locale.US) + ")");
            } else {
                r.line(label, "no answer: " + oneLine(reply));
            }
        }
        String permanent = a.rawCommand("0A", QUERY_MS);
        if (answered(permanent, "4A")) {
            List<String> codes = parsePermanentCodes(permanent);
            r.line("Permanent codes (Mode 0A)", codes.isEmpty() ? "none" : String.join(", ", codes));
            for (String code : codes) f.codes.add(code + " (permanent)");
        } else {
            r.line("Permanent codes (Mode 0A)", "no answer: " + oneLine(permanent) + " (normal before 2010)");
        }
        if (cancel.cancelled()) return;

        // ---- Ford enhanced PIDs by name ----
        progress.update("Trying Ford enhanced PIDs");
        r.section("fordEnhanced", "FORD ENHANCED (MODE 22): WHAT AUTOSENTRY READS, AND CANDIDATES");
        Map<String, String> named = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : appFordPids().entrySet()) named.put(e.getKey(), e.getValue() + " (AutoSentry reads this)");
        for (String[] candidate : FORD_CANDIDATES) {
            if (!named.containsKey(candidate[0])) named.put(candidate[0], candidate[1]);
        }
        for (Map.Entry<String, String> e : named.entrySet()) {
            if (cancel.cancelled()) return;
            String result = tryFordPid(a, e.getKey(), pcmHeader, obdHeader);
            boolean ok = result.startsWith("answers");
            f.fordAnswered.put(e.getKey(), ok);
            r.item(e.getKey() + "  " + e.getValue() + ": " + result,
                    ScanReport.fields("pid", e.getKey(), "name", e.getValue(), "answered", ok, "result", result));
        }

        // ---- Who's on the bus ----
        progress.update("Listing modules that answer");
        r.section("modules", "MODULES THAT ANSWER STANDARD OBD-II");
        a.rawCommand("ATH1", AT_MS);
        Map<String, String> responders = new LinkedHashMap<>();
        for (String message : DTCReader.splitMessages(a.rawCommand("0100", QUERY_MS))) {
            String source = sourceAddress(message, proto);
            if (source != null) responders.put(source, message);
        }
        if (responders.isEmpty()) r.note("none (with headers on)");
        for (Map.Entry<String, String> e : responders.entrySet()) {
            f.modules.add(e.getKey());
            r.item(e.getKey() + "  " + moduleName(e.getKey(), proto) + "  [" + e.getValue() + "]",
                    ScanReport.fields("address", e.getKey(), "name", moduleName(e.getKey(), proto), "reply", e.getValue()));
        }

        // The two sweeps are thousands of requests; the report has their results, so keep them out of the debug log.
        a.setTrace(null);
        try {
            sweeps(a, r, f, progress, cancel, proto, pwm, pcmHeader, obdHeader);
        } finally {
            a.setTrace(trace);
        }
    }

    private static void sweeps(ELM327Adapter a, ScanReport r, Findings f, Progress progress, CancelCheck cancel,
                               String proto, boolean pwm, String pcmHeader, String obdHeader) throws IOException {
        if (pwm) {
            r.section("moduleSweep", "FORD MODULE SWEEP (every J1850 address, Mode 22 read)");
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
            if (modules.isEmpty()) r.note("nothing answered a direct request");
            for (Map.Entry<String, String> e : modules.entrySet()) {
                f.modules.add(e.getKey());
                r.item(e.getKey() + "  " + moduleName(e.getKey(), proto) + "  " + e.getValue(),
                        ScanReport.fields("address", e.getKey(), "name", moduleName(e.getKey(), proto), "found", e.getValue()));
            }
            r.note("(A reply starting 7F is a module saying \"I'm here, but not that PID.\" It still proves the module exists.)");
        }
        a.rawCommand("ATH0", AT_MS);
        if (cancel.cancelled()) return;

        // ---- PCM Mode 22 sweep ----
        if (pcmHeader == null) {
            r.section("pcmSweep", "PCM ENHANCED SWEEP");
            r.note("Skipped: only runs on J1850 PWM or 11-bit CAN.");
            return;
        }
        int total = 0;
        StringBuilder ranges = new StringBuilder();
        for (int[] range : SWEEP_RANGES) {
            total += range[1] - range[0] + 1;
            if (ranges.length() > 0) ranges.append(", ");
            ranges.append(String.format(Locale.US, "%04X-%04X", range[0], range[1]));
        }
        r.section("pcmSweep", "PCM ENHANCED SWEEP (MODE 22, sent straight to the PCM): " + ranges);
        List<String[]> hits = new ArrayList<>();
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
                    if (data != null) hits.add(new String[]{hex, hexBytes(data)});
                    done++;
                }
            }
        } finally {
            a.rawCommand("ATSH" + obdHeader, AT_MS);
        }
        r.line("Answered", hits.size() + " of " + done + " PIDs"
                + (done < total ? " (sweep stopped at " + done + " of " + total + ")" : ""));
        Map<String, String> known = appFordPids();
        for (String[] hit : hits) {
            String name = known.get(hit[0]);
            r.item(hit[0] + " = " + hit[1] + (name != null ? "  (" + name + ", AutoSentry reads this)" : ""),
                    ScanReport.fields("pid", hit[0], "raw", hit[1], "usedByApp", name != null));
        }
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

    // ---------------------------------------------------------------- closing sections

    /** What the truck sends compared with what AutoSentry reads and shows. */
    private static void dataFlow(ScanReport r, Findings f, Options options) {
        r.section("appDataFlow", "IN-APP DATA FLOW (what the truck answers vs. what AutoSentry uses)");
        if (!options.active || !f.readingsChecked) {
            r.note("Needs an active scan: AutoSentry reads the truck by asking, and listen-only asks nothing.");
            return;
        }
        TreeSet<Integer> app = appMode01Pids();
        TreeSet<Integer> used = new TreeSet<>(f.answered);
        used.retainAll(app);
        TreeSet<Integer> ignored = new TreeSet<>(f.answered);
        ignored.removeAll(app);
        TreeSet<Integer> missing = new TreeSet<>(app);
        missing.removeAll(f.answered);
        r.line("Standard readings the truck answers and AutoSentry uses", pidList(used));
        r.line("Truck answers, AutoSentry ignores", pidList(ignored));
        r.line("AutoSentry can show, this truck doesn't answer", pidList(missing));
        List<String> fordUsed = new ArrayList<>();
        List<String> fordMissing = new ArrayList<>();
        List<String> fordExtra = new ArrayList<>();
        Map<String, String> appFord = appFordPids();
        for (Map.Entry<String, Boolean> e : f.fordAnswered.entrySet()) {
            String label = e.getKey() + (appFord.containsKey(e.getKey()) ? " " + appFord.get(e.getKey()) : "");
            if (appFord.containsKey(e.getKey())) (e.getValue() ? fordUsed : fordMissing).add(label);
            else if (e.getValue()) fordExtra.add(label);
        }
        r.line("Ford PIDs AutoSentry reads that answer", fordUsed.isEmpty() ? "none" : String.join(", ", fordUsed));
        r.line("Ford PIDs AutoSentry reads that don't answer", fordMissing.isEmpty() ? "none" : String.join(", ", fordMissing));
        r.line("Other Ford PIDs that answer (unused)", fordExtra.isEmpty() ? "none" : String.join(", ", fordExtra));
    }

    private static void problems(ScanReport r, Findings f, ReadOnlyGuard guard, String outcome) {
        r.section("problems", "GAPS AND ERRORS");
        List<String> all = new ArrayList<>();
        if (!outcome.equals("complete")) all.add("Scan " + outcome);
        all.addAll(guard.problems());
        all.addAll(f.problems);
        all.addAll(guard.rejected());
        if (all.isEmpty()) r.note("none");
        for (String p : all) r.item(p, ScanReport.fields("problem", p));
    }

    private static void commandsSent(ScanReport r, ReadOnlyGuard guard, Options options) {
        r.section("commandsSent", "COMMANDS SENT");
        List<String> frames = guard.busFrames();
        r.line("Requests sent to the truck", frames.isEmpty() ? "none" : String.valueOf(frames.size()));
        if (!options.active) {
            r.note("Listen-only: this scan put nothing on the bus. Tracking polls the truck before and after the scan; that isn't part of it.");
        }
        // Header changes (ATSH...) are counted together; the request list shows each one's header.
        Map<String, Integer> grouped = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : guard.adapterCommands().entrySet()) {
            String key = e.getKey().startsWith("ATSH") ? "ATSH (set header)" : e.getKey();
            Integer n = grouped.get(key);
            grouped.put(key, (n == null ? 0 : n) + e.getValue());
        }
        StringBuilder adapterOnly = new StringBuilder();
        for (Map.Entry<String, Integer> e : grouped.entrySet()) {
            if (adapterOnly.length() > 0) adapterOnly.append(", ");
            adapterOnly.append(e.getKey()).append(e.getValue() > 1 ? " x" + e.getValue() : "");
        }
        r.line("Adapter-only commands (settings and reads that never reach the truck)",
                adapterOnly.length() == 0 ? "none" : adapterOnly.toString());
        List<String> rejected = guard.rejected();
        r.line("Refused by the read-only lock", rejected.isEmpty() ? "none" : String.valueOf(rejected.size()));
        if (!frames.isEmpty()) r.textOnly("  Every request, in order ([header] request):");
        for (String frame : frames) {
            int split = frame.indexOf("] ");
            r.item("  " + frame, ScanReport.fields("header", frame.substring(1, split), "request", frame.substring(split + 2)));
        }
    }

    private static String pidList(Set<Integer> pids) {
        if (pids.isEmpty()) return "none";
        StringBuilder sb = new StringBuilder();
        for (int pid : pids) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(String.format(Locale.US, "%02X %s", pid, pidName(pid)));
        }
        return sb.toString();
    }

    static boolean isCan(String protocol) {
        return protocol.matches("[6-9]");
    }

    private static int idLength(String protocol) {
        if (protocol.equals("6") || protocol.equals("8")) return 3;
        if (protocol.equals("7") || protocol.equals("9")) return 8;
        return 6;
    }

    /** Printable text from a Mode 09 reply (e.g. the calibration ID), or null. */
    static String parseAscii(String reply, String header) {
        StringBuilder chars = new StringBuilder();
        for (String message : DTCReader.splitMessages(reply == null ? "" : reply)) {
            if (!message.startsWith(header) || message.length() < header.length() + 2) continue;
            for (int i = header.length() + 2; i + 2 <= message.length(); i += 2) {
                int c = Integer.parseInt(message.substring(i, i + 2), 16);
                if (c >= 0x20 && c < 0x7F) chars.append((char) c);
            }
        }
        String text = chars.toString().trim();
        return text.isEmpty() ? null : text;
    }

    /** Codes from a Mode 0A reply: pairs of bytes after "4A" (a count byte first on CAN), zero pairs skipped. */
    static List<String> parsePermanentCodes(String reply) {
        Set<String> codes = new LinkedHashSet<>();
        for (String message : DTCReader.splitMessages(reply == null ? "" : reply)) {
            if (!message.startsWith("4A")) continue;
            String payload = message.substring(2);
            int start = (payload.length() / 2) % 2 == 1 ? 2 : 0; // CAN: count byte first
            for (int i = start; i + 4 <= payload.length(); i += 4) {
                int a = Integer.parseInt(payload.substring(i, i + 2), 16);
                int b = Integer.parseInt(payload.substring(i + 2, i + 4), 16);
                if (a != 0 || b != 0) codes.add(DTCReader.decode(a, b));
            }
        }
        return new ArrayList<>(codes);
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
