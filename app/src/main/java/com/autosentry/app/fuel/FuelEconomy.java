package com.autosentry.app.fuel;

import com.autosentry.app.data.FuelFillUp;

import java.util.ArrayList;
import java.util.List;

/**
 * True MPG from pump fill-ups. A full fill-up refills exactly what was burned
 * since the previous full fill-up, so miles / gallons added over that span is
 * real MPG. Partial fills in between add to the gallons but end no interval.
 */
public final class FuelEconomy {
    private FuelEconomy() {}

    public static final class Tank {
        public final FuelFillUp start;      // previous full fill-up
        public final FuelFillUp end;        // full fill-up closing the interval
        public final double miles;
        public final double gallons;        // all fuel added after start, through end
        public final double mpg;

        Tank(FuelFillUp start, FuelFillUp end, double miles, double gallons) {
            this.start = start;
            this.end = end;
            this.miles = miles;
            this.gallons = gallons;
            this.mpg = gallons > 0 ? miles / gallons : 0;
        }
    }

    /** Fill-ups must be sorted by timestamp ascending. Returns tanks oldest first. */
    public static List<Tank> tanks(List<FuelFillUp> fillUps) {
        List<Tank> out = new ArrayList<>();
        FuelFillUp lastFull = null;
        double gallons = 0;
        for (FuelFillUp f : fillUps) {
            if (lastFull == null) {
                if (f.fullTank) lastFull = f;
                continue;
            }
            gallons += f.gallons;
            if (!f.fullTank) continue;
            double miles = f.odometerMiles - lastFull.odometerMiles;
            if (miles > 0 && gallons > 0) {
                out.add(new Tank(lastFull, f, miles, gallons));
            }
            lastFull = f;
            gallons = 0;
        }
        return out;
    }

    /** Overall MPG across tanks (total miles / total gallons); 0 if none. */
    public static double averageMpg(List<Tank> tanks) {
        double miles = 0, gallons = 0;
        for (Tank t : tanks) {
            miles += t.miles;
            gallons += t.gallons;
        }
        return gallons > 0 ? miles / gallons : 0;
    }

}
