package com.autosentry.app.obd;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DTC (Diagnostic Trouble Code) reader — parses Mode 03 (stored),
 * Mode 07 (pending), Mode 0A (permanent) codes from ELM327 responses.
 *
 * 7.3L Powerstroke common codes handled with descriptions.
 */
public class DTCReader {
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
        STORED_03, PENDING_07, PERMANENT_0A
    }

    public static class DTCRecord {
        public final String code;       // e.g. "P0236"
        public final String description; // Human-readable
        public final Mode mode;         // Where it was found
        public DTCRecord(String code, String description, Mode mode) {
            this.code = code;
            this.description = description;
            this.mode = mode;
        }
    }

    /**
     * Parse ELM327 response string for stored codes (Mode 03).
     * Response format for stored codes: "43 01 00 00 ... >" (count + 2-byte codes)
     * Example: 43 = 4 bytes = count (01) + 2 P-codes (each 2 bytes) + padding
     */
    public List<DTCRecord> parseStoredCodes(String response) throws Exception {
        return parseCodes(response, Mode.STORED_03);
    }

    /**
     * Parse pending codes (Mode 07).
     */
    public List<DTCRecord> parsePendingCodes(String response) throws Exception {
        return parseCodes(response, Mode.PENDING_07);
    }

    /**
     * Parse permanent codes (Mode 0A) — emissions-related, only cleared by repair.
     */
    public List<DTCRecord> parsePermanentCodes(String response) throws Exception {
        return parseCodes(response, Mode.PERMANENT_0A);
    }

    private List<DTCRecord> parseCodes(String response, Mode mode) throws Exception {
        List<DTCRecord> codes = new ArrayList<>();
        // Clean response: remove prompts, headers, whitespace
        String clean = response.replace(">", "").trim();
        // Split by lines; find the line that starts with the response header for the mode
        // Mode headers:
        // 03 -> 43 (stored)
        // 07 -> 47 (pending)
        // 0A -> 4A? Actually Mode 0A response is typically different; using generic parsing
        String[] lines = clean.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            // Look for P-codes embedded in hex response
            // A code like P0236 = hex 0236 = bytes 02 36
            // We scan for hex pairs and try to decode
            String[] tokens = trimmed.split("\\s+");
            for (String token : tokens) {
                // Skip mode response headers (43, 47, 4A) and single bytes that aren't pairs
                if (token.equals("43") || token.equals("47") || token.equals("4A")) continue;
                if (token.length() == 4 && token.matches("[0-9A-Fa-f]{4}")) {
                    String code = decodePCode(token);
                    if (code != null && !code.isEmpty()) {
                        codes.add(new DTCRecord(code, DTC_MAP.getOrDefault(code, "Unknown code — check OBD-II database"), mode));
                    }
                }
            }
        }
        return codes;
    }

    /**
     * Decode hex pair to P-code.
     * Example: hex "0236" -> "P0236" (P0xxx format)
     */
    private String decodePCode(String hex) {
        // Hex format: 2-byte code. First nibble indicates P/U/B/C, second indicates category.
        // We assume P-codes (Powertrain) for simplicity; real decoder would handle U/B/C categories.
        try {
            int value = Integer.parseInt(hex, 16);
            // Decode: first nibble indicates letter, next 3 indicate number
            // Simplified: treat as P-codes for 7.3L diagnostics
            int category = (value >> 12) & 0xF;
            int number = value & 0xFFF;
            char letter = 'P';
            switch (category) {
                case 0: letter = 'P'; break; // Powertrain
                case 1: letter = 'B'; break; // Body
                case 2: letter = 'C'; break; // Chassis
                case 3: letter = 'U'; break; // Network
                default: letter = 'P';
            }
            return String.format("%c%04d", letter, number);
        } catch (Exception e) {
            return null;
        }
    }

    public String getCommonCodeDescriptions() {
        StringBuilder sb = new StringBuilder();
        sb.append("7.3L Powerstroke Common DTCs:\n");
        for (String code : DTC_MAP.keySet()) {
            sb.append(code).append(" - ").append(DTC_MAP.get(code)).append("\n");
        }
        return sb.toString();
    }
}
