package com.autosentry.app.obd;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Random;
import java.util.Set;

/**
 * Bench-test stand-in for the adapter, used only by debug builds with no
 * adapter paired (release builds never simulate). Answers a fixed PID set
 * with raw Mode 01 bytes so the real decoders run, and follows a repeating
 * drive cycle — 30 s idle, then 150 s at ~55 mph — so trips, odometer,
 * MPG and oil life all visibly move while testing.
 */
public class OBDSimulator {
    public static final Set<Integer> SUPPORTED;
    static {
        Set<Integer> ids = new LinkedHashSet<>();
        ids.add(PidCatalog.RPM);
        ids.add(PidCatalog.SPEED);
        ids.add(PidCatalog.COOLANT);
        ids.add(PidCatalog.MAF);
        ids.add(PidCatalog.ENGINE_OIL_TEMP);
        ids.add(0x42); // module voltage
        SUPPORTED = Collections.unmodifiableSet(ids);
    }

    private static final long CYCLE_MS = 180_000L;
    private static final long IDLE_MS = 30_000L;

    private final Random random = new Random();
    private final long startMs = System.currentTimeMillis();

    private boolean cruising() {
        return (System.currentTimeMillis() - startMs) % CYCLE_MS >= IDLE_MS;
    }

    private int jitter(int base, int spread) {
        return base + random.nextInt(spread * 2 + 1) - spread;
    }

    /** Raw data bytes for a PID, or null (no answer) for anything outside SUPPORTED. */
    public int[] readPid(int pid) {
        boolean cruising = cruising();
        switch (pid) {
            case PidCatalog.RPM: {
                int raw = jitter(cruising ? 1700 : 700, cruising ? 50 : 20) * 4;
                return new int[]{raw >> 8, raw & 0xFF};
            }
            case PidCatalog.SPEED:
                return new int[]{cruising ? jitter(89, 3) : 0}; // km/h
            case PidCatalog.COOLANT:
                return new int[]{jitter(88, 2) + 40};
            case PidCatalog.ENGINE_OIL_TEMP:
                return new int[]{jitter(95, 2) + 40};
            case PidCatalog.MAF: {
                int raw = jitter(cruising ? 4500 : 800, cruising ? 300 : 50); // g/s x100
                return new int[]{raw >> 8, raw & 0xFF};
            }
            case 0x42: {
                int mv = jitter(14100, 100);
                return new int[]{mv >> 8, mv & 0xFF};
            }
            default:
                return null;
        }
    }
}
