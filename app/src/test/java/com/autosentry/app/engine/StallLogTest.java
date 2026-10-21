package com.autosentry.app.engine;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.autosentry.app.obd.PidCatalog;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class StallLogTest {
    private static Map<Integer, Double> rpm(double value) {
        Map<Integer, Double> values = new HashMap<>();
        values.put(PidCatalog.RPM, value);
        return values;
    }

    @Test
    public void keepsOnlyTheLastMinute() {
        StallLog log = new StallLog();
        log.add(0, rpm(111));
        log.add(30_000, rpm(700));
        log.add(70_000, rpm(0));
        String report = log.report(70_000);
        assertFalse(report.contains("111"));
        assertTrue(report.contains("-40.0,700\n"));
        assertTrue(report.contains("0.0,0\n"));
    }

    @Test
    public void columnsCoverEveryReadingSeen() {
        StallLog log = new StallLog();
        log.add(0, rpm(700));
        Map<Integer, Double> withSpeed = rpm(690);
        withSpeed.put(PidCatalog.SPEED, 35.0);
        log.add(500, withSpeed);
        String report = log.report(1000);
        assertTrue(report.contains("Seconds from stall,Engine RPM (rpm),Vehicle Speed (mph)\n"));
        assertTrue(report.contains("-1.0,700,\n"));
        assertTrue(report.contains("-0.5,690,35\n"));
    }

    @Test
    public void snapshotIsACopy() {
        StallLog log = new StallLog();
        Map<Integer, Double> live = rpm(700);
        log.add(0, live);
        live.put(PidCatalog.RPM, 0.0);
        assertTrue(log.report(0).contains("0.0,700\n"));
    }
}
