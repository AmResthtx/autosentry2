package com.autosentry.app.obd;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Hand-off between the trouble codes on the Checks tab and TrackingService, which owns the adapter
 * connection. The tab drops a request here; the service picks it up on its
 * next poll, talks to the truck, and publishes the result, the same way
 * LiveReadings carries live values.
 */
public final class DiagnosticCodes {

    public enum Request { NONE, SCAN, CLEAR }

    /** One trouble-code read: what each mode found (or why it couldn't), and whether the light was on. */
    public static final class Scan {
        public final long timestamp;
        /** Null when the truck didn't report the light state. */
        public final Boolean milOn;
        public final DTCReader.Result stored;
        public final DTCReader.Result pending;
        /**
         * Every code the truck sent, worst first, including the part of an incomplete read
         * that came through. A code that's both stored and pending is listed once, as stored.
         */
        public final List<DTCReader.DtcRecord> codes;

        public Scan(long timestamp, Boolean milOn, DTCReader.Result stored, DTCReader.Result pending) {
            this.timestamp = timestamp;
            this.milOn = milOn;
            this.stored = stored;
            this.pending = pending;
            List<DTCReader.DtcRecord> sorted = new ArrayList<>();
            for (String code : stored.codes) sorted.add(DTCReader.describe(code, DTCReader.Mode.STORED));
            for (String code : pending.codes) {
                if (!stored.codes.contains(code)) sorted.add(DTCReader.describe(code, DTCReader.Mode.PENDING));
            }
            Collections.sort(sorted, (x, y) -> {
                int bySeverity = Integer.compare(x.severity.ordinal(), y.severity.ordinal());
                return bySeverity != 0 ? bySeverity : Integer.compare(x.mode.ordinal(), y.mode.ordinal());
            });
            this.codes = Collections.unmodifiableList(sorted);
        }

        public static Scan of(long timestamp, DTCReader.CodeRead read) {
            return new Scan(timestamp, read.mil == null ? null : read.mil.milOn, read.stored, read.pending);
        }

        /** Both modes answered, so the list is the whole truth, "no codes" included. */
        public boolean complete() {
            return stored.answered() && pending.answered();
        }

        /**
         * Good enough to clear from: the stored codes were really read, and the pending ones
         * were read or the truck flatly refused them (nothing more to save first).
         */
        public boolean succeeded() {
            return stored.answered() && (pending.answered() || pending.outcome == DTCReader.Outcome.REFUSED);
        }

        /** "no codes" only when both modes answered; otherwise says the read fell short. */
        public String countText() {
            if (codes.isEmpty()) {
                if (complete()) return "no codes";
                return stored.answered() || pending.answered() ? "read incomplete" : "couldn't read codes";
            }
            String count = codes.size() + (codes.size() == 1 ? " code" : " codes");
            return complete() ? count : count + ", read incomplete";
        }

        /** "Couldn't read trouble codes after 3 tries", or null when both modes answered. */
        public String problemHeadline() {
            boolean storedBad = !stored.answered();
            boolean pendingBad = !pending.answered();
            if (!storedBad && !pendingBad) return null;
            String what = storedBad && pendingBad ? "trouble codes" : (storedBad ? "stored codes" : "pending codes");
            boolean partial = (storedBad && !stored.codes.isEmpty()) || (pendingBad && !pending.codes.isEmpty());
            int tries = Math.max(storedBad ? stored.attempts : 0, pendingBad ? pending.attempts : 0);
            return "Couldn't read " + (partial ? "all " : "") + what
                    + (tries >= DTCReader.MAX_ATTEMPTS ? " after " + tries + " tries" : "");
        }

        /** Why, one sentence per mode that fell short (one in all when they match); null when nothing did. */
        public String problemDetail() {
            boolean storedBad = !stored.answered();
            boolean pendingBad = !pending.answered();
            if (storedBad && pendingBad && stored.problem.equals(pending.problem)) return sentence(stored.problem);
            StringBuilder sb = new StringBuilder();
            if (storedBad) sb.append("Stored codes: ").append(stored.problem).append('.');
            if (pendingBad) {
                if (sb.length() > 0) sb.append('\n');
                sb.append("Pending codes: ").append(pending.problem).append('.');
            }
            return sb.length() == 0 ? null : sb.toString();
        }

