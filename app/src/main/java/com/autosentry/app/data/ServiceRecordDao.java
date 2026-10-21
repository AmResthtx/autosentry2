package com.autosentry.app.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface ServiceRecordDao {
    @Insert
    long insert(ServiceRecord record);

    @Update
    void update(ServiceRecord record);

    @Query("SELECT * FROM service_records ORDER BY timestamp DESC")
    List<ServiceRecord> getAllSync();

    @Query("SELECT * FROM service_records WHERE serviceCategory = :category ORDER BY timestamp DESC")
    List<ServiceRecord> getByCategory(String category);

    @Query("SELECT * FROM service_records WHERE serviceType = :type ORDER BY timestamp DESC LIMIT :limit")
    List<ServiceRecord> latestOfType(String type, int limit);

    /**
     * Returns all upgrades ever installed, ordered by date.
     * Used for vehicle history report and resale value estimation.
     */
    @Query("SELECT * FROM service_records WHERE upgradeInstalled IS NOT NULL AND upgradeInstalled != '' ORDER BY timestamp DESC")
    List<ServiceRecord> getAllUpgradesInstalled();

    /**
     * Returns all repair records for a given category.
     * Useful for "common issues" tracking and trend analysis.
     */
    @Query("SELECT * FROM service_records WHERE serviceCategory = 'Repair' ORDER BY timestamp DESC")
    List<ServiceRecord> getAllRepairs();

    /**
     * Returns maintenance records in a date range.
     * Used for vehicle history export and resale documentation.
     */
    @Query("SELECT * FROM service_records WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp ASC")
    List<ServiceRecord> getInDateRange(long startTime, long endTime);
}
