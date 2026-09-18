package com.autosentry.app.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Single-row table (id always 1) holding the vehicle's running totals:
 * odometer, oil life, and fuel economy accumulators. This is what the old
 * app never updated — every field here must be mutated by OilLifeEngine /
 * TrackingService as the vehicle is driven, not left static after creation.
 */
@Entity(tableName = "vehicle_profile")
public class VehicleProfile {
    @PrimaryKey
    public long id = 1L;

    public double odometerMiles;

    public double milesSinceOilChange;
    public double engineHoursSinceOilChange;
    public double oilLifePercent = 100.0;
    public long lastOilResetTimestamp;

    public double totalFuelGallons;
    public double lifetimeAvgMpg;

    public static VehicleProfile newDefault() {
        VehicleProfile p = new VehicleProfile();
        p.id = 1L;
        p.odometerMiles = 0;
        p.milesSinceOilChange = 0;
        p.engineHoursSinceOilChange = 0;
        p.oilLifePercent = 100.0;
        p.lastOilResetTimestamp = System.currentTimeMillis();
        p.totalFuelGallons = 0;
        p.lifetimeAvgMpg = 0;
        return p;
    }
}
