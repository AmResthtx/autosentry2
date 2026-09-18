package com.autosentry.app.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * A photo (receipt, part, install shot) attached to a MaintenanceEvent.
 * The point is a buyer being able to see verifiable, timestamped proof of
 * what was actually done/installed — not just a text note — so an owner
 * who upgraded or maintained the vehicle can substantiate that value at
 * resale, and a buyer isn't taking the seller's word for it.
 */
@Entity(tableName = "maintenance_attachments")
public class MaintenanceAttachment {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public long maintenanceEventId;
    public String filePath; // absolute path under this app's local storage
    public long timestamp;  // when the photo was attached (== capture/import time)
    public String caption;  // e.g. "Receipt", "Part number", "Installed"
}
