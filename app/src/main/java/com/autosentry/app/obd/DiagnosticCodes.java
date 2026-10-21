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

    /** One trouble-code read: what was found and whether the light was on. */
    public static final class Scan {
        public final long timestamp;
        /** Null when the truck didn't report the light state. */
        public final Boolean milOn;
        /** Worst first; a code that's both stored and pending is listed once, as stored. */
        public final List<DTCReader.DtcRecord> codes;

        public Scan(long timestamp, Boolean milOn, List<DTCReader.DtcRecord> codes) {
            this.timestamp = timestamp;
            this.milOn = milOn;
            List<DTCReader.DtcRecord> sorted = new ArrayList<>(codes);
            Collections.sort(sorted, (x, y) -> {
                int bySeverity = Integer.compare(x.severity.ordinal(), y.severity.ordinal());
                return bySeverity != 0 ? bySeverity : Integer.compare(x.mode.ordinal(), y.mode.ordinal());
            });
            this.codes = Collections.unmodifiableList(sorted);
        }

        /** Builds a scan from raw stored/pending code lists. */
        public static Scan of(long timestamp, Boolean milOn, List<String> stored, List<String> pending) {
            List<DTCReader.DtcRecord> records = new ArrayList<>();
            for (String code : stored) records.add(DTCReader.describe(code, DTCReader.Mode.STORED));
            for (String code : pending) {
                if (!stored.contains(code)) records.add(DTCReader.describe(code, DTCReader.Mode.PENDING));
            }
            return new Scan(timestamp, milOn, records);
        }

        /**
         * Compact form for settings storage: "1;timestamp;mil;P1211:S,P0340:P".
         * Only codes and modes are stored, so titles and severity always come
         * from the current code list.
         */
        public String serialize() {
            StringBuilder sb = new StringBuilder("1;").append(timestamp).append(';')
                    .append(milOn == null ? "-" : (milOn ? "1" : "0")).append(';');
            for (int i = 0; i < codes.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(codes.get(i).code).append(':').append(codes.get(i).mode.key);
            }
            return sb.toString();
        }

        /** Null when the stored text is missing or from an unknown format. */
        public static Scan deserialize(String text) {
            if (text == null) return null;
            String[] parts = text.split(";", -1);
            if (parts.length != 4 || !parts[0].equals("1")) return null;
            try {
                long timestamp = Long.parseLong(parts[1]);
                Boolean mil = parts[2].equals("-") ? null : parts[2].equals("1");
                List<DTCReader.DtcRecord> records = new ArrayList<>();
                if (!parts[3].isEmpty()) {
                    for (String item : parts[3].split(",")) {
                        String[] codeMode = item.split(":");
                        if (codeMode.length != 2 || codeMode[1].length() != 1) continue;
                        DTCReader.Mode mode = DTCReader.Mode.fromKey(codeMode[1].charAt(0));
                        if (mode != null) records.add(DTCReader.describe(codeMode[0].toUpperCase(Locale.US), mode));
                    }
                }
                return new Scan(timestamp, mil, records);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    private static final AtomicReference<Request> pending = new AtomicReference<>(Request.NONE);

    /** True while the service is reading or clearing codes. */
    public static volatile boolean busy = false;
    /** Outcome of the last request worth telling the user about (e.g. "Codes cleared"); null when there's nothing to say. */
    public static volatile String message = null;
    /** Latest scan this run; the tab falls back to the saved one when null. */
    public static volatile Scan lastScan = null;

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
