package com.autosentry.app.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** One drive/trip: from tracking start to tracking stop. */
@Entity(tableName = "sessions")
public class Session {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public long startTimestamp;
    public long endTimestamp; // 0 while active

    public double startOdometerMiles;
    public double distanceMiles;
    public double fuelGallonsUsed;
    public double avgMpg;
    public double maxSpeedMph;
}
