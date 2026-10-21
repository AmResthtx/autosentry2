package com.autosentry.app.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface SessionDao {
    @Insert
    long insert(Session session);

    @Update
    void update(Session session);

    @Query("SELECT * FROM sessions ORDER BY startTimestamp DESC")
    LiveData<List<Session>> observeAll();

    @Query("SELECT * FROM sessions ORDER BY startTimestamp ASC")
    List<Session> getAllSync();

    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1")
    Session getById(long id);

    @Query("SELECT * FROM sessions WHERE endTimestamp = 0 ORDER BY startTimestamp DESC LIMIT 1")
    Session getActive();

    /** Trip miles AutoSentry recorded since a moment in time (never-logged service items count these). */
    @Query("SELECT COALESCE(SUM(distanceMiles), 0) FROM sessions WHERE startTimestamp >= :sinceMillis")
    double milesSince(long sinceMillis);
}
