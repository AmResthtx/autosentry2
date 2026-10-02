package com.autosentry.app.obd;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Trouble codes from Mode 03 (stored), 07 (pending) and 0A (permanent)
 * replies, with descriptions for common 7.3L Power Stroke codes.
 *
 * Replies arrive with spaces and headers off (see ELM327Adapter). On J1850
 * (the 7.3L) each "43" line carries three 2-byte codes padded with 0000; on
 * CAN the first byte after "43" is a code count and long replies come as
 * numbered "0:", "1:" segments.
 */
public final class DTCReader {
    // 7.3L Powerstroke common DTC descriptions (subset)
    private static final Map<String, String> DTC_MAP = new HashMap<>();
    static {
        DTC_MAP.put("P0236", "MAP Sensor Circuit - Range/Performance (Boost low)");
        DTC_MAP.put("P0470", "Exhaust Back Pressure (EBP) Sensor Malfunction");
        DTC_MAP.put("P0541", "Intake Air Heater (IAH) Relay Circuit Low");
        DTC_MAP.put("P0603", "PCM Internal Memory Error");
        DTC_MAP.put("P1211", "Injection Control Pressure Above/Below Spec");
        DTC_MAP.put("P1212", "Injection Control Pressure Low During Crank");
        DTC_MAP.put("P1247", "Turbocharger Boost Pressure Low");
        DTC_MAP.put("P1248", "Turbocharger Boost Pressure High / Overboost");
        DTC_MAP.put("P1280", "Injection Control Pressure Circuit Low");
        DTC_MAP.put("P1670", "FICM Communication / Power Error");
        DTC_MAP.put("P0261", "Cylinder 1 Injector Circuit Low");
        DTC_MAP.put("P0264", "Cylinder 2 Injector Circuit Low");
        DTC_MAP.put("P0276", "Cylinder 6 Injector Circuit Low");
        DTC_MAP.put("P0401", "EGR Flow Insufficient (common 7.3L)");
        DTC_MAP.put("P0404", "EGR Position Sensor Range/Performance");
        DTC_MAP.put("P0606", "Processor / Internal Circuit Failure");
    }

    public enum Mode {
        STORED_03("03"), PENDING_07("07"), PERMANENT_0A("0A");

        public final String request;

        Mode(String request) {
            this.request = request;
        }

        String replyHeader() {
            return String.format(Locale.US, "%02X", Integer.parseInt(request, 16) + 0x40);
        }
    }

    private DTCReader() {}

    /** Codes in the reply, without duplicates (several modules can answer). Empty when none. */
    public static List<String> parse(String response, Mode mode, boolean canFormat) {
        Set<String> codes = new LinkedHashSet<>();
        if (response == null) return new ArrayList<>(codes);
        String header = mode.replyHeader();
        StringBuilder segments = new StringBuilder();
        for (String rawLine : response.split("[\\r\\n]+")) {
            String line = rawLine.replace(">", "").replaceAll("\\s", "").toUpperCase(Locale.US);
            if (line.matches("[0-9A-F]:[0-9A-F]*")) {
                segments.append(line.substring(2)); // CAN multi-frame piece
            } else if (line.startsWith(header) && line.matches("[0-9A-F]+")) {
                addCodes(line.substring(header.length()), canFormat, codes);
            }
        }
        if (segments.length() > 0 && segments.indexOf(header) == 0) {
            addCodes(segments.substring(header.length()), canFormat, codes);
        }
        return new ArrayList<>(codes);
    }

    private static void addCodes(String hex, boolean canFormat, Set<String> out) {
        int start = canFormat ? 2 : 0; // CAN: skip the code-count byte
        for (int i = start; i + 4 <= hex.length(); i += 4) {
            int b0 = Integer.parseInt(hex.substring(i, i + 2), 16);
            int b1 = Integer.parseInt(hex.substring(i + 2, i + 4), 16);
            if (b0 == 0 && b1 == 0) continue; // padding
            out.add(decode(b0, b1));
        }
    }

    /** SAE J2012: top 2 bits pick P/C/B/U, next 2 the first digit, then three hex digits. */
    static String decode(int b0, int b1) {
        char letter = "PCBU".charAt((b0 >> 6) & 3);
        return String.format(Locale.US, "%c%d%X%X%X", letter, (b0 >> 4) & 3, b0 & 0xF, (b1 >> 4) & 0xF, b1 & 0xF);
    }

    /** "P0470 (Exhaust Back Pressure (EBP) Sensor Malfunction)", or just the code when not in the table. */
    public static String describe(String code) {
        String text = DTC_MAP.get(code);
        return text != null ? code + " (" + text + ")" : code;
    }

    public static String getCommonCodeDescriptions() {
        StringBuilder sb = new StringBuilder();
        sb.append("7.3L Powerstroke Common DTCs:\n");
        for (String code : DTC_MAP.keySet()) {
            sb.append(code).append(" - ").append(DTC_MAP.get(code)).append("\n");
        }
        return sb.toString();
    }
}
