package com.autosentry.app.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface MaintenanceDao {
    @Insert
    long insert(MaintenanceEvent event);

    @Query("SELECT * FROM maintenance_events ORDER BY timestamp DESC")
    LiveData<List<MaintenanceEvent>> observeAll();

    @Query("SELECT * FROM maintenance_events WHERE type = :type ORDER BY timestamp DESC LIMIT 1")
    MaintenanceEvent getMostRecentOfType(String type);
}
