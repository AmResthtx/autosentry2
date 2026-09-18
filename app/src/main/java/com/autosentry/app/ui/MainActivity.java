package com.autosentry.app.ui;

import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

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
import com.autosentry.app.settings.AppSettings;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = this::refreshFromDb;
    private static final long REFRESH_INTERVAL_MS = 1500L;

    private AppDatabase db;

    private TextView textAdapterStatus, textOilLife, textOdometer, textSpeed, textMpg, textRpm, textCoolant, textTrip;
    private Button buttonToggleTracking, buttonResetOil, buttonServiceStatus, buttonPairAdapter, buttonAutoTrackingToggle, buttonDebugLog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        db = AppDatabase.getInstance(this);

        textAdapterStatus = findViewById(R.id.textAdapterStatus);
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
        buttonPairAdapter = findViewById(R.id.buttonPairAdapter);
        buttonAutoTrackingToggle = findViewById(R.id.buttonAutoTrackingToggle);
        buttonDebugLog = findViewById(R.id.buttonDebugLog);

        if (!PermissionFlow.hasAllPermissions(this)) {
            PermissionFlow.requestAllPermissions(this);
        }

        buttonToggleTracking.setOnClickListener(v -> toggleTracking());
        buttonResetOil.setOnClickListener(v -> resetOilLife());
        buttonServiceStatus.setOnClickListener(v -> showServiceStatus());
        buttonPairAdapter.setOnClickListener(v -> pickObdAdapter());
        buttonAutoTrackingToggle.setOnClickListener(v -> toggleAutoTracking());
        buttonDebugLog.setOnClickListener(v -> startActivity(new Intent(this, DebugLogActivity.class)));

        ioExecutor.execute(() -> {
            if (db.vehicleProfileDao().getSync() == null) {
                db.vehicleProfileDao().insert(VehicleProfile.newDefault());
            }
        });

        refreshAdapterStatusUi();
    }

    /**
     * Manual override — mainly useful for testing against the simulator
     * without an adapter paired, since with an adapter configured tracking
     * starts/stops itself via BluetoothConnectionReceiver.
     */
    private void toggleTracking() {
        if (!PermissionFlow.hasAllPermissions(this)) {
            PermissionFlow.requestAllPermissions(this);
            return;
        }
        ioExecutor.execute(() -> {
            boolean currentlyActive = db.sessionDao().getActive() != null;
            uiHandler.post(() -> {
                if (!currentlyActive) {
                    Intent intent = new Intent(this, TrackingService.class);
                    String adapterAddress = AppSettings.getObdAdapterAddress(this);
                    if (adapterAddress != null) {
                        intent.putExtra(TrackingService.EXTRA_ADAPTER_ADDRESS, adapterAddress);
                    }
                    startForegroundService(intent);
                } else {
                    stopService(new Intent(this, TrackingService.class));
                }
            });
        });
    }

    /**
     * One-time setup: lists already-paired Bluetooth devices and lets the
     * user tap which one is the ELM327/OBDLink adapter. After this, walking
     * up to the truck is the only "action" needed — BluetoothConnectionReceiver
     * takes it from here automatically.
     */
    private void pickObdAdapter() {
        if (!PermissionFlow.hasAllPermissions(this)) {
            PermissionFlow.requestAllPermissions(this);
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Toast.makeText(this, "This device has no Bluetooth adapter", Toast.LENGTH_LONG).show();
            return;
        }

        Set<BluetoothDevice> bonded;
        try {
            bonded = adapter.getBondedDevices();
        } catch (SecurityException e) {
            Toast.makeText(this, "Bluetooth permission required", Toast.LENGTH_LONG).show();
            return;
        }

        if (bonded.isEmpty()) {
            Toast.makeText(this, "No paired Bluetooth devices — pair your OBD adapter in phone Settings > Bluetooth first", Toast.LENGTH_LONG).show();
            return;
        }

        BluetoothDevice[] devices = bonded.toArray(new BluetoothDevice[0]);
        String[] labels = new String[devices.length];
        for (int i = 0; i < devices.length; i++) {
            String name;
            try {
                name = devices[i].getName();
            } catch (SecurityException e) {
                name = null;
            }
            labels[i] = (name != null ? name : "Unknown device") + "  (" + devices[i].getAddress() + ")";
        }

        new AlertDialog.Builder(this)
                .setTitle("Which device is your OBD adapter?")
                .setItems(labels, (dialog, which) -> {
                    BluetoothDevice chosen = devices[which];
                    String name;
                    try {
                        name = chosen.getName();
                    } catch (SecurityException e) {
                        name = "OBD Adapter";
                    }
                    AppSettings.setObdAdapter(this, chosen.getAddress(), name);
                    AppSettings.setAutoTrackingEnabled(this, true);
                    Toast.makeText(this, "Paired: " + name + ". Tracking will now start automatically when it connects.", Toast.LENGTH_LONG).show();
                    refreshAdapterStatusUi();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void toggleAutoTracking() {
        boolean newState = !AppSettings.isAutoTrackingEnabled(this);
        AppSettings.setAutoTrackingEnabled(this, newState);
        refreshAdapterStatusUi();
    }

    private void refreshAdapterStatusUi() {
        String adapterName = AppSettings.getObdAdapterName(this);
        boolean autoEnabled = AppSettings.isAutoTrackingEnabled(this);
        buttonAutoTrackingToggle.setText(autoEnabled ? "Auto-Tracking: On" : "Auto-Tracking: Off");
        if (adapterName == null) {
            textAdapterStatus.setText("No OBD adapter paired yet — tap \"Pair OBD Adapter\"");
        } else if (autoEnabled) {
            textAdapterStatus.setText("Auto-tracking with: " + adapterName);
        } else {
            textAdapterStatus.setText("Paired with " + adapterName + " (auto-tracking off)");
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
                buttonToggleTracking.setText(session != null ? "Stop Trip (manual)" : "Start Trip (manual)");
            });
        });
        uiHandler.postDelayed(refreshTask, REFRESH_INTERVAL_MS);
    }
}
