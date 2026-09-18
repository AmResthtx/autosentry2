package com.autosentry.app.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "pid_definitions")
public class PIDDefinition {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public String name; // e.g., "RPM"
    public String command; // OBD PID hex or expression
    public int pollIntervalSeconds; // suggested polling interval
}
