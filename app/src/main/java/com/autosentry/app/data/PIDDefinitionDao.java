package com.autosentry.app.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;
import androidx.room.Delete;

import java.util.List;

@Dao
public interface PIDDefinitionDao {
    @Insert
    long insert(PIDDefinition def);

    @Update
    void update(PIDDefinition def);

    @Delete
    void delete(PIDDefinition def);

    @Query("SELECT * FROM pid_definitions ORDER BY name ASC")
    List<PIDDefinition> all();
}
