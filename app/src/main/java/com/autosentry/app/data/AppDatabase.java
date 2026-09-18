package com.autosentry.app.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(
    entities = {
        PIDDefinition.class,
        PIDRecord.class,
        VehicleProfile.class,
        Session.class,
        TripPoint.class,
        MaintenanceEvent.class,
        MaintenanceAttachment.class
    },
    version = 2,
    exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {
    public abstract PIDDefinitionDao pidDefinitionDao();
    public abstract PIDRecordDao pidRecordDao();
    public abstract VehicleProfileDao vehicleProfileDao();
    public abstract SessionDao sessionDao();
    public abstract TripPointDao tripPointDao();
    public abstract MaintenanceDao maintenanceDao();
    public abstract MaintenanceAttachmentDao maintenanceAttachmentDao();

    /**
     * Adds photo-attachment support without wiping data. This tablet has a
     * live install accumulating real trip/maintenance history — destructive
     * migration is not an option here.
     */
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE maintenance_events ADD COLUMN title TEXT");
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS maintenance_attachments (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "maintenanceEventId INTEGER NOT NULL, " +
                "filePath TEXT, " +
                "timestamp INTEGER NOT NULL, " +
                "caption TEXT)"
            );
        }
    };

    private static volatile AppDatabase INSTANCE;

    public static AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            AppDatabase.class,
                            "autosentry.db"
                    ).addMigrations(MIGRATION_1_2).build();
                }
            }
        }
        return INSTANCE;
    }
}
