package com.autosentry.app.obd;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The systems scan's read-only lock. While it's installed on an
 * {@link ELM327Adapter}, every command is checked here before a byte is
 * written: anything that isn't a read is refused, never sent.
 *
 * Allowed on the bus: OBD-II Modes 01, 02, 03, 07, 09, 0A and UDS 0x22 / 0x19.
 * Everything else (clear codes 04/14, sessions 10, reset 11, security 27,
 * writes 2E/2F, routines 31, downloads 34-37, tester present 3E, DTC
 * settings 85, ...) is refused. Adapter commands are limited to reads and
 * settings that ATZ resets; anything that saves to the adapter's memory is
 * refused (ATSP, ATPP, ATSD, ATM1, baud changes).
 *
 * In listen-only mode no bus request is allowed at all.
 *
 * It also paces requests (one per {@code minIntervalMs}), backs off and
 * retries when a module says it's busy (NRC 0x21) or still working (0x78),
 * backs off on bus errors and gives up after three, and stops the scan on
 * any reply that isn't an answer to the request that was sent.
 */
public final class ReadOnlyGuard {

    /** A command the guard refused. Nothing was sent. */
    public static final class Blocked extends IOException {
        Blocked(String message) {
            super(message);
        }
    }

    /** The scan stopped itself on something it didn't expect. */
    public static final class Abort extends IOException {
        Abort(String message) {
            super(message);
        }
    }

    /** Time source, swappable so tests don't wait. */
    public interface Clock {
        long now();

        void sleep(long ms) throws IOException;
    }

