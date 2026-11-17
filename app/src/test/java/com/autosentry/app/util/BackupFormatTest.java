package com.autosentry.app.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BackupFormatTest {
    private static final String DB = "autosentry.db";

    @Test
    public void acceptsExactlyWhatABackupHolds() {
        assertTrue(BackupFormat.isSafeEntry("manifest.json", DB));
        assertTrue(BackupFormat.isSafeEntry("settings.json", DB));
        assertTrue(BackupFormat.isSafeEntry("db/autosentry.db", DB));
        assertTrue(BackupFormat.isSafeEntry("db/autosentry.db-wal", DB));
        assertTrue(BackupFormat.isSafeEntry("files/maintenance_photos/IMG_20260101_120000_000.jpg", DB));
        assertTrue(BackupFormat.isSafeEntry("files/reports/truck-systems.txt", DB));
    }

    @Test
    public void refusesAnythingThatCouldLandElsewhere() {
        assertFalse(BackupFormat.isSafeEntry("../shared_prefs/autosentry_settings.xml", DB));
        assertFalse(BackupFormat.isSafeEntry("files/maintenance_photos/../../databases/autosentry.db", DB));
        assertFalse(BackupFormat.isSafeEntry("/data/data/com.autosentry.app/files/x", DB));
        assertFalse(BackupFormat.isSafeEntry("files\\maintenance_photos\\x.jpg", DB));
        assertFalse(BackupFormat.isSafeEntry("files/logs/autosentry_debug_log.txt", DB));
        assertFalse(BackupFormat.isSafeEntry("files/maintenance_photos/sub/x.jpg", DB));
        assertFalse(BackupFormat.isSafeEntry("files/maintenance_photos/", DB));
        assertFalse(BackupFormat.isSafeEntry("db/other.db", DB));
        assertFalse(BackupFormat.isSafeEntry("db/autosentry.db-shm", DB));
        assertFalse(BackupFormat.isSafeEntry("lib/evil.so", DB));
        assertFalse(BackupFormat.isSafeEntry("", DB));
        assertFalse(BackupFormat.isSafeEntry(null, DB));
    }

    @Test
    public void restoresSameOrOlderDatabasesOnly() {
        assertNull(BackupFormat.refusal(BackupFormat.FORMAT, 4, 4));
        assertNull(BackupFormat.refusal(BackupFormat.FORMAT, 2, 4)); // migrated on first open
        assertNotNull(BackupFormat.refusal(BackupFormat.FORMAT, 5, 4));
        assertNotNull(BackupFormat.refusal(BackupFormat.FORMAT + 1, 4, 4));
        assertNotNull(BackupFormat.refusal(BackupFormat.FORMAT, 0, 4));
        assertNotNull(BackupFormat.refusal(-1, -1, 4));
    }
}
