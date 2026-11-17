package com.autosentry.app.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * An app update must keep the truck's history. Each test builds a database file the way an
 * older AutoSentry left it (versions 1, 2 and 3, with rows in it), opens it with today's
 * code, and checks the rows are still there. Opening also makes Room compare the migrated
 * layout with the current one, so a migration that leaves a table wrong fails here, not on
 * the tablet.
 *
 * The old layouts are written out by hand: schema files were only exported from version 4 on
 * (app/schemas). From now on a layout change needs a new version, a Migration, and a test here.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
public class MigrationTest {
    private static final String NAME = "migration-test.db";

    private Context context;
    private AppDatabase room;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase(NAME);
    }

    @After
    public void tearDown() {
        if (room != null) room.close();
        context.deleteDatabase(NAME);
    }

    // ---- the layouts older versions created ----

    private static final String V1_PROFILE_EARLY = "CREATE TABLE vehicle_profile (id INTEGER NOT NULL, "
            + "odometerMiles REAL NOT NULL, milesSinceOilChange REAL NOT NULL, engineHoursSinceOilChange REAL NOT NULL, "
            + "oilLifePercent REAL NOT NULL, lastOilResetTimestamp INTEGER NOT NULL, totalFuelGallons REAL NOT NULL, "
            + "lifetimeAvgMpg REAL NOT NULL, PRIMARY KEY(id))";
    private static final String V1_PROFILE = V1_PROFILE_EARLY.replace(", PRIMARY KEY(id))",
            ", severeDuty INTEGER NOT NULL, PRIMARY KEY(id))");
    private static final String[] V1_OTHER = {
            "CREATE TABLE pid_definitions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT, command TEXT, "
                    + "pollIntervalSeconds INTEGER NOT NULL)",
            "CREATE TABLE pid_records (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, pidName TEXT, "
                    + "pidValue INTEGER NOT NULL, timestamp INTEGER NOT NULL, sessionId INTEGER NOT NULL)",
            "CREATE TABLE sessions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, startTimestamp INTEGER NOT NULL, "
                    + "endTimestamp INTEGER NOT NULL, startOdometerMiles REAL NOT NULL, distanceMiles REAL NOT NULL, "
                    + "fuelGallonsUsed REAL NOT NULL, avgMpg REAL NOT NULL, maxSpeedMph REAL NOT NULL)",
            "CREATE TABLE trip_points (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sessionId INTEGER NOT NULL, "
                    + "timestamp INTEGER NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, speedMph REAL NOT NULL, "
                    + "distanceDeltaMiles REAL NOT NULL, rpm INTEGER NOT NULL, mafGramsPerSec REAL NOT NULL, "
                    + "instantMpg REAL NOT NULL)",
            "CREATE TABLE maintenance_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, type TEXT, notes TEXT, "
                    + "timestamp INTEGER NOT NULL, odometerAtEvent REAL NOT NULL)",
    };
    private static final String[] V2_ADDED = {
            "ALTER TABLE maintenance_events ADD COLUMN title TEXT",
            "CREATE TABLE maintenance_attachments (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "maintenanceEventId INTEGER NOT NULL, filePath TEXT, timestamp INTEGER NOT NULL, caption TEXT)",
    };
    private static final String V3_ADDED = "CREATE TABLE koeo_reports (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
            + "timestamp INTEGER NOT NULL, keyOnVolts REAL NOT NULL, readings TEXT, storedCodes TEXT, pendingCodes TEXT, "
            + "crankAttempts INTEGER NOT NULL, crankSeconds REAL NOT NULL, minCrankVolts REAL NOT NULL, "
            + "started INTEGER NOT NULL)";

    /** A database as version {@code version} left it, holding one row per table that existed then. */
    private void createOld(int version, boolean earlyProfile) {
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(NAME), null);
        db.execSQL(earlyProfile ? V1_PROFILE_EARLY : V1_PROFILE);
        for (String sql : V1_OTHER) db.execSQL(sql);
        db.execSQL("INSERT INTO vehicle_profile VALUES (1, 187654.3, 2100.5, 61.2, 58.0, 1700000000000, 912.4, 14.6"
                + (earlyProfile ? "" : ", 0") + ")");
        db.execSQL("INSERT INTO sessions VALUES (7, 1700000100000, 1700003700000, 187600.0, 54.3, 3.7, 14.7, 71.0)");
        db.execSQL("INSERT INTO trip_points VALUES (70, 7, 1700000101000, 29.76, -95.37, 45.0, 0.0125, 1850, 31.5, 15.2)");
        db.execSQL("INSERT INTO pid_definitions VALUES (1, 'RPM', '010C', 1)");
        db.execSQL("INSERT INTO pid_records VALUES (1, 'RPM', 700, 1700000101000, 7)");
        db.execSQL("INSERT INTO maintenance_events (id, type, notes, timestamp, odometerAtEvent) "
                + "VALUES (3, 'OIL_CHANGE', '15W-40, Motorcraft FL-1995', 1699000000000, 185553.8)");
        if (version >= 2) {
            for (String sql : V2_ADDED) db.execSQL(sql);
            db.execSQL("UPDATE maintenance_events SET title = 'Oil and filter'");
            db.execSQL("INSERT INTO maintenance_attachments VALUES (5, 3, '/data/photos/IMG_1.jpg', 1699000001000, 'Receipt')");
        }
        if (version >= 3) {
            db.execSQL(V3_ADDED);
            db.execSQL("INSERT INTO koeo_reports VALUES (9, 1700000090000, 12.4, 'Engine RPM (rpm): 0', 'P1211', '', 1, 2.5, 10.1, 1)");
        }
        db.setVersion(version);
        db.close();
    }

    private SupportSQLiteDatabase openWithRoom() {
        room = AppDatabase.open(context, NAME);
        // Opening runs the migrations, then Room checks every table against the entities.
        return room.getOpenHelper().getWritableDatabase();
    }

    private static String one(SupportSQLiteDatabase db, String sql) {
        try (Cursor c = db.query(sql)) {
            assertTrue("no row for: " + sql, c.moveToFirst());
            return c.getString(0);
        }
    }

    /** A number column, read as a number: reading it as text rounds it to six digits. */
    private static double num(SupportSQLiteDatabase db, String sql) {
        try (Cursor c = db.query(sql)) {
            assertTrue("no row for: " + sql, c.moveToFirst());
            return c.getDouble(0);
        }
    }

    private static void assertDrivingHistoryKept(SupportSQLiteDatabase db) {
        assertEquals(187654.3, num(db, "SELECT odometerMiles FROM vehicle_profile"), 1e-9);
        assertEquals(58.0, num(db, "SELECT oilLifePercent FROM vehicle_profile"), 1e-9);
        assertEquals(912.4, num(db, "SELECT totalFuelGallons FROM vehicle_profile"), 1e-9);
        assertEquals(54.3, num(db, "SELECT distanceMiles FROM sessions WHERE id = 7"), 1e-9);
        assertEquals(185553.8, num(db, "SELECT odometerAtEvent FROM maintenance_events WHERE id = 3"), 1e-9);
        assertEquals("1850", one(db, "SELECT rpm FROM trip_points WHERE sessionId = 7"));
        assertEquals("15W-40, Motorcraft FL-1995", one(db, "SELECT notes FROM maintenance_events WHERE id = 3"));
        assertEquals("700", one(db, "SELECT pidValue FROM pid_records"));
        assertEquals("010C", one(db, "SELECT command FROM pid_definitions"));
    }

    @Test
    public void version1KeepsEveryRow() {
        createOld(1, false);
        SupportSQLiteDatabase db = openWithRoom();
        assertEquals(AppDatabase.VERSION, db.getVersion());
        assertDrivingHistoryKept(db);
        assertEquals("0", one(db, "SELECT severeDuty FROM vehicle_profile")); // the owner's choice survives
        assertNull(one(db, "SELECT title FROM maintenance_events WHERE id = 3"));
        assertEquals("0", one(db, "SELECT COUNT(*) FROM maintenance_attachments"));
        assertEquals("0", one(db, "SELECT COUNT(*) FROM koeo_reports"));
    }

    /** The first builds shipped version 1 without the severe-duty column; 1 to 2 adds it. */
    @Test
    public void earliestVersion1WithoutSevereDutyKeepsEveryRow() {
        createOld(1, true);
        SupportSQLiteDatabase db = openWithRoom();
        assertDrivingHistoryKept(db);
        assertEquals("1", one(db, "SELECT severeDuty FROM vehicle_profile"));
    }

    @Test
    public void version2KeepsEveryRowAndPhotoLinks() {
        createOld(2, false);
        SupportSQLiteDatabase db = openWithRoom();
        assertDrivingHistoryKept(db);
        assertEquals("Oil and filter", one(db, "SELECT title FROM maintenance_events WHERE id = 3"));
        assertEquals("/data/photos/IMG_1.jpg", one(db, "SELECT filePath FROM maintenance_attachments WHERE maintenanceEventId = 3"));
    }

    @Test
    public void version3KeepsEveryRowAndMarksOldEmptyCodeReadsUnconfirmed() {
        createOld(3, false);
        SupportSQLiteDatabase db = openWithRoom();
        assertDrivingHistoryKept(db);
        assertEquals("Receipt", one(db, "SELECT caption FROM maintenance_attachments"));
        assertEquals("P1211", one(db, "SELECT storedCodes FROM koeo_reports WHERE id = 9"));
        assertEquals(12.4, num(db, "SELECT keyOnVolts FROM koeo_reports WHERE id = 9"), 1e-9);
        // Codes that were read stay as they were; an old empty list is no longer shown as a clean "none".
        assertNull(one(db, "SELECT storedCodesProblem FROM koeo_reports WHERE id = 9"));
        assertEquals(AppDatabase.UNCONFIRMED_NONE, one(db, "SELECT pendingCodesProblem FROM koeo_reports WHERE id = 9"));
    }

    @Test
    public void currentVersionReopensWithItsRows() {
        createOld(3, false);
        openWithRoom();
        room.close();
        SupportSQLiteDatabase db = openWithRoom();
        assertDrivingHistoryKept(db);
        assertEquals("1", one(db, "SELECT COUNT(*) FROM koeo_reports"));
    }

    /** No destructive fallback: a file this build can't read makes it stop, and the file is left alone. */
    @Test
    public void aNewerDatabaseIsRefusedNotWiped() {
        createOld(3, false);
        SQLiteDatabase raw = SQLiteDatabase.openDatabase(context.getDatabasePath(NAME).getPath(), null,
                SQLiteDatabase.OPEN_READWRITE);
        raw.setVersion(AppDatabase.VERSION + 1);
        raw.close();
        try {
            openWithRoom();
            fail("opened a database from a newer version");
        } catch (IllegalStateException expected) {
            // Room has no path down and no permission to start over.
        }
        room.close();
        room = null;
        raw = SQLiteDatabase.openDatabase(context.getDatabasePath(NAME).getPath(), null, SQLiteDatabase.OPEN_READONLY);
        try (Cursor c = raw.rawQuery("SELECT odometerMiles FROM vehicle_profile", null)) {
            assertTrue(c.moveToFirst());
            assertEquals(187654.3, c.getDouble(0), 1e-9);
        } finally {
            raw.close();
        }
    }
}
