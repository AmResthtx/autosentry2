package com.autosentry.app.obd;

import java.util.Random;

/**
 * Simulates OBD-II adapter responses for development and testing.
 * In production, this class is replaced by real Bluetooth ELM327/OBDLink communication.
 */
public class OBDSimulator {
    private final Random random = new Random();
    private int baseRPM = 750; // idle RPM
    private int oscillationCounter = 0;

    /**
     * Simulates reading RPM from an OBD-II adapter.
     * Occasionally introduces instability for testing the detection algorithm.
     */
    public int readRPM() {
        oscillationCounter++;
        int noise = random.nextInt(40) - 20; // +/- 20 RPM normal noise

        // Every 10th reading, simulate a large RPM swing (instability)
        if (oscillationCounter % 10 == 0) {
            int spike = (random.nextBoolean() ? 1 : -1) * (200 + random.nextInt(150));
            return baseRPM + spike + noise;
        }
        return baseRPM + noise;
    }

    /**
     * Simulates reading coolant temperature (degrees C).
     */
    public int readCoolantTemp() {
        return 85 + random.nextInt(15); // 85-100 C normal operating range
    }

    /**
     * Raw Mode 01 data bytes for a PID, shaped like the real adapter's
     * answer so the same decoders run against simulated data.
     */
    public int[] readPid(int pid) {
        switch (pid) {
            case 0x0C: { int raw = readRPM() * 4; return new int[]{raw >> 8, raw & 0xFF}; }
            case 0x05: return new int[]{readCoolantTemp() + 40};
            case 0x10: { int raw = Math.round(readMAF() * 100); return new int[]{raw >> 8, raw & 0xFF}; }
            case 0x0D: return new int[]{0};
            case 0x42: { int mv = readBatteryVoltage(); return new int[]{mv >> 8, mv & 0xFF}; }
            default: return new int[]{100 + random.nextInt(20), random.nextInt(256)};
        }
    }

    /**
     * Simulates reading battery voltage (millivolts).
     */
    public int readBatteryVoltage() {
        return 13500 + random.nextInt(1500); // 13.5-15.0V
    }

    /**
     * Simulates Mass Air Flow (grams/sec). Roughly scales with RPM above idle
     * so MpgCalculator has something realistic to chew on during dev.
     */
    public float readMAF() {
        int rpm = readRPM();
        float base = 2.0f + Math.max(0, (rpm - baseRPM)) * 0.03f;
        float noise = (random.nextFloat() - 0.5f) * 0.5f;
        return Math.max(0.5f, base + noise);
    }
}
