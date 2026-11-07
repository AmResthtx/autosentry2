package com.autosentry.app.obd;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns ELM327 replies to Mode 03 (stored) and Mode 07 (pending) trouble-code
 * requests into codes like "P1211", and describes them from the 7.3L list.
 *
 * The adapter runs with spaces and headers off, so a reply is one run of hex
 * per line. Two layouts exist:
 *  - J1850 PWM (the 7.3L) and other pre-CAN buses: each line is "43" plus
 *    exactly three 2-byte codes, padded with 0000; more codes mean more lines.
 *  - CAN: "43", a count byte, then that many codes; long replies come as an
 *    ISO-TP block ("00A", "0:...", "1:...").
 * After the 43/47 header, pre-CAN payloads are always an even number of bytes
 * and CAN payloads (count + 2 per code) always odd, which tells them apart
 * without asking the adapter which protocol it's on.
 *
 * A read never concludes "no codes" from silence. Each reply is classified
 * (see {@link #classify}); timeouts, adapter errors and NO DATA are retried,
 * and NO DATA only counts as none when the same read proved the truck's
 * computer is answering (see {@link #decide}).
 */
public final class DTCReader {

    /** Tries per mode before a read is called failed. A normal read stops at the first answer. */
    public static final int MAX_ATTEMPTS = 3;
    public static final long RETRY_GAP_MS = 500L;
    /** Why a read stopped early on the key-on check: a crank is never held up for codes. */
    public static final String ENGINE_STARTED = "the engine started before codes could be read";
    static final String NO_ANSWER = "the truck didn't answer";

    /** What one request for codes got back. */
    public enum Reply {
        /** A 43/47 reply with codes in it. */
        CODES,
        /** A 43/47 reply with only 0000 padding: the truck answered, and has none. */
        NONE,
        /** NO DATA: nothing answered. On J1850 PWM a clean 7.3L may say this instead of NONE. */
        NO_DATA,
        /** A 7F negative reply: the truck's computer heard the request and said no. */
        REFUSED,
        /** Timeout, adapter or bus error, or a reply that doesn't make sense. */
        FAILED
    }

    public static final class Attempt {
        public final Reply reply;
        /** Codes in this reply; empty unless CODES. */
        public final List<String> codes;
        /** The 7F reply's reason code (NRC); -1 when there isn't one. */
        public final int nrc;
        /** Why this attempt didn't count, in plain English; null for CODES and NONE. */
        public final String problem;

        Attempt(Reply reply, List<String> codes, int nrc, String problem) {
            this.reply = reply;
            this.codes = Collections.unmodifiableList(new ArrayList<>(codes));
            this.nrc = nrc;
            this.problem = problem;
        }

        static Attempt failed(String problem) {
            return new Attempt(Reply.FAILED, Collections.emptyList(), -1, problem);
        }

        boolean answered() {
            return reply == Reply.CODES || reply == Reply.NONE;
        }
    }

    /** How a read of one mode ended. */
    public enum Outcome {
        CODES('C'), NONE('N'), REFUSED('R'), FAILED('F');

        final char key;

        Outcome(char key) {
            this.key = key;
        }

        static Outcome fromKey(char key) {
            for (Outcome o : values()) if (o.key == key) return o;
            return null;
        }
    }

    /** The answer for one mode after every try, or why there isn't one. */
    public static final class Result {
        public final Mode mode;
        public final Outcome outcome;
        /** Every code the truck sent. Can be non-empty for FAILED: an incomplete read keeps what came through. */
        public final List<String> codes;
        /** REFUSED / FAILED: why, in plain English. Null otherwise. */
        public final String problem;
        /** Requests actually sent for this mode. */
        public final int attempts;

        public Result(Mode mode, Outcome outcome, List<String> codes, String problem, int attempts) {
            this.mode = mode;
            this.outcome = outcome;
            this.codes = Collections.unmodifiableList(new ArrayList<>(codes));
            this.problem = problem;
            this.attempts = attempts;
        }

        /** The truck gave a real answer: codes, or a confirmed none. */
        public boolean answered() {
            return outcome == Outcome.CODES || outcome == Outcome.NONE;
        }

        /** "couldn't read after 3 tries: the truck didn't answer"; null when the read answered. */
        public String describeProblem() {
            if (answered()) return null;
            if (outcome == Outcome.REFUSED) return problem;
            return (codes.isEmpty() ? "couldn't read" : "couldn't read all of them")
                    + (attempts >= MAX_ATTEMPTS ? " after " + attempts + " tries" : "") + ": " + problem;
        }

        /** For the debug log, e.g. "[P1211] in 1 try" or "none in 3 tries". */
        public String logText() {
            String tries = attempts + (attempts == 1 ? " try" : " tries");
            switch (outcome) {
                case CODES: return codes + " in " + tries;
                case NONE: return "none in " + tries;
                default: return describeProblem() + (codes.isEmpty() ? "" : " " + codes);
            }
        }
    }

    /** What a code read needs from the adapter, so the retry rules run (and are tested) without one. */
    public interface Link {
        String requestCodes(Mode mode) throws IOException;

        /** Mode 01 PID 01's data bytes; null when the truck didn't answer. */
        int[] readPid01() throws IOException;

        /** Waits out the gap before a retry. Returns why to stop instead (e.g. ENGINE_STARTED), or null to go on. */
        String pauseBeforeRetry() throws IOException;
    }

    /** One full read: check engine light, stored codes, pending codes. */
    public static final class CodeRead {
        /** Null when PID 01 isn't supported or didn't answer. */
        public final MilStatus mil;
        public final Result stored;
        public final Result pending;
        /** Why the read stopped early (ENGINE_STARTED); null when it ran to the end. */
        public final String stoppedBecause;

        CodeRead(MilStatus mil, Result stored, Result pending, String stoppedBecause) {
            this.mil = mil;
            this.stored = stored;
            this.pending = pending;
            this.stoppedBecause = stoppedBecause;
        }
    }

    public enum Mode {
        STORED(0x03, 'S', "Stored",
                "Confirmed fault. If the check engine light is on, this is why."),
        PENDING(0x07, 'P', "Pending",
                "Seen once but not confirmed. It turns the check engine light on if it happens again.");

        public final int request;
        final char key;
        public final String label;
        public final String explanation;

        Mode(int request, char key, String label, String explanation) {
            this.request = request;
            this.key = key;
            this.label = label;
            this.explanation = explanation;
        }

        static Mode fromKey(char key) {
            for (Mode m : values()) if (m.key == key) return m;
            return null;
        }
    }

    public static final class DtcRecord {
        public final String code;
        public final String title;
        public final DtcSeverity severity;
        public final String hint; // may be null
        public final Mode mode;

        DtcRecord(String code, String title, DtcSeverity severity, String hint, Mode mode) {
            this.code = code;
            this.title = title;
            this.severity = severity;
            this.hint = hint;
            this.mode = mode;
        }
    }

    /** Check engine light state from Mode 01 PID 01. */
    public static final class MilStatus {
        public final boolean milOn;
        /** How many codes the truck says are stored. */
        public final int storedCount;

        MilStatus(boolean milOn, int storedCount) {
            this.milOn = milOn;
            this.storedCount = storedCount;
        }
    }

    private static final Pattern ISO_TP_FRAME = Pattern.compile("^([0-9A-F]):([0-9A-F]*)$");
    private static final Pattern ISO_TP_LENGTH = Pattern.compile("^[0-9A-F]{3}$");
    private static final Pattern HEX = Pattern.compile("^[0-9A-F]+$");
    private static final Pattern ELM_ERROR_NUMBER = Pattern.compile("^ERR\\d+$");

    private DTCReader() {}

    /**
     * Codes in the order the truck sent them, duplicates removed. Empty both when
     * there are none and when the read failed: use {@link #classify} to tell them apart.
     */
    public static List<String> parseCodes(String response, Mode mode) {
        Set<String> codes = new LinkedHashSet<>();
        if (response == null) return new ArrayList<>(codes);
        String header = hex(mode.request + 0x40);
        for (String message : splitMessages(response)) {
            if (message.startsWith(header)) decodePayload(message.substring(header.length()), codes);
        }
        return new ArrayList<>(codes);
    }

    /**
     * Adds a 43/47 payload's codes to {@code codes}. Returns false when the payload
     * isn't a whole reply: odd hex, nothing at all, or fewer codes than a CAN count promised.
     */
    private static boolean decodePayload(String payload, Set<String> codes) {
        if (payload.isEmpty() || payload.length() % 2 != 0) return false;
        int bytes = payload.length() / 2;
        int start = 0;
        int count = -1;
        if (bytes % 2 == 1) { // CAN: count byte first
            count = Integer.parseInt(payload.substring(0, 2), 16);
            start = 2;
            if (bytes < 1 + 2 * count) return false;
        }
        int found = 0;
        for (int i = start; i + 4 <= payload.length(); i += 4) {
            if (count >= 0 && found >= count) break;
            int a = Integer.parseInt(payload.substring(i, i + 2), 16);
            int b = Integer.parseInt(payload.substring(i + 2, i + 4), 16);
            if (a == 0 && b == 0) continue; // padding
            codes.add(decode(a, b));
            found++;
        }
        return true;
    }

    /**
     * Sorts one raw reply to a Mode 03/07 request. Only a real 43/47 reply can
     * say "no codes"; silence, errors and garbage are FAILED so they get retried
     * and are never shown as a clean result. Any adapter error in the reply fails
     * it, even next to data, since the error means some of the answer was lost.
     */
    public static Attempt classify(String response, Mode mode) {
        if (response == null || response.trim().isEmpty()) return Attempt.failed("the adapter didn't answer");
        if (response.indexOf('>') < 0) return Attempt.failed("the adapter's answer was cut off");

        String echo = hex(mode.request);
        boolean noData = false;
        for (String raw : response.split("[\\r\\n]+")) {
            String line = raw.replace(">", "").replaceAll("\\s", "").toUpperCase(Locale.US);
            if (line.isEmpty()) continue;
            String error = adapterError(line);
            if (error != null) return Attempt.failed(error);
            if (line.equals("NODATA")) {
                noData = true;
            } else if (!line.startsWith("SEARCHING") && !line.startsWith("BUSINIT") && !line.equals(echo)
                    && !HEX.matcher(line).matches() && !ISO_TP_FRAME.matcher(line).matches()) {
                return Attempt.failed("the answer didn't make sense");
            }
        }

        String header = hex(mode.request + 0x40);
        String refusal = "7F" + echo;
        Set<String> codes = new LinkedHashSet<>();
        boolean answered = false;
        int nrc = -2; // no 7F seen
        for (String message : splitMessages(response)) {
            if (message.equals(echo)) continue;
            if (message.startsWith(header)) {
                if (!decodePayload(message.substring(header.length()), codes)) {
                    return Attempt.failed("the answer was cut short");
                }
                answered = true;
            } else if (message.startsWith(refusal)) {
                nrc = message.length() >= 6 ? Integer.parseInt(message.substring(4, 6), 16) : -1;
            } else {
                return Attempt.failed("the answer didn't make sense");
            }
        }
        // Another module's 7F doesn't cancel a real answer.
        if (answered) {
            return new Attempt(codes.isEmpty() ? Reply.NONE : Reply.CODES, new ArrayList<>(codes), -1, null);
        }
        if (nrc == 0x21 || nrc == 0x78) {
            return Attempt.failed("the truck's computer was busy"); // "busy, ask again" / "still working"
        }
        if (nrc != -2) {
            return new Attempt(Reply.REFUSED, Collections.emptyList(), nrc,
                    "the truck's computer turned the request down (" + describeNrc(nrc) + ")");
        }
        if (noData) return new Attempt(Reply.NO_DATA, Collections.emptyList(), -1, NO_ANSWER);
        return Attempt.failed("the answer was empty");
    }

    /** Plain-English reason for an ELM327 error line, or null when the line isn't one. */
    private static String adapterError(String line) {
        if (line.equals("?")) return "the adapter didn't understand the request";
        if (line.contains("UNABLETOCONNECT")) return "the adapter couldn't connect to the truck's computer";
        if (line.startsWith("BUSINIT") && line.contains("ERROR")) return "the adapter couldn't start talking to the truck";
        if (line.contains("BUSERROR") || line.contains("CANERROR") || line.contains("BUSBUSY")
                || line.contains("DATAERROR") || line.contains("RXERROR") || line.contains("FBERROR")
                || ELM_ERROR_NUMBER.matcher(line).matches()) {
            return "the truck's data line had an error";
        }
        if (line.contains("STOPPED")) return "the request was interrupted";
        if (line.contains("BUFFERFULL")) return "the adapter ran out of room for the answer";
        if (line.contains("LVRESET") || line.startsWith("ELM327") || line.startsWith("STN")) {
            return "the adapter reset itself";
        }
        return null;
    }

    private static String describeNrc(int nrc) {
        String code = nrc < 0 ? "no reason given" : "code " + hex(nrc);
        switch (nrc) {
            case 0x11:
            case 0x12: return code + ": not supported";
            case 0x22: return code + ": conditions not right";
            case 0x31: return code + ": out of range";
            default: return code;
        }
    }

    /**
     * The verdict for one mode from every attempt. Codes from any attempt are
     * real and kept. NO DATA on every try counts as none only with proof the
     * truck's computer is answering: for stored codes, PID 01 reporting zero
     * stored and the light off; for pending, any real Mode 01 01 or Mode 03
     * reply in the same read ({@code pcmAnswered}). Fewer stored codes than
     * PID 01's count is an incomplete read, never a clean one.
     */
    public static Result decide(Mode mode, List<Attempt> attempts, MilStatus mil, boolean pcmAnswered) {
        Set<String> codes = new LinkedHashSet<>();
        boolean answered = false;
        boolean allNoData = !attempts.isEmpty();
        Attempt refusal = null;
        for (Attempt a : attempts) {
            codes.addAll(a.codes);
            if (a.answered()) answered = true;
            if (a.reply == Reply.REFUSED && refusal == null) refusal = a;
            if (a.reply != Reply.NO_DATA) allNoData = false;
        }
        List<String> found = new ArrayList<>(codes);
        int n = attempts.size();
        if (n == 0) return new Result(mode, Outcome.FAILED, found, "nothing was asked", 0);
        int expected = mode == Mode.STORED && mil != null ? mil.storedCount : 0;

        if (answered) {
            if (found.size() < expected) {
                return new Result(mode, Outcome.FAILED, found, countMismatch(expected, found.size()), n);
            }
            return new Result(mode, found.isEmpty() ? Outcome.NONE : Outcome.CODES, found, null, n);
        }
        if (refusal != null) return new Result(mode, Outcome.REFUSED, found, refusal.problem, n);
        if (allNoData) {
            if (mode == Mode.STORED && mil != null) {
                if (mil.storedCount > 0) return new Result(mode, Outcome.FAILED, found, countMismatch(mil.storedCount, 0), n);
                if (mil.milOn) {
                    return new Result(mode, Outcome.FAILED, found,
                            "the check engine light is on but the truck sent no codes", n);
                }
                return new Result(mode, Outcome.NONE, found, null, n);
            }
            if (mode == Mode.PENDING && pcmAnswered) return new Result(mode, Outcome.NONE, found, null, n);
            return new Result(mode, Outcome.FAILED, found, NO_ANSWER, n);
        }
        return new Result(mode, Outcome.FAILED, found, attempts.get(n - 1).problem, n);
    }

    private static String countMismatch(int expected, int got) {
        return "the truck says " + expected + (expected == 1 ? " code is" : " codes are") + " stored but "
                + (got == 0 ? "sent none" : "only " + got + " came through");
    }

    /**
     * Reads the check engine light, then stored, then pending codes, retrying
     * NO DATA and failures up to MAX_ATTEMPTS times each with a pause between.
     * An answer ends a mode at once, so a normal read is one request per mode.
     * If a pause says stop (the engine started), the read ends there and
     * everything still unread is FAILED with that reason.
     */
    public static CodeRead readAll(Link link, boolean pid01Supported) throws IOException {
        String stop = null;
        MilStatus mil = null;
        if (pid01Supported) {
            for (int tries = 1; ; tries++) {
                mil = parseMilStatus(link.readPid01());
                if (mil != null || tries >= MAX_ATTEMPTS) break;
                stop = link.pauseBeforeRetry();
                if (stop != null) break;
            }
        }

        List<Attempt> storedTries = new ArrayList<>();
        if (stop == null) stop = attempt(Mode.STORED, link, mil == null ? 0 : mil.storedCount, storedTries);
        Result stored = stop == null ? decide(Mode.STORED, storedTries, mil, false)
                : stopped(Mode.STORED, storedTries, stop);

        boolean pcmAnswered = mil != null;
        for (Attempt a : storedTries) pcmAnswered |= a.answered();
        List<Attempt> pendingTries = new ArrayList<>();
        if (stop == null) stop = attempt(Mode.PENDING, link, 0, pendingTries);
        Result pending = stop == null ? decide(Mode.PENDING, pendingTries, mil, pcmAnswered)
                : stopped(Mode.PENDING, pendingTries, stop);
        return new CodeRead(mil, stored, pending, stop);
    }

    /**
     * Asks for one mode until a reply ends it, or MAX_ATTEMPTS. An answer with
     * fewer codes than the truck says it has is asked again, keeping every code seen.
     * Returns why it stopped early, or null.
     */
    private static String attempt(Mode mode, Link link, int expected, List<Attempt> tries) throws IOException {
        Set<String> seen = new LinkedHashSet<>();
        while (true) {
            Attempt a = classify(link.requestCodes(mode), mode);
            tries.add(a);
            seen.addAll(a.codes);
            boolean done = a.answered() ? seen.size() >= expected : a.reply == Reply.REFUSED;
            if (done || tries.size() >= MAX_ATTEMPTS) return null;
            String stop = link.pauseBeforeRetry();
            if (stop != null) return stop;
        }
    }

    /** A mode the read gave up on early: FAILED with the stop reason, keeping any codes already seen. */
    static Result stopped(Mode mode, List<Attempt> tries, String reason) {
        Set<String> codes = new LinkedHashSet<>();
        for (Attempt a : tries) codes.addAll(a.codes);
        return new Result(mode, Outcome.FAILED, new ArrayList<>(codes), reason, tries.size());
    }

    private static String hex(int value) {
        return String.format(Locale.US, "%02X", value);
    }

    /**
     * Splits a raw reply into whole messages: one per plain data line, and one
     * per ISO-TP block (frames joined, trimmed to the stated length). Status
     * lines like SEARCHING..., BUS INIT: ...OK, NO DATA and "?" are dropped.
     */
    static List<String> splitMessages(String response) {
        List<String> messages = new ArrayList<>();
        StringBuilder block = null;
        int blockBytes = -1;
        for (String raw : response.split("[\\r\\n]+")) {
            String line = raw.replace(">", "").replaceAll("\\s", "").toUpperCase(Locale.US);
            if (line.isEmpty()) continue;
            if (ISO_TP_LENGTH.matcher(line).matches()) {
                addBlock(messages, block, blockBytes);
                block = new StringBuilder();
                blockBytes = Integer.parseInt(line, 16);
                continue;
            }
            Matcher frame = ISO_TP_FRAME.matcher(line);
            if (frame.matches()) {
                if (block == null) block = new StringBuilder();
                block.append(frame.group(2));
                continue;
            }
            if (!HEX.matcher(line).matches()) continue;
            addBlock(messages, block, blockBytes);
            block = null;
            blockBytes = -1;
            messages.add(line);
        }
        addBlock(messages, block, blockBytes);
        return messages;
    }

    private static void addBlock(List<String> messages, StringBuilder block, int blockBytes) {
        if (block == null || block.length() == 0) return;
        String data = block.toString();
        if (blockBytes > 0 && data.length() > blockBytes * 2) data = data.substring(0, blockBytes * 2);
        messages.add(data);
    }

    /**
     * SAE J2012: the top two bits pick P/C/B/U, the next two are the first
     * digit, and the remaining three nibbles are hex digits.
     */
    public static String decode(int a, int b) {
        char letter = "PCBU".charAt((a >> 6) & 0x3);
        return String.format(Locale.US, "%c%d%X%X%X", letter, (a >> 4) & 0x3, a & 0xF, (b >> 4) & 0xF, b & 0xF);
    }

    /** True when the truck acknowledged a Mode 04 clear ("44"). */
    public static boolean isClearAcknowledged(String response) {
        return splitMessages(response == null ? "" : response).contains("44");
    }

    /** Reads the light and stored-code count from PID 01's first byte; null when the truck didn't answer. */
    public static MilStatus parseMilStatus(int[] pid01Data) {
        if (pid01Data == null || pid01Data.length < 1) return null;
        return new MilStatus((pid01Data[0] & 0x80) != 0, pid01Data[0] & 0x7F);
    }

    public static DtcRecord describe(String code, Mode mode) {
        DtcDictionary.Entry entry = DtcDictionary.lookup(code);
        if (entry == null) {
            return new DtcRecord(code, "Not in AutoSentry's 7.3L code list yet", DtcSeverity.UNKNOWN, null, mode);
        }
        return new DtcRecord(code, entry.title, entry.severity, entry.hint, mode);
    }

    /** "P0470 (Exhaust Back Pressure Sensor Circuit Malfunction)", or just the code when not in the 7.3L list. */
    public static String describe(String code) {
        DtcDictionary.Entry entry = DtcDictionary.lookup(code);
        return entry != null ? code + " (" + entry.title + ")" : code;
    }
}
