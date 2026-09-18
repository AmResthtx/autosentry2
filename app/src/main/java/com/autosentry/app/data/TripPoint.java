package com.autosentry.app.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** One GPS fix during a session, plus the OBD snapshot taken alongside it. */
@Entity(tableName = "trip_points")
public class TripPoint {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public long sessionId;
    public long timestamp;

    public double latitude;
    public double longitude;
    public double speedMph;
    public double distanceDeltaMiles; // distance since previous point

    public int rpm;
    public float mafGramsPerSec;
    public double instantMpg;
}
