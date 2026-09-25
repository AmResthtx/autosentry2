package com.autosentry.app.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

@Dao
public interface VehicleProfileDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insert(VehicleProfile profile);

    @Update
    void update(VehicleProfile profile);

    @Query("SELECT * FROM vehicle_profile WHERE id = 1 LIMIT 1")
    VehicleProfile getSync();

    @Query("SELECT * FROM vehicle_profile WHERE id = 1 LIMIT 1")
    LiveData<VehicleProfile> observe();

    /**
     * Adds one batch of driving as deltas in a single statement, so the
     * tracking service never overwrites odometer/oil changes made from the UI.
     */
    @Query("UPDATE vehicle_profile SET odometerMiles = odometerMiles + :miles, "
            + "milesSinceOilChange = milesSinceOilChange + :miles, "
            + "engineHoursSinceOilChange = engineHoursSinceOilChange + :hours, "
            + "totalFuelGallons = totalFuelGallons + :gallons, "
            + "oilLifePercent = MAX(0, oilLifePercent - :oilPercentUsed) WHERE id = 1")
    void applyDrive(double miles, double hours, double gallons, double oilPercentUsed);

    @Query("UPDATE vehicle_profile SET odometerMiles = :miles WHERE id = 1")
    void setOdometer(double miles);

    @Query("UPDATE vehicle_profile SET oilLifePercent = :oilLifePercent, milesSinceOilChange = :milesSinceChange, "
            + "engineHoursSinceOilChange = 0, lastOilResetTimestamp = :timestamp WHERE id = 1")
    void resetOil(double oilLifePercent, double milesSinceChange, long timestamp);
}
