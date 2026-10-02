package com.autosentry.app.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface KoeoReportDao {
    @Insert
    long insert(KoeoReport report);

    @Update
    void update(KoeoReport report);

    @Query("SELECT * FROM koeo_reports ORDER BY timestamp DESC LIMIT :limit")
    List<KoeoReport> latest(int limit);
}
