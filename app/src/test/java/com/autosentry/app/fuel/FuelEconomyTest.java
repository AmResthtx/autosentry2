package com.autosentry.app.fuel;

import com.autosentry.app.data.FuelFillUp;
import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.assertEquals;

public class FuelEconomyTest {
    private static FuelFillUp fill(double miles, double gallons, boolean full) {
        FuelFillUp f = new FuelFillUp();
        f.odometerMiles = miles;
        f.gallons = gallons;
        f.fullTank = full;
        return f;
    }

    @Test
    public void partialFillsCountOnlyAfterBaseline() {
        List<FuelEconomy.Tank> tanks = FuelEconomy.tanks(Arrays.asList(
                fill(900, 8, false), fill(1000, 20, true),
                fill(1200, 10, false), fill(1400, 15, true),
                fill(1700, 20, true)));
        assertEquals(2, tanks.size());
        assertEquals(25, tanks.get(0).gallons, 0.001);
        assertEquals(16, tanks.get(0).mpg, 0.001);
        assertEquals(15, tanks.get(1).mpg, 0.001);
        assertEquals(700.0 / 45, FuelEconomy.averageMpg(tanks), 0.001);
    }

    @Test
    public void noFullBaselineOrNoMilesProducesNoTank() {
        assertEquals(0, FuelEconomy.tanks(Arrays.asList(fill(100, 5, false),
                fill(100, 10, true))).size());
        assertEquals(0, FuelEconomy.tanks(Arrays.asList(fill(100, 5, true),
                fill(100, 10, true))).size());
    }
}
