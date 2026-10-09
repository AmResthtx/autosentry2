package com.autosentry.app.ui;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.autosentry.app.obd.PidCatalog;

import org.junit.Test;

public class GaugeScaleTest {
    private static final int OIL_TEMP = 0x5C;
    private static final int COOLANT = 0x05;
    private static final int BATTERY = 0x42;
    private static final int ENGINE_LOAD = 0x04;
    private static final int RUN_TIME = 0x1F;
    private static final int COMPUTED_TRIP_MILES = 0x1002;

    @Test
    public void scaleSpansCriticalLimitsWithNormalBandInside() {
        GaugeScale scale = GaugeScale.forPid(OIL_TEMP);
        assertNotNull(scale);
        assertEquals(80, scale.min, 0);
        assertEquals(260, scale.max, 0);
        assertEquals(120, scale.normalMin, 0);
        assertEquals(220, scale.normalMax, 0);
        assertTrue(scale.hasNormalRange());
    }

    @Test
    public void negativeSentinelFloorBecomesZero() {
        assertEquals(0, GaugeScale.forPid(ENGINE_LOAD).min, 0);
    }

    @Test
    public void readingsWithoutAnyRangeHaveNoScale() {
        assertNull(GaugeScale.forPid(RUN_TIME));
        assertNull(GaugeScale.forPid(COMPUTED_TRIP_MILES));
        assertNull(GaugeScale.forPid(0x31)); // distance since codes cleared: a counter
    }

    @Test
    public void readingsWithOnlyADisplayRangeGetAScaleWithoutANormalBand() {
        GaugeScale icp = GaugeScale.forPid(PidCatalog.FORD_ICP);
        assertNotNull(icp);
        assertEquals(0, icp.min, 0);
        assertEquals(4000, icp.max, 0);
        assertFalse(icp.hasNormalRange());
        assertEquals(GaugeScale.Level.OK, GaugeScale.level(PidCatalog.FORD_ICP, 3900));
    }

    @Test
    public void fractionIsClampedAndNanSitsAtZero() {
        GaugeScale scale = GaugeScale.forPid(OIL_TEMP);
        assertEquals(0.5, scale.fraction(170), 1e-9);
        assertEquals(0, scale.fraction(-500), 0);
        assertEquals(1, scale.fraction(900), 0);
        assertEquals(0, scale.fraction(Double.NaN), 0);
    }

    @Test
    public void levelFollowsToleranceBands() {
        assertEquals(GaugeScale.Level.OK, GaugeScale.level(OIL_TEMP, 200));
        assertEquals(GaugeScale.Level.WATCH, GaugeScale.level(OIL_TEMP, 231));
        assertEquals(GaugeScale.Level.CRITICAL, GaugeScale.level(OIL_TEMP, 270));
        assertEquals(GaugeScale.Level.OK, GaugeScale.level(COMPUTED_TRIP_MILES, 12));
    }

    @Test
    public void notWarmedUpIsNotAFaultForTemperaturesOnly() {
        assertEquals(GaugeScale.Level.OK, GaugeScale.level(OIL_TEMP, 75 + 10)); // cold, inside critical floor
        assertEquals(GaugeScale.Level.OK, GaugeScale.level(COOLANT, 60));
        assertEquals(GaugeScale.Level.WATCH, GaugeScale.level(COOLANT, 230));
        assertEquals(GaugeScale.Level.WATCH, GaugeScale.level(BATTERY, 11)); // low volts is still a fault
    }

    @Test
    public void majorTicksLandOnRoundNumbers() {
        assertArrayEquals(new double[]{1000, 2000, 3000, 4000}, GaugeScale.forPid(0x0C).majorTicks(), 1e-9);
        assertArrayEquals(new double[]{100, 150, 200, 250}, GaugeScale.forPid(OIL_TEMP).majorTicks(), 1e-9);
        assertEquals(2.5, GaugeScale.niceStep(12, 5), 1e-9);
    }
}
