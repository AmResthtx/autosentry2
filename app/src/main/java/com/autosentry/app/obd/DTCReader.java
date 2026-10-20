package com.autosentry.app.obd;

import java.util.ArrayList;
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
 */
public final class DTCReader {

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

    private DTCReader() {}

    /** Codes in the order the truck sent them, duplicates removed. Empty for NO DATA or no codes. */
    public static List<String> parseCodes(String response, Mode mode) {
        Set<String> codes = new LinkedHashSet<>();
        if (response == null) return new ArrayList<>(codes);
        String header = String.format(Locale.US, "%02X", mode.request + 0x40);

        for (String message : splitMessages(response)) {
            if (!message.startsWith(header) || message.length() % 2 != 0) continue;
            String payload = message.substring(header.length());
            int bytes = payload.length() / 2;
            int start = 0;
            int count = -1;
            if (bytes % 2 == 1) { // CAN: count byte first
                count = Integer.parseInt(payload.substring(0, 2), 16);
                start = 2;
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
        }
        return new ArrayList<>(codes);
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
}
