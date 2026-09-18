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
}