        private static String sentence(String text) {
            return Character.toUpperCase(text.charAt(0)) + text.substring(1) + ".";
        }

        /**
         * Compact form for settings storage:
         * "2;timestamp;mil;P1211:S,P0340:P;stored;pending", where each mode is
         * "outcome|attempts|problem" (e.g. "F|3|the truck didn't answer"). Only codes
         * and modes are stored, so titles and severity always come from the current code list.
         */
        public String serialize() {
            StringBuilder sb = new StringBuilder("2;").append(timestamp).append(';')
                    .append(milOn == null ? "-" : (milOn ? "1" : "0")).append(';');
            String sep = "";
            for (DTCReader.Result result : new DTCReader.Result[]{stored, pending}) {
                for (String code : result.codes) {
                    sb.append(sep).append(code).append(':').append(result.mode.key);
                    sep = ",";
                }
            }
            sb.append(';').append(serialize(stored)).append(';').append(serialize(pending));
            return sb.toString();
        }

        private static String serialize(DTCReader.Result result) {
            String problem = result.problem == null ? "" : result.problem.replaceAll("[;\\r\\n]", ",");
            return result.outcome.key + "|" + result.attempts + "|" + problem;
        }

        /**
         * Null when the stored text is missing or unreadable. Scans saved before reads
         * were classified ("1;...") are dropped too: their "no codes" may have been a failed
         * read, so the tab says "not scanned yet" until the next read instead of repeating it.
         */
        public static Scan deserialize(String text) {
            if (text == null) return null;
            String[] parts = text.split(";", -1);
            if (parts.length != 6 || !parts[0].equals("2")) return null;
            try {
                long timestamp = Long.parseLong(parts[1]);
                Boolean mil = parts[2].equals("-") ? null : parts[2].equals("1");
                List<String> storedCodes = new ArrayList<>();
                List<String> pendingCodes = new ArrayList<>();
                if (!parts[3].isEmpty()) {
                    for (String item : parts[3].split(",")) {
                        String[] codeMode = item.split(":");
                        if (codeMode.length != 2 || codeMode[1].length() != 1) continue;
                        DTCReader.Mode mode = DTCReader.Mode.fromKey(codeMode[1].charAt(0));
                        String code = codeMode[0].toUpperCase(Locale.US);
                        if (mode == DTCReader.Mode.STORED) storedCodes.add(code);
                        if (mode == DTCReader.Mode.PENDING) pendingCodes.add(code);
                    }
                }
                DTCReader.Result stored = deserialize(parts[4], DTCReader.Mode.STORED, storedCodes);
                DTCReader.Result pending = deserialize(parts[5], DTCReader.Mode.PENDING, pendingCodes);
                if (stored == null || pending == null) return null;
                return new Scan(timestamp, mil, stored, pending);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private static DTCReader.Result deserialize(String text, DTCReader.Mode mode, List<String> codes) {
            String[] fields = text.split("\\|", 3);
            if (fields.length != 3 || fields[0].length() != 1) return null;
            DTCReader.Outcome outcome = DTCReader.Outcome.fromKey(fields[0].charAt(0));
            if (outcome == null) return null;
            String problem = fields[2].isEmpty() ? null : fields[2];
            // A result that didn't answer always says why; one that did never needs to.
            if ((problem == null) != (outcome == DTCReader.Outcome.CODES || outcome == DTCReader.Outcome.NONE)) {
                return null;
            }
            return new DTCReader.Result(mode, outcome, codes, problem, Integer.parseInt(fields[1]));
        }
    }

    private static final AtomicReference<Request> pending = new AtomicReference<>(Request.NONE);

    /** True while the service is reading or clearing codes. */
    public static volatile boolean busy = false;
    /** Outcome of the last request worth telling the user about (e.g. "Codes cleared"); null when there's nothing to say. */
    public static volatile String message = null;
    /** Latest scan this run; the tab falls back to the saved one when null. */
    public static volatile Scan lastScan = null;
    /** The codes as they were just before each clear, newest first; null until a clear this run (the tab falls back to the saved list). */
    public static volatile List<Scan> beforeClear = null;

    private DiagnosticCodes() {}

    public static void request(Request request) {
        message = null;
        pending.set(request);
    }

    /** Takes the waiting request, leaving NONE behind. */
    public static Request take() {
        return pending.getAndSet(Request.NONE);
    }
}
