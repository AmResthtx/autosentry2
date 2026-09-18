package com.autosentry.app.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "maintenance_events")
public class MaintenanceEvent {
    @PrimaryKey(autoGenerate = true)
    public long id;

    // A ServiceItemType.name() for scheduled items (OIL_FILTER, etc.), or
    // "UPGRADE" / "OTHER" for anything outside the manufacturer schedule
    // (mods, custom work) that still deserves a documented, photo-backed record.
    public String type;
    public String title; // short label, e.g. "Steel front bumper" — mainly for UPGRADE/OTHER
    public String notes;
    public long timestamp;
    public double odometerAtEvent;
}
