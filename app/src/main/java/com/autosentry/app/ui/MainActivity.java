package com.autosentry.app.ui;

import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.autosentry.app.R;
import com.autosentry.app.data.AppDatabase;
import com.autosentry.app.data.MaintenanceEvent;
import com.autosentry.app.data.VehicleProfile;
import com.autosentry.app.maintenance.MaintenanceScheduleEngine;
import com.autosentry.app.maintenance.ServiceItemType;
import com.autosentry.app.maintenance.ServiceStatus;
import com.autosentry.app.obd.LiveReadings;
import com.autosentry.app.obd.PidCatalog;
import com.autosentry.app.service.TrackingService;
import com.autosentry.app.settings.AppSettings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Two tabs: Dashboard (live readings the user picks via "Edit Dashboard",
 * plus adapter setup) and Service (oil life and the full service schedule,
 * driven by engine-on time and distance).
 */
public class MainActivity extends AppCompatActivity {
    private static final long REFRESH_INTERVAL_MS = 500L;
    private static final long DB_REFRESH_INTERVAL_MS = 1500L;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = this::refresh;

    private AppDatabase db;
    private long lastDbRefresh = 0;
    private volatile VehicleProfile lastProfile;
    private boolean dashboardTabVisible = true;

    private final Map<Integer, TextView> tileValues = new HashMap<>();
    private GridLayout gridTiles;
    private View scrollDashboard, scrollService;
    private TextView textAdapterStatus, textLiveStatus, textOilLife, textOilDetail, textOdometer, textServiceList;
    private Button buttonTabDashboard, buttonTabService, buttonEditDashboard, buttonToggleTracking, buttonResetOil,
            buttonLogMaintenance, buttonMaintenanceHistory, buttonPairAdapter, buttonAutoTrackingToggle,
            buttonBackgroundAccess, buttonDebugLog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        db = AppDatabase.getInstance(this);

        gridTiles = findViewById(R.id.gridTiles);
        scrollDashboard = findViewById(R.id.scrollDashboard);
        scrollService = findViewById(R.id.scrollService);
        textAdapterStatus = findViewById(R.id.textAdapterStatus);
        textLiveStatus = findViewById(R.id.textLiveStatus);
        textOilLife = findViewById(R.id.textOilLife);
        textOilDetail = findViewById(R.id.textOilDetail);
        textOdometer = findViewById(R.id.textOdometer);
        textServiceList = findViewById(R.id.textServiceList);
        buttonTabDashboard = findViewById(R.id.buttonTabDashboard);
        buttonTabService = findViewById(R.id.buttonTabService);
        buttonEditDashboard = findViewById(R.id.buttonEditDashboard);
        buttonToggleTracking = findViewById(R.id.buttonToggleTracking);
        buttonResetOil = findViewById(R.id.buttonResetOil);
        buttonLogMaintenance = findViewById(R.id.buttonLogMaintenance);
        buttonMaintenanceHistory = findViewById(R.id.buttonMaintenanceHistory);
        buttonPairAdapter = findViewById(R.id.buttonPairAdapter);
        buttonAutoTrackingToggle = findViewById(R.id.buttonAutoTrackingToggle);
        buttonBackgroundAccess = findViewById(R.id.buttonBackgroundAccess);
        buttonDebugLog = findViewById(R.id.buttonDebugLog);

        if (!PermissionFlow.hasAllPermissions(this)) {
            PermissionFlow.requestAllPermissions(this);
        }

        buttonTabDashboard.setOnClickListener(v -> showTab(true));
        buttonTabService.setOnClickListener(v -> showTab(false));
        buttonEditDashboard.setOnClickListener(v -> showEditDashboard());
        buttonToggleTracking.setOnClickListener(v -> toggleTracking());
        buttonResetOil.setOnClickListener(v -> resetOilLife());
        buttonLogMaintenance.setOnClickListener(v -> startActivity(new Intent(this, LogMaintenanceActivity.class)));
        buttonMaintenanceHistory.setOnClickListener(v -> startActivity(new Intent(this, MaintenanceHistoryActivity.class)));
        buttonPairAdapter.setOnClickListener(v -> pickObdAdapter());
        buttonAutoTrackingToggle.setOnClickListener(v -> toggleAutoTracking());
        buttonBackgroundAccess.setOnClickListener(v -> requestBackgroundAccess());
        buttonDebugLog.setOnClickListener(v -> startActivity(new Intent(this, DebugLogActivity.class)));

        rebuildTiles();
        showTab(true);

