package com.autosentry.app.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "pid_records")
public class PIDRecord {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public String pidName;  // e.g., "RPM", "ODO", "COOLANT_TEMP"
    public int pidValue;
    public long timestamp;
    public long sessionId;  // FK to sessions table (0 if no active session)
}
