package com.autosentry.app.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.autosentry.app.R;
import com.autosentry.app.data.AppDatabase;
import com.autosentry.app.data.MaintenanceEvent;
import com.autosentry.app.data.Session;
import com.autosentry.app.data.TripPoint;
import com.autosentry.app.data.VehicleProfile;
import com.autosentry.app.maintenance.MaintenanceScheduleEngine;
import com.autosentry.app.maintenance.ServiceItemType;
import com.autosentry.app.maintenance.ServiceStatus;
import com.autosentry.app.service.TrackingService;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = this::refreshFromDb;
    private static final long REFRESH_INTERVAL_MS = 1500L;

    private boolean tracking = false;
    private AppDatabase db;

    private TextView textOilLife, textOdometer, textSpeed, textMpg, textRpm, textCoolant, textTrip;
    private Button buttonToggleTracking, buttonResetOil, buttonServiceStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        db = AppDatabase.getInstance(this);

        textOilLife = findViewById(R.id.textOilLife);
        textOdometer = findViewById(R.id.textOdometer);
        textSpeed = findViewById(R.id.textSpeed);
        textMpg = findViewById(R.id.textMpg);
        textRpm = findViewById(R.id.textRpm);
        textCoolant = findViewById(R.id.textCoolant);
        textTrip = findViewById(R.id.textTrip);
        buttonToggleTracking = findViewById(R.id.buttonToggleTracking);
        buttonResetOil = findViewById(R.id.buttonResetOil);
        buttonServiceStatus = findViewById(R.id.buttonServiceStatus);

        if (!PermissionFlow.hasAllPermissions(this)) {
            PermissionFlow.requestAllPermissions(this);
        }

        buttonToggleTracking.setOnClickListener(v -> toggleTracking());
        buttonResetOil.setOnClickListener(v -> resetOilLife());
        buttonServiceStatus.setOnClickListener(v -> showServiceStatus());

        ioExecutor.execute(() -> {
            if (db.vehicleProfileDao().getSync() == null) {
                db.vehicleProfileDao().insert(VehicleProfile.newDefault());
            }
        });
    }

    private void toggleTracking() {
        if (!PermissionFlow.hasAllPermissions(this)) {
            PermissionFlow.requestAllPermissions(this);
            return;
        }
        if (!tracking) {
            Intent intent = new Intent(this, TrackingService.class);
            startForegroundService(intent);
            tracking = true;
            buttonToggleTracking.setText("Stop Trip");
        } else {
            stopService(new Intent(this, TrackingService.class));
            tracking = false;
            buttonToggleTracking.setText("Start Trip");
        }
    }

    private void resetOilLife() {
        ioExecutor.execute(() -> {
            VehicleProfile profile = db.vehicleProfileDao().getSync();
            if (profile == null) return;
            profile.oilLifePercent = 100.0;
            profile.milesSinceOilChange = 0;
            profile.engineHoursSinceOilChange = 0;
            profile.lastOilResetTimestamp = System.currentTimeMillis();
            db.vehicleProfileDao().update(profile);

            MaintenanceEvent event = new MaintenanceEvent();
            event.type = ServiceItemType.OIL_FILTER.name();
            event.notes = "Logged from dashboard";
            event.timestamp = System.currentTimeMillis();
            event.odometerAtEvent = profile.odometerMiles;
            db.maintenanceDao().insert(event);
        });
    }

    /**
     * Shows %-of-life used for every manufacturer-scheduled item (Ford's
     * 7.3L Power Stroke severe-duty intervals by default), flagging anything
     * at or past MaintenanceScheduleEngine.DUE_SOON_THRESHOLD_PERCENT (80%).
     */
    private void showServiceStatus() {
        ioExecutor.execute(() -> {
            VehicleProfile profile = db.vehicleProfileDao().getSync();
            if (profile == null) return;
            List<ServiceStatus> statuses = MaintenanceScheduleEngine.computeAll(profile, db.maintenanceDao());

            StringBuilder sb = new StringBuilder();
            for (ServiceStatus status : statuses) {
                String flag = status.overdue ? " — OVERDUE" : (status.dueSoon ? " — DUE SOON" : "");
                sb.append(String.format("%s: %.0f%% used, %.0f mi remaining%s\n",
                        status.displayName, status.percentOfLifeUsed, status.milesRemaining(), flag));
            }

            uiHandler.post(() -> new AlertDialog.Builder(this)
                    .setTitle(profile.severeDuty ? "Service Schedule (Severe Duty)" : "Service Schedule (Normal Duty)")
                    .setMessage(sb.toString())
                    .setPositiveButton("Close", null)
                    .show());
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        uiHandler.post(refreshTask);
    }

    @Override
    protected void onPause() {
        super.onPause();
        uiHandler.removeCallbacks(refreshTask);
    }

    private void refreshFromDb() {
        ioExecutor.execute(() -> {
            VehicleProfile profile = db.vehicleProfileDao().getSync();
            Session session = db.sessionDao().getActive();
            TripPoint lastPoint = session != null ? db.tripPointDao().getLastForSession(session.id) : null;

            uiHandler.post(() -> {
                if (profile != null) {
                    textOilLife.setText(String.format("Oil life: %.0f%%", profile.oilLifePercent));
                    textOdometer.setText(String.format("Odometer: %.1f mi", profile.odometerMiles));
                }
                if (lastPoint != null) {
                    textSpeed.setText(String.format("Speed: %.0f mph", lastPoint.speedMph));
                    textMpg.setText(String.format("Instant MPG: %.1f", lastPoint.instantMpg));
                    textRpm.setText(String.format("RPM: %d", lastPoint.rpm));
                }
                if (session != null) {
                    textTrip.setText(String.format("Trip: %.1f mi, %.2f gal, %.1f avg mpg",
                            session.distanceMiles, session.fuelGallonsUsed, session.avgMpg));
                }
            });
        });
        uiHandler.postDelayed(refreshTask, REFRESH_INTERVAL_MS);
    }
}
