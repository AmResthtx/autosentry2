package com.autosentry.app.data;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface MaintenanceAttachmentDao {
    @Insert
    long insert(MaintenanceAttachment attachment);

    @Delete
    void delete(MaintenanceAttachment attachment);

    @Query("SELECT * FROM maintenance_attachments WHERE maintenanceEventId = :eventId ORDER BY timestamp ASC")
    List<MaintenanceAttachment> getForEvent(long eventId);

    @Query("SELECT COUNT(*) FROM maintenance_attachments WHERE maintenanceEventId = :eventId")
    int countForEvent(long eventId);
}
