package com.autosentry.app.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface PIDRecordDao {
    @Insert
    long insert(PIDRecord record);

    @Query("SELECT * FROM pid_records ORDER BY timestamp DESC LIMIT :limit")
    List<PIDRecord> latest(int limit);

    @Query("SELECT * FROM pid_records WHERE pidName = :name ORDER BY timestamp DESC LIMIT :limit")
    List<PIDRecord> latestByName(String name, int limit);
}
