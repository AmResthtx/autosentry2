package com.autosentry.app.data;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface FuelFillUpDao {
    @Insert
    long insert(FuelFillUp fillUp);

    @Delete
    void delete(FuelFillUp fillUp);

    @Query("SELECT * FROM fuel_fillups ORDER BY timestamp ASC, id ASC")
    List<FuelFillUp> getAllSync();
}
