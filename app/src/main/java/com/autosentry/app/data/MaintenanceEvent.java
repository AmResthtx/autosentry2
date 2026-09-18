package com.autosentry.app.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "maintenance_events")
public class MaintenanceEvent {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public String type; // e.g. "OIL_CHANGE", "TIRE_ROTATION", "FILTER"
    public String notes;
    public long timestamp;
    public double odometerAtEvent;
}
