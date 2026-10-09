package com.autosentry.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class GaugeScaleTest {
    private static final int OIL_TEMP = 0x5C;
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
    }

    @Test
    public void negativeSentinelFloorBecomesZero() {
        assertEquals(0, GaugeScale.forPid(ENGINE_LOAD).min, 0);
    }

    @Test
    public void readingsWithoutADrawableBandHaveNoScale() {
        assertNull(GaugeScale.forPid(RUN_TIME));
        assertNull(GaugeScale.forPid(COMPUTED_TRIP_MILES));
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
}
