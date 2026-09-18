package com.autosentry.app.data;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(
    entities = {
        PIDDefinition.class,
        PIDRecord.class,
        VehicleProfile.class,
        Session.class,
        TripPoint.class,
        MaintenanceEvent.class
    },
    version = 1,
    exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {
    public abstract PIDDefinitionDao pidDefinitionDao();
    public abstract PIDRecordDao pidRecordDao();
    public abstract VehicleProfileDao vehicleProfileDao();
    public abstract SessionDao sessionDao();
    public abstract TripPointDao tripPointDao();
    public abstract MaintenanceDao maintenanceDao();

    private static volatile AppDatabase INSTANCE;

    public static AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            AppDatabase.class,
                            "autosentry.db"
                    ).build();
                }
            }
        }
        return INSTANCE;
    }
}
