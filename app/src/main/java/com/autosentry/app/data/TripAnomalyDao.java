package com.autosentry.app.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface TripAnomalyDao {
    @Insert
    long insert(TripAnomaly anomaly);

    @Query("SELECT * FROM trip_anomalies WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    List<TripAnomaly> getForTrip(long sessionId);

    @Query("SELECT * FROM trip_anomalies WHERE pidId = :pidId ORDER BY timestamp DESC LIMIT :limit")
    List<TripAnomaly> latestByPid(int pidId, int limit);

    @Query("SELECT * FROM trip_anomalies WHERE severity = :severity ORDER BY timestamp DESC")
    List<TripAnomaly> getBySeverity(String severity);
}
