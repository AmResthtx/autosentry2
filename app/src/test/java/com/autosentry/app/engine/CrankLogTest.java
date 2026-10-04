package com.autosentry.app.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CrankLogTest {
    private static final double NA = Double.NaN;

    @Test
    public void liveLineShowsSwingSinceLastReading() {
        CrankLog log = new CrankLog(1000);
        log.add(1000, 150, NA, NA, NA);
        assertEquals("Cranking 0.0 s: 150 rpm", log.liveLine());
        log.add(1500, 180, 420, 60, 10.8);
        assertEquals("Cranking 0.5 s: 180 rpm (+30), ICP 420 psi, IPR 60%, 10.8 V", log.liveLine());
        log.add(2000, 140, NA, NA, 10.6);
        assertEquals("Cranking 1.0 s: 140 rpm (-40), 10.6 V", log.liveLine());
    }

    @Test
    public void swingSkipsReadingsWithNoRpm() {
        CrankLog log = new CrankLog(0);
        log.add(0, 150, NA, NA, NA);
        log.add(400, NA, NA, NA, 10.9);
        log.add(800, 200, NA, NA, NA);
        assertEquals("Cranking 0.8 s: 200 rpm (+50)", log.liveLine());
    }

    @Test
    public void reportHasSummaryAndEveryReading() {
        CrankLog log = new CrankLog(0);
        log.add(0, 150, NA, NA, NA);
        log.add(500, 190, 600, 45, 10.4);
        log.add(1000, 650, 900, 30, 11.8);
        String report = log.report(true, 1000);
        assertTrue(report.contains("Result,Started\n"));
        assertTrue(report.contains("Seconds cranking,1.0\n"));
        assertTrue(report.contains("Lowest RPM,150\n"));
        assertTrue(report.contains("Peak ICP psi,900\n"));
        assertTrue(report.contains("Lowest battery V,10.4\n"));
        assertTrue(report.contains("Seconds,RPM,RPM change,ICP psi,IPR %,Battery V\n"
                + "0.0,150,,,,\n"
                + "0.5,190,+40,600,45,10.4\n"
                + "1.0,650,+460,900,30,11.8\n"));
        assertEquals("Started after 1.0 s of cranking: 150-650 rpm, peak ICP 900 psi, lowest 10.4 V",
                log.summary(true, 1000));
    }

    @Test
    public void noStartWithLowIcpSaysSo() {
        CrankLog log = new CrankLog(0);
        log.add(0, 160, 240, 85, 10.9);
        log.add(6000, 0, 250, 85, 11.9);
        String summary = log.summary(false, 6000);
        assertTrue(summary.startsWith("No start after 6.0 s of cranking: 160-160 rpm, peak ICP 250 psi"));
        assertTrue(summary.endsWith("ICP never reached the 500 psi needed to fire the injectors"));
        assertTrue(log.report(false, 6000).contains("Note,ICP never reached"));
    }

    @Test
    public void noIcpReadingMakesNoIcpClaim() {
        CrankLog log = new CrankLog(0);
        log.add(0, 160, NA, NA, NA);
        assertFalse(log.summary(false, 3000).contains("ICP"));
        assertTrue(log.report(false, 3000).contains("Peak ICP psi,\n"));
    }
}
