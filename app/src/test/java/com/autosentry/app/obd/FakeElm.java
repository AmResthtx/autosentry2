package com.autosentry.app.obd;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A simulated OBDLink plugged into a 2000 F-250 7.3L (J1850 PWM), for tests.
 * Records every command it receives. While monitoring (ATMA) it emits
 * broadcast traffic on a timer: A8 29 10 every 50 ms with a dropout from
 * 300 to 650 ms, A8 49 10 every 100 ms, and an occasional E8 FF 40.
 */
final class FakeElm {
    static final String VIN = "1FTNW21F0YEA00000";

    /** Every command line the adapter received, in order. */
    final List<String> received = new ArrayList<>();
    /** Only the requests that would go out on the bus. */
    final List<String> busRequests = new ArrayList<>();
    /** Bytes written while monitoring (the stop key), which aren't commands. */
    int stopKeys = 0;

    private final Map<String, Deque<String>> overrides = new HashMap<>();
    private final StringBuilder pending = new StringBuilder();
    private final StringBuilder line = new StringBuilder();
    private boolean headersOn = false;
    private String header = "default"; // as the guard labels it before any ATSH
    private boolean monitoring = false;
    private long monitorStart;
    private long nextA, nextB;
    private boolean sporadic1, sporadic2;

    /** The next replies to {@code command}, one per ask, before the normal answer. */
    void override(String command, String... replies) {
        Deque<String> queue = overrides.computeIfAbsent(command, k -> new ArrayDeque<>());
        for (String r : replies) queue.add(r);
    }

    int count(String command) {
        int n = 0;
        for (String c : received) if (c.equals(command)) n++;
        return n;
    }

    final InputStream in = new InputStream() {
        @Override
        public int available() {
            synchronized (FakeElm.this) {
                pump();
                return pending.length();
            }
        }

        @Override
        public int read() {
            synchronized (FakeElm.this) {
                pump();
                if (pending.length() == 0) return -1;
                char c = pending.charAt(0);
                pending.deleteCharAt(0);
                return c;
            }
        }

        @Override
        public int read(byte[] b, int off, int len) {
            synchronized (FakeElm.this) {
                pump();
                int n = Math.min(len, pending.length());
                byte[] bytes = pending.substring(0, n).getBytes(StandardCharsets.US_ASCII);
                System.arraycopy(bytes, 0, b, off, n);
                pending.delete(0, n);
                return n;
            }
        }

        @Override
        public long skip(long n) {
            synchronized (FakeElm.this) {
                int k = (int) Math.min(n, pending.length());
                pending.delete(0, k);
                return k;
            }
        }
    };

    final OutputStream out = new OutputStream() {
        @Override
        public void write(int b) {
            synchronized (FakeElm.this) {
                if (monitoring) { // any byte stops ATMA
                    monitoring = false;
                    stopKeys++;
                    pending.append("STOPPED\r\r>");
                    return;
                }
                if (b == '\r') {
                    handle(line.toString());
                    line.setLength(0);
                } else {
                    line.append((char) b);
                }
            }
        }
    };

    private void handle(String raw) {
        String cmd = raw.trim().toUpperCase(Locale.US);
        received.add(cmd);
        String reply = reply(cmd);
        if (reply != null) pending.append(reply).append("\r\r>");
    }

    private String reply(String cmd) {
        if (cmd.startsWith("AT") || cmd.startsWith("ST")) return adapter(cmd);
        busRequests.add("[" + header + "] " + cmd);
        Deque<String> queue = overrides.get(cmd);
        if (queue != null && !queue.isEmpty()) return queue.poll();
        String data = truck(cmd);
        if (data == null) return "NO DATA";
        StringBuilder sb = new StringBuilder();
        for (String frame : data.split("\r")) {
            if (sb.length() > 0) sb.append('\r');
            sb.append(headersOn ? "6CF110" + frame + "AA" : frame);
        }
        return sb.toString();
    }

    private String adapter(String cmd) {
        switch (cmd) {
            case "ATI": return "ELM327 v1.4b";
            case "AT@1": return "OBDLink MX+";
            case "STI": return "STN2255 v5.6.19";
            case "ATRV": return "12.6V";
            case "ATDP": return "AUTO, SAE J1850 PWM";
            case "ATDPN": return "A1";
            case "ATH1": headersOn = true; return "OK";
            case "ATH0": headersOn = false; return "OK";
            case "ATMA":
                monitoring = true;
                monitorStart = System.currentTimeMillis();
                nextA = 0;
                nextB = 0;
                sporadic1 = sporadic2 = false;
                return null; // no prompt while monitoring
            default:
                if (cmd.startsWith("ATSH")) header = cmd.substring(4);
                return "OK";
        }
    }

    /** The truck's answer (data bytes, frames split by \r), or null for NO DATA. */
    private String truck(String cmd) {
        if (cmd.startsWith("22")) {
            String pid = cmd.substring(2);
            if (header.equals("C410F1")) {
                switch (pid) {
                    case "1310": return "6213102328";
                    case "1446": return "621446037D";
                    case "1434": return "62143480";
                    case "1127": return "6211270102";
                    default: return "7F2231";
                }
            }
            return null;
        }
        switch (cmd) {
            case "0100": return "410008182000"; // PIDs 05, 0C, 0D, 13
            case "0105": return "41057B";
            case "010C": return "410C1AF8";
            case "010D": return "410D00";
            case "0113": return "411303";
            case "020200": return "4202000470";
            case "0900": return "490054400000";
            case "0902": return vinFrames();
            case "0904": return "4904014D4F434B\r4904024341204C";
            case "03": return "43047000000000";
            case "07": return "47000000000000";
            default: return null;
        }
    }

    /** J1850 Mode 09 02: five frames of four bytes, three zero bytes then the 17 VIN characters. */
    private static String vinFrames() {
        String bytes = "000000";
        for (char c : VIN.toCharArray()) bytes += String.format(Locale.US, "%02X", (int) c);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            if (sb.length() > 0) sb.append('\r');
            sb.append(String.format(Locale.US, "4902%02X", i + 1)).append(bytes, i * 8, i * 8 + 8);
        }
        return sb.toString();
    }

    /** While monitoring, adds every broadcast frame that's due. */
    private void pump() {
        if (!monitoring) return;
        long t = System.currentTimeMillis() - monitorStart;
        while (nextA <= t) {
            if (nextA < 300 || nextA >= 650) pending.append("A829101234").append(String.format(Locale.US, "%02X", (int) (nextA / 50) & 0xFF)).append("5D\r");
            nextA += 50;
        }
        while (nextB <= t) {
            pending.append("A849100B2277\r");
            nextB += 100;
        }
        if (!sporadic1 && t >= 120) {
            sporadic1 = true;
            pending.append("E8FF40030011\r");
        }
        if (!sporadic2 && t >= 410) {
            sporadic2 = true;
            pending.append("E8FF40030022\r");
        }
    }
}
