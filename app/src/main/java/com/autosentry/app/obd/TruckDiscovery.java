package com.autosentry.app.obd;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * Asks the truck what it really answers, with nothing filtered or guessed, and writes down the
 * raw replies. FORScan reads far more than the standard Mode 01 list because Ford's own PIDs
 * (Mode 22, asked of the PCM directly) carry most 7.3L data; this finds which of those this
 * truck answers so each can be matched to what FORScan shows and added to the catalog.
 *
 * Three passes: who answers Mode 01 (with module addresses visible), every Mode 01 PID asked
 * one by one (the support bitmap can leave real ones out), then a Mode 22 sweep of the PCM.
 */
public final class TruckDiscovery {
    /** Where the 7.3L's enhanced PIDs sit (1310 oil temp, 1434 IPR and 1446 ICP are inside). */
    public static final int SWEEP_START = 0x1000;
    public static final int SWEEP_END = 0x1500;

    private static final long PROBE_TIMEOUT_MS = 1500L;
    private static final int MODE01_LAST = 0xC0;

    public interface Progress {
        void update(String text);
    }

    private final ELM327Adapter adapter;
    private final Progress progress;
    private final BooleanSupplier cancelled;
    private final StringBuilder out = new StringBuilder();

    public TruckDiscovery(ELM327Adapter adapter, Progress progress, BooleanSupplier cancelled) {
        this.adapter = adapter;
        this.progress = progress;
        this.cancelled = cancelled;
    }

    /** The report so far; complete once {@link #run} returns or throws. */
    public String report() {
        return out.toString();
    }

    public void run() throws IOException {
        line("Truck discovery — raw answers, nothing guessed.");
        line("Protocol: " + adapter.describeProtocol());
        line("");
        try {
            modulesAnswering();
            if (cancelled.getAsBoolean()) return;
            mode01OneByOne();
            if (cancelled.getAsBoolean()) return;
            mode22Sweep(SWEEP_START, SWEEP_END);
        } finally {
            try {
                adapter.restoreObdHeader(); // never leave the adapter addressing the PCM
            } catch (IOException ignored) {
            }
        }
    }

    /** Headers on, so each reply shows which module sent it (the PCM is not the only one on the bus). */
    private void modulesAnswering() throws IOException {
        line("== 1. Who answers Mode 01 support requests (headers on) ==");
        adapter.rawCommand("ATH1", 2500L);
        try {
            for (int base = 0x00; base <= 0xC0; base += 0x20) {
                String reply = adapter.rawCommand(String.format(Locale.US, "01%02X", base), base == 0 ? 15000L : 2500L);
                line(String.format(Locale.US, "01%02X -> %s", base, flat(reply)));
                if (!reply.contains("41" + String.format(Locale.US, "%02X", base))) break;
            }
        } finally {
            adapter.rawCommand("ATH0", 2500L);
        }
        line("");
    }

    private void mode01OneByOne() throws IOException {
        line("== 2. Mode 01, every PID asked individually (01-" + String.format(Locale.US, "%02X", MODE01_LAST) + ") ==");
        List<String> answered = new ArrayList<>();
        for (int pid = 0x01; pid <= MODE01_LAST; pid++) {
            if (cancelled.getAsBoolean()) return;
            if (pid % 0x20 == 0) continue; // support-bitmap PIDs, already shown above
            String reply = adapter.rawCommand(String.format(Locale.US, "01%02X", pid), PROBE_TIMEOUT_MS);
            int[] data = ELM327Adapter.parseMode01(reply, pid);
            if (data != null) {
                String hex = hex(data);
                answered.add(String.format(Locale.US, "%02X", pid));
                line(String.format(Locale.US, "  01%02X answers: %s", pid, hex));
            }
            if (pid % 16 == 0) progress.update(report() + String.format(Locale.US, "\n…Mode 01 at %02X", pid));
        }
        line("Mode 01 PIDs that answered: " + (answered.isEmpty() ? "none" : String.join(" ", answered)));
        line("");
    }

    private void mode22Sweep(int start, int end) throws IOException {
        line(String.format(Locale.US, "== 3. Mode 22 sweep of the PCM, %04X-%04X ==", start, end));
        line("Compare each answering PID's bytes with the value FORScan shows at the same moment.");
        List<String> answered = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        for (int pid = start; pid <= end; pid++) {
            if (cancelled.getAsBoolean()) break;
            String code = String.format(Locale.US, "%04X", pid);
            String reply = adapter.askPcm("22" + code, PROBE_TIMEOUT_MS);
            int[] data = ELM327Adapter.parseReply(reply, "62" + code);
            if (data != null) {
                answered.add(code);
                line("  22" + code + " answers: " + hex(data));
            } else if (flat(reply).replace(" ", "").contains("7F22")) {
                // The PCM knows this PID but won't give it now (reason code 31 = not supported, others = conditions).
                refused.add(code + "(" + flat(reply) + ")");
            }
            if ((pid - start) % 16 == 0) {
                progress.update(report() + String.format(Locale.US, "\n…Mode 22 at %04X of %04X, %d answering so far", pid, end, answered.size()));
            }
        }
        line("Mode 22 PIDs that answered: " + (answered.isEmpty() ? "none" : String.join(" ", answered)));
        if (!refused.isEmpty()) line("Refused with a negative reply: " + String.join(", ", refused));
    }

    private void line(String text) {
        out.append(text).append('\n');
        progress.update(report());
    }

    private static String flat(String reply) {
        return reply.replace(">", "").replaceAll("\\s+", " ").trim();
    }

    private static String hex(int[] data) {
        StringBuilder sb = new StringBuilder();
        for (int b : data) sb.append(String.format(Locale.US, "%02X ", b));
        return sb.toString().trim();
    }
}
