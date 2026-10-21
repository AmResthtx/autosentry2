package com.autosentry.app.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

@Dao
public interface VehicleUpgradeDao {
    @Insert
    void insert(VehicleUpgrade upgrade);

    @Update
    void update(VehicleUpgrade upgrade);

    @Query("SELECT * FROM vehicle_upgrades WHERE id = 1 LIMIT 1")
    VehicleUpgrade getSync();

    /**
     * Returns whether any upgrade has been explicitly confirmed by the user.
     * If true, we can reduce prompting frequency.
     */
    @Query("""
        SELECT CASE WHEN
            injectorUpgradeConfirmed = 1 OR
            turboUpgradeConfirmed = 1 OR
            tireUpgradeConfirmed = 1 OR
            camshaftUpgradeConfirmed = 1 OR
            fuelPumpUpgradeConfirmed = 1 OR
            alternatorUpgradeTimestamp > 0 OR
            engineTuneConfirmed = 1
        THEN 1 ELSE 0 END
        FROM vehicle_upgrades WHERE id = 1
    """)
    boolean hasConfirmedAnyUpgrade();
}
