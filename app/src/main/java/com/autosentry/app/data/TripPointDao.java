package com.autosentry.app.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface TripPointDao {
    @Insert
    long insert(TripPoint point);

    @Query("SELECT * FROM trip_points WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    List<TripPoint> getForSession(long sessionId);

    @Query("SELECT * FROM trip_points WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT 1")
    TripPoint getLastForSession(long sessionId);

    @Query("SELECT * FROM trip_points WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT 50")
    LiveData<List<TripPoint>> observeRecent(long sessionId);
}