        ioExecutor.execute(() -> {
            if (db.vehicleProfileDao().getSync() == null) {
                db.vehicleProfileDao().insert(VehicleProfile.newDefault());
            }
        });

        refreshAdapterStatusUi();
        maybeAutoStartTracking();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        maybeAutoStartTracking();
    }

    /**
     * With the app open we're allowed to start the tracking service ourselves,
     * so opening the app is enough — no need to also tap Start. The service
     * itself waits for the truck to be running before it records anything.
     */
    private void maybeAutoStartTracking() {
        if (TrackingService.isRunning) return;
        if (!AppSettings.hasObdAdapterConfigured(this) || !AppSettings.isAutoTrackingEnabled(this)) return;
        if (!PermissionFlow.hasAllPermissions(this)) return;
        startTrackingService();
    }

    private void startTrackingService() {
        try {
            startForegroundService(new Intent(this, TrackingService.class));
        } catch (RuntimeException e) {
            Toast.makeText(this, "Couldn't start tracking: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showTab(boolean dashboard) {
        dashboardTabVisible = dashboard;
        scrollDashboard.setVisibility(dashboard ? View.VISIBLE : View.GONE);
        scrollService.setVisibility(dashboard ? View.GONE : View.VISIBLE);
        buttonTabDashboard.setEnabled(!dashboard);
        buttonTabService.setEnabled(dashboard);
        lastDbRefresh = 0; // refresh the service tab right away
    }

    /** Start/stop by hand. Without a paired adapter this runs the simulator, for testing. */
    private void toggleTracking() {
        if (TrackingService.isRunning) {
            stopService(new Intent(this, TrackingService.class));
            return;
        }
        if (!PermissionFlow.hasAllPermissions(this)) {
            PermissionFlow.requestAllPermissions(this);
            return;
        }
        startTrackingService();
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
                    // Restart so a running simulator session switches over to the real adapter.
                    if (TrackingService.isRunning) stopService(new Intent(this, TrackingService.class));
                    uiHandler.postDelayed(this::maybeAutoStartTracking, 800L);
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
        PowerManager pm = getSystemService(PowerManager.class);
        boolean allowed = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        buttonBackgroundAccess.setText(allowed
                ? "Background start: Allowed"
                : "Allow background start (needed for auto-start)");
    }

    /** Android only lets an app start tracking from a closed state if the user exempts it from battery optimization. */
    private void requestBackgroundAccess() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            Toast.makeText(this, "Already allowed", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
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
            lastDbRefresh = 0;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAdapterStatusUi(); // the battery exemption may have just changed
        uiHandler.post(refreshTask);
    }

    @Override
    protected void onPause() {
        super.onPause();
        uiHandler.removeCallbacks(refreshTask);
    }

    private void refresh() {
        updateLiveStatus();
        updateTiles();
        long now = System.currentTimeMillis();
        if (now - lastDbRefresh >= DB_REFRESH_INTERVAL_MS) {
            lastDbRefresh = now;
            refreshFromDb();
        }
        uiHandler.postDelayed(refreshTask, REFRESH_INTERVAL_MS);
    }

    private void updateLiveStatus() {
        boolean running = TrackingService.isRunning;
        buttonToggleTracking.setText(running ? "Stop Tracking"
                : (AppSettings.hasObdAdapterConfigured(this) ? "Start Tracking" : "Start Tracking (simulator)"));
        textLiveStatus.setText(running ? LiveReadings.status : "Tracking is off — tap Start Tracking");
    }

    private void updateTiles() {
        Set<Integer> supported = LiveReadings.supported;
        for (Map.Entry<Integer, TextView> entry : tileValues.entrySet()) {
            int id = entry.getKey();
            PidCatalog.Pid def = PidCatalog.get(id);
            Double value = LiveReadings.values.get(id);
            VehicleProfile profile = lastProfile;
            if (value == null && id == PidCatalog.COMPUTED_ODOMETER && profile != null) {
                value = profile.odometerMiles;
            }
            if (value != null) {
                entry.getValue().setText(def == null ? "--" : def.formatValue(value));
            } else if (def != null && !def.computed && !supported.isEmpty() && !supported.contains(id)) {
                // Truck has been scanned and confirmed it doesn't answer this one — say so
                // instead of leaving it blank with no explanation.
                entry.getValue().setText("Not supported");
            } else {
                entry.getValue().setText("--");
            }
        }
    }

    /** Rebuilds the tile grid from the user's saved selection. */
    private void rebuildTiles() {
        gridTiles.removeAllViews();
        tileValues.clear();
        List<Integer> ids = AppSettings.getDashboardPids(this);
        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(12 * density);
        int margin = Math.round(6 * density);

        boolean any = false;
        for (int id : ids) {
            PidCatalog.Pid def = PidCatalog.get(id);
            if (def == null) continue;
            any = true;

            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setPadding(pad, pad, pad, pad);
            GradientDrawable border = new GradientDrawable();
            border.setCornerRadius(8 * density);
            border.setStroke(Math.max(1, Math.round(density)), Color.parseColor("#55888888"));
            tile.setBackground(border);

            TextView label = new TextView(this);
            label.setText(def.label());
            label.setTextSize(12);
            label.setTextColor(Color.parseColor("#888888"));

            TextView value = new TextView(this);
            value.setText("--");
            value.setTextSize(28);
            value.setTypeface(null, Typeface.BOLD);

            tile.addView(label);
            tile.addView(value);

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            lp.setMargins(margin, margin, margin, margin);
            gridTiles.addView(tile, lp);
            tileValues.put(id, value);
        }

        if (!any) {
            TextView hint = new TextView(this);
            hint.setText("No readings selected — tap Edit Dashboard to pick some.");
            gridTiles.addView(hint);
        }
    }

    /** Lets the user pick which readings appear on the dashboard. */
    private void showEditDashboard() {
        Set<Integer> supported = !LiveReadings.supported.isEmpty()
                ? LiveReadings.supported : AppSettings.getSupportedPids(this);
        if (supported.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Connect to the truck first")
                    .setMessage("AutoSentry doesn't know yet which readings this truck reports. "
                            + "Start tracking with the engine running once, then come back here — "
                            + "you'll still be able to pick the computed ones (MPG, distance, odometer) now.")
                    .setPositiveButton("Pick computed readings", (d, w) -> showEditDashboardOptions(supported))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        showEditDashboardOptions(supported);
    }

    private void showEditDashboardOptions(Set<Integer> supported) {
        List<PidCatalog.Pid> options = PidCatalog.available(supported);
        List<Integer> current = AppSettings.getDashboardPids(this);

        String[] labels = new String[options.size()];
        boolean[] checked = new boolean[options.size()];
        for (int i = 0; i < options.size(); i++) {
            labels[i] = options.get(i).label();
            checked[i] = current.contains(options.get(i).id);
        }

        new AlertDialog.Builder(this)
                .setTitle(supported.isEmpty()
                        ? "Choose readings (connect to the truck to see which it supports)"
                        : "Choose readings to show")
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Save", (dialog, which) -> {
                    List<Integer> chosen = new ArrayList<>();
                    // Keep the existing order for what was already shown, then add new picks.
                    for (int id : current) {
                        for (int i = 0; i < options.size(); i++) {
                            if (options.get(i).id == id && checked[i]) chosen.add(id);
                        }
                    }
                    for (int i = 0; i < options.size(); i++) {
                        if (checked[i] && !chosen.contains(options.get(i).id)) chosen.add(options.get(i).id);
                    }
                    AppSettings.setDashboardPids(this, chosen);
                    rebuildTiles();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Service tab: oil life plus every scheduled item, from engine-on distance and time. */
    private void refreshFromDb() {
        final boolean needServiceList = !dashboardTabVisible;
        ioExecutor.execute(() -> {
            VehicleProfile profile = db.vehicleProfileDao().getSync();
            if (profile == null) return;
            lastProfile = profile;

            StringBuilder sb = new StringBuilder();
            if (needServiceList) {
                List<ServiceStatus> statuses = MaintenanceScheduleEngine.computeAll(profile, db.maintenanceDao());
                for (ServiceStatus status : statuses) {
                    String flag = status.overdue ? "  — OVERDUE" : (status.dueSoon ? "  — DUE SOON" : "");
                    sb.append(String.format(Locale.US, "%s\n    %.0f%% used, %,.0f mi remaining%s\n",
                            status.displayName, status.percentOfLifeUsed, status.milesRemaining(), flag));
                }
            }

            uiHandler.post(() -> {
                textOilLife.setText(String.format(Locale.US, "Oil life: %.0f%%", profile.oilLifePercent));
                textOdometer.setText(String.format(Locale.US, "Odometer: %,.1f mi", profile.odometerMiles));
                textOilDetail.setText(String.format(Locale.US,
                        "Since last oil change: %,.1f mi, %.1f engine hrs",
                        profile.milesSinceOilChange, profile.engineHoursSinceOilChange));
                if (needServiceList) textServiceList.setText(sb.toString());
            });
        });
    }
}
