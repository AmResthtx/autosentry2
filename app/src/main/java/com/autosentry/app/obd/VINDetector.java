package com.autosentry.app.obd;

/**
 * VIN (Vehicle Identification Number) detector.
 * Uses Mode 01 PID 02 (VIN Message Count) + Mode 09 PIDs 02/0A to read VIN.
 * For 7.3L Powerstroke trucks, VIN is stored in ECU; auto-detect removes
 * user from having to manually enter VIN each time.
 */
public class VINDetector {
    private final ELM327Adapter adapter;

    public VINDetector(ELM327Adapter adapter) {
        this.adapter = adapter;
    }

    /**
     * Attempt VIN detection via adapter.
     * Returns VIN if successful; null/empty if adapter not connected or VIN unavailable.
     */
    public String detectVIN() {
        try {
            if (!adapter.isConnected()) return null;
            // Mode 09 PID 02: VIN (first 17 chars across multiple messages)
            adapter.sendCommand("09 02");
            String response = adapter.readResponse();
            // Parse VIN from response (hex-encoded ASCII)
            String extracted = parseVINFromResponse(response);
            return (extracted != null && extracted.length() >= 17) ? extracted.trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String parseVINFromResponse(String response) {
        // VIN messages typically contain hex-encoded ASCII characters.
        // Example response line for VIN: series of hex pairs representing VIN chars.
        try {
            String clean = response.replace(">", "").replaceAll("[^0-9A-Fa-f\\s]", "").trim();
            StringBuilder vinBuilder = new StringBuilder();
            String[] tokens = clean.split("\\s+");
            for (String token : tokens) {
                if (token.length() == 2) {
                    int charCode = Integer.parseInt(token, 16);
                    if (charCode >= 32 && charCode <= 126) {
                        vinBuilder.append((char) charCode);
                    }
                }
            }
            String result = vinBuilder.toString();
            // VIN must be 17 chars; filter to first 17 alphanumeric
            if (result.length() >= 17) {
                return result.substring(0, Math.min(17, result.length()));
            }
            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            return null;
        }
    }
}
