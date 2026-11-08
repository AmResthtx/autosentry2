package com.autosentry.app.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** One pump fill-up. Full-tank fills bound the intervals used for true MPG. */
@Entity(tableName = "fuel_fillups")
public class FuelFillUp {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public long timestamp;
    public double odometerMiles;
    public double gallons;
    public boolean fullTank;
    public double pricePerGallon;       // 0 = not entered
    // App's running fuel estimate (VehicleProfile.totalFuelGallons) when logged; 0 if none.
    public double estimatedTotalGallons;
}
