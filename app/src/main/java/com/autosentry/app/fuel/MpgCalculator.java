package com.autosentry.app.fuel;

/**
 * Instantaneous MPG from MAF (grams/sec of intake air) and GPS speed.
 *
 * fuel flow (gal/hr) = maf(g/s) * 3600 / (AFR * fuelDensity(g/gal))
 * mpg = speed(mph) / fuel flow(gal/hr)
 *
 * Defaults are tuned for diesel (7.3L Powerstroke): stoichiometric AFR ~14.5:1,
 * #2 diesel density ~3167 g/gal. Swap constants via the overload if a gas
 * engine is ever connected.
 */
public final class MpgCalculator {
    public static final float DIESEL_AFR = 14.5f;
    public static final float DIESEL_DENSITY_G_PER_GAL = 3167f;

    private MpgCalculator() {}

    /** Returns fuel flow in gallons/hour for a given MAF reading. */
    public static double fuelGallonsPerHour(float mafGramsPerSec, float afr, float densityGramsPerGal) {
        if (mafGramsPerSec <= 0) return 0;
        return (mafGramsPerSec * 3600.0) / (afr * densityGramsPerGal);
    }

    public static double fuelGallonsPerHour(float mafGramsPerSec) {
        return fuelGallonsPerHour(mafGramsPerSec, DIESEL_AFR, DIESEL_DENSITY_G_PER_GAL);
    }

    /** Instantaneous MPG. Returns 0 when stationary or no airflow (avoids divide-by-zero noise). */
    public static double instantMpg(double speedMph, float mafGramsPerSec) {
        double gph = fuelGallonsPerHour(mafGramsPerSec);
        if (gph <= 0.01 || speedMph <= 0.5) return 0;
        return speedMph / gph;
    }

    /** Gallons consumed over an interval of durationSeconds at the given MAF rate. */
    public static double gallonsForInterval(float mafGramsPerSec, double durationSeconds) {
        double gph = fuelGallonsPerHour(mafGramsPerSec);
        return gph * (durationSeconds / 3600.0);
    }
}