    static final Clock SYSTEM_CLOCK = new Clock() {
        @Override
        public long now() {
            return System.currentTimeMillis();
        }

        @Override
        public void sleep(long ms) throws IOException {
            if (ms <= 0) return;
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted");
            }
        }
    };

    static final Set<Integer> READ_SERVICES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            0x01, 0x02, 0x03, 0x07, 0x09, 0x0A, 0x19, 0x22)));

    private static final Pattern[] ADAPTER_ALLOWED = patterns(
            "ATI", "AT@1", "ATRV", "ATDP", "ATDPN",       // reads
            "ATE[01]", "ATL[01]", "ATS[01]", "ATH[01]",   // formatting
            "ATSH([0-9A-F]{3}|[0-9A-F]{6}|[0-9A-F]{8})",  // header for the next request (reset by ATZ)
            "ATIFR0",                                     // J1850: don't acknowledge frames while listening
            "ATCSM1",                                     // CAN: silent monitoring (no ACKs)
            "ATMA",                                       // monitor all: listen, transmit nothing
            "STI", "STDI");                               // OBDLink firmware and device reads

    static final int MAX_ATTEMPTS = 4;
    static final int MAX_BUS_ERRORS = 3;
    private static final long MAX_INTERVAL_MS = 1000L;

    private static final String[] BUS_ERRORS = {
            "BUS ERROR", "CAN ERROR", "BUS BUSY", "DATA ERROR", "FB ERROR", "<RX ERROR", "RX ERROR", "LV RESET"};

    private final boolean listenOnly;
    private final Clock clock;
    private long minIntervalMs;
    private final long backoffMs;

    private final List<String> busFrames = new ArrayList<>();
    private final Map<String, Integer> adapterCommands = new LinkedHashMap<>();
    private final List<String> rejected = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();

    private String header = "default";
    private boolean headersOn = false;
    private String protocol = "?";
    private long lastBusSend = Long.MIN_VALUE / 2;
    private long sentAt = 0;
    private long lastResponseMs = -1;
    private int busErrors = 0;

    public ReadOnlyGuard(boolean listenOnly) {
        this(listenOnly, 100L, 250L, SYSTEM_CLOCK);
    }

    ReadOnlyGuard(boolean listenOnly, long minIntervalMs, long backoffMs, Clock clock) {
        this.listenOnly = listenOnly;
        this.minIntervalMs = minIntervalMs;
        this.backoffMs = backoffMs;
        this.clock = clock;
    }

    public boolean isListenOnly() {
        return listenOnly;
    }

    /**
     * Checks a command before it's written. Refused commands throw
     * {@link Blocked} and are recorded; allowed bus requests wait out the
     * pacing interval and are recorded with the header they go out under.
     */
    public synchronized void beforeSend(String command) throws IOException {
        String cmd = command == null ? "" : command.replace(" ", "").toUpperCase(Locale.US);
        if (cmd.isEmpty() || !cmd.matches("[0-9A-Z@]+")) {
            // An empty line repeats the last command on an ELM327, and CR/LF could smuggle in a second one.
            reject(command, "not a single plain command");
        }
        if (cmd.startsWith("AT") || cmd.startsWith("ST")) {
            if (!adapterAllowed(cmd)) reject(cmd, "adapter command isn't on the read-only list");
            if (cmd.startsWith("ATSH")) header = cmd.substring(4);
            if (cmd.equals("ATH0")) headersOn = false;
            if (cmd.equals("ATH1")) headersOn = true;
            Integer n = adapterCommands.get(cmd);
            adapterCommands.put(cmd, n == null ? 1 : n + 1);
            sentAt = clock.now();
            return;
        }
        if (!cmd.matches("([0-9A-F]{2})+")) reject(cmd, "not a hex request");
        if (listenOnly) reject(cmd, "listen-only: nothing may be sent to the truck");
        int service = Integer.parseInt(cmd.substring(0, 2), 16);
        if (!READ_SERVICES.contains(service)) {
            reject(cmd, String.format(Locale.US, "service 0x%02X isn't a read", service));
        }
        long wait = lastBusSend + minIntervalMs - clock.now();
        if (wait > 0) clock.sleep(wait);
        lastBusSend = clock.now();
        sentAt = lastBusSend;
        busFrames.add("[" + header + "] " + cmd);
    }

    /**
     * Checks the reply to a command that was sent. Returns true when the
     * request should be sent again (a module was busy or still working, or
     * the bus had an error). Throws {@link Abort} on a reply that isn't an
     * answer to this request, or after too many bus errors.
     */
    public synchronized boolean afterReply(String command, String reply, int attempt) throws IOException {
        lastResponseMs = clock.now() - sentAt;
        String cmd = command.replace(" ", "").toUpperCase(Locale.US);
        if (cmd.equals("ATDPN")) protocol = TruckProbe.protocolNumber(reply);
        if (cmd.startsWith("AT") || cmd.startsWith("ST")) return false;

        int service = Integer.parseInt(cmd.substring(0, 2), 16);
        String positive = String.format(Locale.US, "%02X", service + 0x40);
        String negative = String.format(Locale.US, "7F%02X", service);
        boolean answered = false;
        boolean pending = false;
        boolean busError = false;
        for (String data : replyData(reply == null ? "" : reply, cmd)) {
            if (data.startsWith(positive)) {
                answered = true;
            } else if (data.startsWith(negative) && data.length() >= 6) {
                String nrc = data.substring(4, 6);
                if (nrc.equals("21") || nrc.equals("78")) {
                    pending = true;
                } else {
                    answered = true;
                }
            } else {
                throw new Abort("unexpected reply to " + cmd + ": " + data);
            }
        }
        for (String line : (reply == null ? "" : reply).split("[\\r\\n]+")) {
            String upper = line.toUpperCase(Locale.US);
            for (String error : BUS_ERRORS) {
                if (upper.contains(error)) busError = true;
            }
            if (upper.contains("BUFFER FULL")) problems.add("Adapter buffer overflowed during " + cmd);
        }
        if (busError) {
            busErrors++;
            problems.add("Bus error on " + cmd + " (" + TruckProbe.oneLine(reply) + ")");
            if (busErrors >= MAX_BUS_ERRORS) throw new Abort(busErrors + " bus errors; stopped so the bus can settle");
            backOff();
            return !answered && attempt < MAX_ATTEMPTS;
        }
        if (pending && !answered) {
            if (attempt >= MAX_ATTEMPTS) {
                problems.add(cmd + ": module still busy after " + attempt + " tries");
                return false;
            }
            backOff();
            return true;
        }
        return false;
    }

    /** The data part of each frame in a reply: headers stripped, status lines and continuation frames skipped. */
    private List<String> replyData(String reply, String cmd) {
        List<String> out = new ArrayList<>();
        if (!headersOn) {
            for (String message : DTCReader.splitMessages(reply)) {
                if (!message.equals(cmd)) out.add(message); // an echoed command isn't a reply
            }
            return out;
        }
        for (String raw : reply.split("[\\r\\n]+")) {
            String line = raw.replace(">", "").replaceAll("\\s", "").toUpperCase(Locale.US);
            if (!line.matches("([0-9A-F]{2})+|[0-9A-F]{3}([0-9A-F]{2})+")) continue;
            switch (protocol) {
                case "6": case "8":
                    addCanData(out, line, 3);
                    break;
                case "7": case "9":
                    addCanData(out, line, 8);
                    break;
                case "1": case "2": case "3": case "4": case "5":
                    if (line.length() > 6) out.add(line.substring(6));
                    break;
                default:
                    break; // unknown frame layout: nothing safe to check
            }
        }
        return out;
    }

    /** CAN with headers on: ID, then the ISO-TP byte. Only single and first frames start with the service. */
    private static void addCanData(List<String> out, String line, int idLength) {
        if (line.length() <= idLength + 2) return;
        char type = line.charAt(idLength);
        if (type == '0') out.add(line.substring(idLength + 2));
        else if (type == '1' && line.length() > idLength + 4) out.add(line.substring(idLength + 4));
    }

    private void backOff() throws IOException {
        clock.sleep(backoffMs);
        minIntervalMs = Math.min(Math.max(minIntervalMs * 2, backoffMs), MAX_INTERVAL_MS);
    }

    private void reject(String command, String reason) throws Blocked {
        String shown = command == null ? "(null)" : command.replace("\r", "\\r").replace("\n", "\\n");
        rejected.add(shown + ": " + reason);
        throw new Blocked("refused " + shown + ": " + reason);
    }

    private static boolean adapterAllowed(String cmd) {
        for (Pattern p : ADAPTER_ALLOWED) {
            if (p.matcher(cmd).matches()) return true;
        }
        return false;
    }

    private static Pattern[] patterns(String... regexes) {
        Pattern[] out = new Pattern[regexes.length];
        for (int i = 0; i < regexes.length; i++) out[i] = Pattern.compile(regexes[i]);
        return out;
    }

    /** Milliseconds from the last command's write to its reply. */
    public synchronized long lastResponseMs() {
        return lastResponseMs;
    }

    /** Every request sent to the truck, in order, with the header it went out under. */
    public synchronized List<String> busFrames() {
        return new ArrayList<>(busFrames);
    }

    /** Adapter-only commands (never reach the truck) and how many times each was sent. */
    public synchronized Map<String, Integer> adapterCommands() {
        return new LinkedHashMap<>(adapterCommands);
    }

    /** Commands refused before sending, with the reason. */
    public synchronized List<String> rejected() {
        return new ArrayList<>(rejected);
    }

    /** Busy modules, bus errors and buffer overflows seen along the way. */
    public synchronized List<String> problems() {
        return new ArrayList<>(problems);
    }
}
