package com.autosentry.app.obd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PidCatalogTest {
    private static double decode(int id, int... data) {
        PidCatalog.Pid pid = PidCatalog.get(id);
        assertNotNull("missing pid " + Integer.toHexString(id), pid);
        return pid.decode(data);
    }

    @Test
    public void coolantTempConvertsCelsiusToFahrenheit() {
        assertEquals(194.0, decode(0x05, 130), 1e-9); // 90 C
    }

    @Test
    public void loadAndPedalReadingsAreScaledToPercent() {
        assertEquals(100.0, decode(0x43, 0x00, 0xFF), 1e-9);
        assertEquals(100.0, decode(0x45, 255), 1e-9);
        assertEquals(0.0, decode(0x2C, 0), 1e-9);
    }

    @Test
    public void signedAndOffsetReadingsUseTheStandardFormulas() {
        assertEquals(-100.0, decode(0x2D, 0), 1e-9);
        assertEquals(0.0, decode(0x2D, 128), 1e-9);
        assertEquals(0.0, decode(0x62, 125), 1e-9);
        assertEquals(-210.0, decode(0x5D, 0, 0), 1e-9);
        assertEquals(0.0, decode(0x5D, 0x69, 0x00), 1e-9); // 26880 / 128 - 210
    }

    @Test
    public void troubleCodeCountIgnoresTheCheckEngineBit() {
        assertEquals(3.0, decode(0x01, 0x83, 0, 0, 0), 1e-9);
    }

    @Test
    public void distancesConvertKilometersToMiles() {
        assertEquals(62.1371, decode(0x31, 0x00, 100), 1e-4);
    }

    @Test
    public void fordEnhancedReadingsAreNotMode01AndHaveARange() {
        PidCatalog.Pid icp = PidCatalog.get(PidCatalog.FORD_ICP);
        assertTrue(icp.isEnhanced());
        assertEquals(4000, icp.displayMax, 0);
        assertTrue(Double.isNaN(icp.decode(new int[]{1, 2})));
    }
}
