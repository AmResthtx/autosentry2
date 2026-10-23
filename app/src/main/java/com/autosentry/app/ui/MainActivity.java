package com.autosentry.app.ui;

import android.app.AlarmManager;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.autosentry.app.BuildConfig;
import com.autosentry.app.R;
import com.autosentry.app.business.BusinessConfig;
import com.autosentry.app.business.DealsCatalog;
import com.autosentry.app.business.DealsMatcher;
import com.autosentry.app.business.PartOffer;
import com.autosentry.app.data.AppDatabase;
import com.autosentry.app.data.KoeoReport;
import com.autosentry.app.data.MaintenanceEvent;
import com.autosentry.app.data.Session;
import com.autosentry.app.data.TripPoint;
import com.autosentry.app.data.VehicleProfile;
import com.autosentry.app.diagnostics.PidToleranceEngine;
import com.autosentry.app.maintenance.MaintenanceScheduleEngine;
import com.autosentry.app.maintenance.ServiceInterval;
import com.autosentry.app.maintenance.ServiceItemType;
import com.autosentry.app.maintenance.ServiceStatus;
import com.autosentry.app.obd.DTCReader;
import com.autosentry.app.obd.DiagnosticCodes;
import com.autosentry.app.obd.DtcSeverity;
import com.autosentry.app.obd.LiveReadings;
import com.autosentry.app.obd.PidCatalog;
import com.autosentry.app.service.TrackingService;
import com.autosentry.app.settings.AppSettings;
import com.autosentry.app.util.TabletFiles;

import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Five tabs: Dashboard (gauges for the live readings the user picks via "Edit readings"),
 * Checks (trouble codes with their meaning spelled out, key-on checks, and the
 * truck systems scan), History (oil life and the full service schedule, driven
 * by engine-on time and distance), Deals, and Account (adapter setup).
 */
public class MainActivity extends AppCompatActivity {
    private static final long REFRESH_INTERVAL_MS = 500L;
    private static final long DB_REFRESH_INTERVAL_MS = 1500L;
    private static final int TAB_DASHBOARD = 0;
    private static final int TAB_DIAGNOSTICS = 1;
    private static final int TAB_HISTORY = 2;
    private static final int TAB_DEALS = 3;
    private static final int KOEO_REPORTS_SHOWN = 5;
    private static final String[] TAB_TITLES = {"Dashboard", "Checks", "History", "Deals", "Account"};
    private static final String STOP_CONFIRM_WORD = "STOP";

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = this::refresh;

    private AppDatabase db;
    private long lastDbRefresh = 0;
    private volatile VehicleProfile lastProfile;
    private int currentTab = TAB_DASHBOARD;
    // Runs once the Bluetooth permission dialog is answered with a grant.
    private Runnable afterPermissions;

    /** One dashboard reading and the tile that shows it. */
    private static final class Tile {
        final int pidId;
        final String name;
        final GaugeTileView view;

        Tile(int pidId, String name, GaugeTileView view) {
            this.pidId = pidId;
            this.name = name;
            this.view = view;
        }
    }

    private final List<Tile> tiles = new ArrayList<>();
    private GridLayout gridTiles;
    private View[] tabViews;
    private View[] tabButtons;
    private View toolbarActions;
    private TextView textToolbarTitle;
    private int cOk, cWarn, cCrit, cText, cMuted;
    private TextView textHealth, textAlert;
    private TextView textAdapterStatus, textLiveStatus, textOilLife, textOilDetail, textOdometer,
            textKoeoReports, textMilStatus, textCodesScanInfo;
    private LinearLayout layoutServiceList, layoutCodesList, layoutDealsList;
    private String lastServiceListSignature = "";
    private String lastDealsSignature = "";
    private String lastCodesSignature = "";
    private DiagnosticCodes.Scan savedCodeScan;
    private ImageButton buttonEditDashboard, buttonToggleTracking;
    private Button buttonResetOil,
            buttonLogMaintenance, buttonMaintenanceHistory, buttonPairAdapter, buttonAutoTrackingToggle,
            buttonSetupWizard, buttonBackgroundAccess, buttonDebugLog, buttonSetOdometer, buttonTestConnection, buttonKoeoCheck,
            buttonSaveTrips, buttonSystemsScan, buttonScanCodes, buttonClearCodes;

    /** Set by the service-alert notification so tapping it lands on the History tab. */
    public static final String EXTRA_OPEN_SERVICE_TAB = "open_service_tab";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        db = AppDatabase.getInstance(this);

        cOk = ContextCompat.getColor(this, R.color.fs_ok);
        cWarn = ContextCompat.getColor(this, R.color.fs_warn);
        cCrit = ContextCompat.getColor(this, R.color.fs_crit);
        cText = ContextCompat.getColor(this, R.color.fs_text);
        cMuted = ContextCompat.getColor(this, R.color.fs_muted);

        gridTiles = findViewById(R.id.gridTiles);
        textHealth = findViewById(R.id.textHealth);
        textAlert = findViewById(R.id.textAlert);
        textToolbarTitle = findViewById(R.id.textToolbarTitle);
        toolbarActions = findViewById(R.id.toolbarActions);
        tabViews = new View[]{findViewById(R.id.scrollDashboard), findViewById(R.id.scrollDiagnostics),
                findViewById(R.id.scrollService), findViewById(R.id.scrollDeals), findViewById(R.id.scrollAccount)};
        tabButtons = new View[]{findViewById(R.id.buttonTabDashboard), findViewById(R.id.buttonTabDiagnostics),
                findViewById(R.id.buttonTabService), findViewById(R.id.buttonTabDeals), findViewById(R.id.buttonTabAccount)};
        textAdapterStatus = findViewById(R.id.textAdapterStatus);
        textLiveStatus = findViewById(R.id.textLiveStatus);
        textOilLife = findViewById(R.id.textOilLife);
        textOilDetail = findViewById(R.id.textOilDetail);
        textOdometer = findViewById(R.id.textOdometer);
        layoutServiceList = findViewById(R.id.layoutServiceList);
        layoutDealsList = findViewById(R.id.layoutDealsList);
        textMilStatus = findViewById(R.id.textMilStatus);
        textCodesScanInfo = findViewById(R.id.textCodesScanInfo);
        layoutCodesList = findViewById(R.id.layoutCodesList);
        buttonScanCodes = findViewById(R.id.buttonScanCodes);
        buttonClearCodes = findViewById(R.id.buttonClearCodes);
        buttonSystemsScan = findViewById(R.id.buttonSystemsScan);
        buttonEditDashboard = findViewById(R.id.buttonEditDashboard);
        buttonToggleTracking = findViewById(R.id.buttonToggleTracking);
        buttonResetOil = findViewById(R.id.buttonResetOil);
        buttonLogMaintenance = findViewById(R.id.buttonLogMaintenance);
        buttonMaintenanceHistory = findViewById(R.id.buttonMaintenanceHistory);
        buttonPairAdapter = findViewById(R.id.buttonPairAdapter);
        buttonSetupWizard = findViewById(R.id.buttonSetupWizard);
        buttonAutoTrackingToggle = findViewById(R.id.buttonAutoTrackingToggle);
        buttonBackgroundAccess = findViewById(R.id.buttonBackgroundAccess);
        buttonDebugLog = findViewById(R.id.buttonDebugLog);
        buttonSetOdometer = findViewById(R.id.buttonSetOdometer);
        buttonTestConnection = findViewById(R.id.buttonTestConnection);
        buttonKoeoCheck = findViewById(R.id.buttonKoeoCheck);
        textKoeoReports = findViewById(R.id.textKoeoReports);
        buttonSaveTrips = findViewById(R.id.buttonSaveTrips);
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide(); // the screen has its own toolbar
        }


        if (savedInstanceState == null) {
            // A fresh install gets the wizard, which asks for permissions itself, step by step.
            if (AppSettings.getSetupPhaseDone(this) == 0 && !AppSettings.hasObdAdapterConfigured(this)) {
                startActivity(new Intent(this, SetupWizardActivity.class));
            } else {
                PermissionFlow.requestMissingPermissions(this);
            }
        }

        for (int i = 0; i < tabButtons.length; i++) {
            final int tab = i;
            tabButtons[i].setOnClickListener(v -> showTab(tab));
        }
        buttonEditDashboard.setOnClickListener(v -> showEditDashboard());
        findViewById(R.id.buttonZoomIn).setOnClickListener(v -> changeTileColumns(-1));
        findViewById(R.id.buttonZoomOut).setOnClickListener(v -> changeTileColumns(1));
        findViewById(R.id.buttonInfo).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("AutoSentry")
                .setMessage("Version " + BuildConfig.VERSION_NAME + " (build " + BuildConfig.VERSION_CODE + ")")
                .setPositiveButton("OK", null)
                .show());
        buttonToggleTracking.setOnClickListener(v -> toggleTracking());
        buttonResetOil.setOnClickListener(v -> resetOilLife());
        buttonLogMaintenance.setOnClickListener(v -> startActivity(new Intent(this, LogMaintenanceActivity.class)));
        buttonMaintenanceHistory.setOnClickListener(v -> startActivity(new Intent(this, MaintenanceHistoryActivity.class)));
        buttonPairAdapter.setOnClickListener(v -> pickObdAdapter());
        buttonSetupWizard.setOnClickListener(v -> startActivity(new Intent(this, SetupWizardActivity.class)));
        buttonAutoTrackingToggle.setOnClickListener(v -> toggleAutoTracking());
        buttonBackgroundAccess.setOnClickListener(v -> requestBackgroundAccess());
        buttonDebugLog.setOnClickListener(v -> startActivity(new Intent(this, DebugLogActivity.class)));
        buttonSetOdometer.setOnClickListener(v -> promptSetOdometer());
        buttonTestConnection.setOnClickListener(v -> withRequiredPermissions(this::showConnectionTest));
        buttonKoeoCheck.setOnClickListener(v -> withRequiredPermissions(this::showKoeoCheck));
        buttonSaveTrips.setOnClickListener(v -> saveTripsToTablet());
        buttonScanCodes.setOnClickListener(v -> requestCodeScan());
        buttonClearCodes.setOnClickListener(v -> confirmClearCodes());
        buttonSystemsScan.setOnClickListener(v -> startActivity(new Intent(this, SystemsScanActivity.class)));

        rebuildTiles();
        savedCodeScan = AppSettings.getLastCodeScan(this);
        showTab(getIntent().getBooleanExtra(EXTRA_OPEN_SERVICE_TAB, false) ? TAB_HISTORY : TAB_DASHBOARD);

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
        Runnable action = afterPermissions;
        afterPermissions = null;
        if (action != null) {
            if (PermissionFlow.hasRequiredPermissions(this)) {
                action.run();
            } else {
                PermissionFlow.explainBluetoothDenied(this);
            }
            return;
        }
        maybeAutoStartTracking();
    }

    private void withRequiredPermissions(Runnable action) {
        if (PermissionFlow.hasRequiredPermissions(this)) {
            action.run();
            return;
        }
        afterPermissions = action;
        PermissionFlow.requestMissingPermissions(this);
    }

    /**
     * With the app open we're allowed to start the tracking service ourselves,
     * so opening the app is enough — no need to also tap Start. The service
     * itself waits for the truck to be running before it records anything.
     */
    private void maybeAutoStartTracking() {
        TrackingService.startIfEnabled(this, "app opened");
    }

    private void startTrackingService() {
        try {
            ContextCompat.startForegroundService(this, new Intent(this, TrackingService.class));
        } catch (RuntimeException e) {
            Toast.makeText(this, "Couldn't start tracking: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showTab(int tab) {
        currentTab = tab;
        for (int i = 0; i < tabViews.length; i++) {
            tabViews[i].setVisibility(i == tab ? View.VISIBLE : View.GONE);
            tabButtons[i].setSelected(i == tab);
        }
        textToolbarTitle.setText(TAB_TITLES[tab]);
        toolbarActions.setVisibility(tab == TAB_DASHBOARD ? View.VISIBLE : View.GONE);
        lastDbRefresh = 0; // refresh the history tab right away
        lastCodesSignature = ""; // and the trouble codes
    }

    /** Start/stop by hand. Without a paired adapter this opens pairing instead. */
    private void toggleTracking() {
        if (TrackingService.isRunning) {
            confirmStopTracking();
            return;
        }
        if (!AppSettings.hasObdAdapterConfigured(this)) {
            Toast.makeText(this, "Pair your OBD adapter first", Toast.LENGTH_SHORT).show();
            pickObdAdapter();
            return;
        }
        AppSettings.setTrackingPaused(this, false);
        withRequiredPermissions(this::startTrackingService);
    }

    /** Stopping by hand needs the confirm word typed, so a stray tap can't end the session. */
    private void confirmStopTracking() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        input.setHint(STOP_CONFIRM_WORD);
        FrameLayout container = new FrameLayout(this);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, 0, pad, 0);
        container.addView(input);

        new AlertDialog.Builder(this)
                .setTitle("Stop tracking the truck?")
                .setMessage("Tracking stays off — even at key-on — until you tap Start Tracking. "
                        + "Type " + STOP_CONFIRM_WORD + " to confirm.")
                .setView(container)
                .setPositiveButton("Stop Tracking", (dialog, which) -> {
                    if (!STOP_CONFIRM_WORD.equalsIgnoreCase(input.getText().toString().trim())) {
                        Toast.makeText(this, "Still tracking — type " + STOP_CONFIRM_WORD + " to stop", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    AppSettings.setTrackingPaused(this, true);
                    stopService(new Intent(this, TrackingService.class));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * One-time setup: lists already-paired Bluetooth devices and lets the
     * user tap which one is the ELM327/OBDLink adapter. After this, turning
     * the key is the only "action" needed — TrackingService keeps retrying the
     * adapter and picks the trip up automatically.
     */
    private void pickObdAdapter() {
        if (!PermissionFlow.hasRequiredPermissions(this)) {
            withRequiredPermissions(this::pickObdAdapter);
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Toast.makeText(this, "This device has no Bluetooth adapter", Toast.LENGTH_LONG).show();
            return;
        }

        Set<BluetoothDevice> bonded;
        try {
            if (!adapter.isEnabled()) {
                Toast.makeText(this, "Turn on Bluetooth, then pair your OBD adapter in phone Settings > Bluetooth", Toast.LENGTH_LONG).show();
                return;
            }
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
                    AppSettings.setTrackingPaused(this, false);
                    Toast.makeText(this, "Paired: " + name + ". Tracking will now start automatically when it connects.", Toast.LENGTH_LONG).show();
                    refreshAdapterStatusUi();
                    // Restart so a running session switches over to the newly chosen adapter.
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
        if (newState) maybeAutoStartTracking();
    }

    /**
     * Step-by-step check of everything a tracked trip depends on. The service runs it
     * (it owns the adapter link), and this dialog shows the report as it fills in.
     */
    private void showConnectionTest() {
        showServiceReport("OBD connection test", TrackingService.ACTION_RUN_TEST,
                "Testing… (up to 30 s if the adapter is asleep)");
    }

    /** Key on, engine off: battery, readings and trouble codes now; the next crank gets timed. */
    private void showKoeoCheck() {
        showServiceReport("Key-on engine-off check", TrackingService.ACTION_RUN_KOEO,
                "Reading the truck… keep the key ON, engine off");
        lastDbRefresh = 0;
    }

    /** Runs {@code action} in the service and shows its LiveReadings.testReport as it fills in. */
    private void showServiceReport(String title, String action, String waitingText) {
        if (!AppSettings.hasObdAdapterConfigured(this)) {
            Toast.makeText(this, "Pair your OBD adapter first", Toast.LENGTH_SHORT).show();
            pickObdAdapter();
            return;
        }
        TextView report = new TextView(this);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        report.setPadding(pad, pad / 2, pad, 0);
        report.setTextSize(15);
        report.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(report);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scroll)
                .setNeutralButton("Run Again", null)
                .setPositiveButton("Close", null)
                .create();
        Runnable update = new Runnable() {
            @Override
            public void run() {
                report.setText(LiveReadings.testReport);
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(!LiveReadings.testRunning);
                uiHandler.postDelayed(this, REFRESH_INTERVAL_MS);
            }
        };
        dialog.setOnShowListener(d -> {
            // Set here so tapping it doesn't close the dialog.
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> startServiceReport(action, waitingText));
            uiHandler.post(update);
        });
        dialog.setOnDismissListener(d -> {
            uiHandler.removeCallbacks(update);
            lastDbRefresh = 0; // show a new KOEO report on the Diagnostics tab right away
        });
        startServiceReport(action, waitingText);
        dialog.show();
    }

    private void startServiceReport(String action, String waitingText) {
        LiveReadings.testRunning = true;
        LiveReadings.testReport = waitingText;
        AppSettings.setTrackingPaused(this, false);
        try {
            ContextCompat.startForegroundService(this, new Intent(this, TrackingService.class)
                    .setAction(action));
        } catch (RuntimeException e) {
            LiveReadings.testRunning = false;
            LiveReadings.testReport = "✗ Couldn't start tracking: " + e.getMessage();
        }
    }

    private void refreshAdapterStatusUi() {
        String adapterName = AppSettings.getObdAdapterName(this);
        boolean autoEnabled = AppSettings.isAutoTrackingEnabled(this);
        buttonAutoTrackingToggle.setText(autoEnabled ? "Auto-Tracking: On" : "Auto-Tracking: Off");
        if (adapterName == null) {
            textAdapterStatus.setText("Scanner not connected — pair your OBD adapter in the Account tab");
        } else if (autoEnabled) {
            textAdapterStatus.setText("Auto-tracking with: " + adapterName);
        } else {
            textAdapterStatus.setText("Paired with " + adapterName + " (auto-tracking off)");
        }
        PowerManager pm = getSystemService(PowerManager.class);
        boolean allowed = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        buttonBackgroundAccess.setText(!allowed ? "Allow background start (needed for auto-start)"
                : !exactAlarmsAllowed() ? "Allow low-power waiting (alarms)"
                : "Background start: Allowed");
    }

    /** Exact alarms let tracking sleep while the truck is off and still check for key-on every 15 s. */
    private boolean exactAlarmsAllowed() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        AlarmManager alarms = getSystemService(AlarmManager.class);
        return alarms != null && alarms.canScheduleExactAlarms();
    }

    /** Android only lets an app start tracking from a closed state if the user exempts it from battery optimization. */
    private void requestBackgroundAccess() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            if (exactAlarmsAllowed()) {
                Toast.makeText(this, "Already allowed", Toast.LENGTH_SHORT).show();
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:" + getPackageName())));
            }
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
            long now = System.currentTimeMillis();
            db.vehicleProfileDao().resetOil(100.0, 0, now);

            MaintenanceEvent event = new MaintenanceEvent();
            event.type = ServiceItemType.OIL_FILTER.name();
            event.notes = "Logged from dashboard";
            event.timestamp = now;
            event.odometerAtEvent = profile.odometerMiles;
            db.maintenanceDao().insert(event);
            lastDbRefresh = 0;
            uiHandler.post(() -> Toast.makeText(this, String.format(Locale.US,
                    "Oil change logged at %,.0f mi", profile.odometerMiles), Toast.LENGTH_SHORT).show());
        });
    }

    /** Every trip, and every once-a-second point in it, as two CSV files on the tablet. No internet needed. */
    private void saveTripsToTablet() {
        ioExecutor.execute(() -> {
            String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
            SimpleDateFormat time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            String message;
            try {
                List<Session> sessions = db.sessionDao().getAllSync();
                String where = TabletFiles.save(this, "trips_" + stamp + ".csv", out -> {
                    out.write("Trip,Start,End,Start odometer mi,Distance mi,Fuel gal,Avg MPG,Max speed mph\n");
                    for (Session s : sessions) {
                        out.write(String.format(Locale.US, "%d,%s,%s,%.1f,%.2f,%.3f,%.1f,%.0f\n",
                                s.id, time.format(new Date(s.startTimestamp)),
                                s.endTimestamp > 0 ? time.format(new Date(s.endTimestamp)) : "",
                                s.startOdometerMiles, s.distanceMiles, s.fuelGallonsUsed, s.avgMpg, s.maxSpeedMph));
                    }
                });
                TabletFiles.save(this, "trip_points_" + stamp + ".csv", out -> {
                    out.write("Trip,Time,Latitude,Longitude,Speed mph,RPM,MAF g/s,Instant MPG,Miles since last point\n");
                    for (Session s : sessions) {
                        for (TripPoint p : db.tripPointDao().getForSession(s.id)) {
                            out.write(String.format(Locale.US, "%d,%s,%.6f,%.6f,%.1f,%d,%.2f,%.1f,%.4f\n",
                                    p.sessionId, time.format(new Date(p.timestamp)), p.latitude, p.longitude,
                                    p.speedMph, p.rpm, p.mafGramsPerSec, p.instantMpg, p.distanceDeltaMiles));
                        }
                    }
                });
                message = sessions.size() + " trips saved to " + where.substring(0, where.lastIndexOf('/'));
            } catch (IOException | RuntimeException e) {
                message = "Couldn't save trips: " + e.getMessage();
            }
            String toast = message;
            uiHandler.post(() -> Toast.makeText(this, toast, Toast.LENGTH_LONG).show());
        });
    }

    /** Tracking adds driven miles; this sets the starting point to what the instrument cluster reads. */
    private void promptSetOdometer() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        VehicleProfile profile = lastProfile;
        if (profile != null && profile.odometerMiles > 0) {
            input.setText(String.format(Locale.US, "%.0f", profile.odometerMiles));
        }
        FrameLayout container = new FrameLayout(this);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, 0, pad, 0);
        container.addView(input);

        new AlertDialog.Builder(this)
                .setTitle("Current odometer (mi)")
                .setMessage("Enter the instrument-cluster reading. Tracked driving is added automatically.")
                .setView(container)
                .setPositiveButton("Save", (dialog, which) -> {
                    double miles;
                    try {
                        miles = Double.parseDouble(input.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        miles = -1;
                    }
                    if (!(miles >= 0) || Double.isInfinite(miles)) {
                        Toast.makeText(this, "Enter the odometer as a number of miles", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    final double value = miles;
                    ioExecutor.execute(() -> {
                        db.vehicleProfileDao().setOdometer(value);
                        lastDbRefresh = 0;
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra(EXTRA_OPEN_SERVICE_TAB, false)) showTab(TAB_HISTORY);
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
        if (currentTab == TAB_DIAGNOSTICS) updateCodesTab();
        long now = System.currentTimeMillis();
        if (now - lastDbRefresh >= DB_REFRESH_INTERVAL_MS) {
            lastDbRefresh = now;
            refreshFromDb();
        }
        uiHandler.postDelayed(refreshTask, REFRESH_INTERVAL_MS);
    }

    private void updateLiveStatus() {
        boolean running = TrackingService.isRunning;
        buttonToggleTracking.setImageResource(running ? R.drawable.ic_tb_stop : R.drawable.ic_tb_play);
        buttonToggleTracking.setContentDescription(running ? "Stop tracking"
                : AppSettings.hasObdAdapterConfigured(this) ? "Start tracking" : "Pair adapter");
        textLiveStatus.setText(LiveReadings.status);
    }

    private void updateTiles() {
        Set<Integer> supported = LiveReadings.supported;
        VehicleProfile profile = lastProfile;
        int readings = 0, watch = 0, critical = 0;
        PidCatalog.Pid worstDef = null;
        double worstValue = 0;
        GaugeScale.Level worstLevel = GaugeScale.Level.OK;
        for (Tile tile : tiles) {
            PidCatalog.Pid def = PidCatalog.get(tile.pidId);
            Double value = LiveReadings.values.get(tile.pidId);
            if (value == null && tile.pidId == PidCatalog.COMPUTED_ODOMETER && profile != null) {
                value = profile.odometerMiles;
            }
            // Truck has been scanned and confirmed it doesn't answer this one — say so
            // instead of leaving it blank with no explanation.
            boolean noAnswer = value == null && def != null && !def.computed
                    && !supported.isEmpty() && !supported.contains(tile.pidId);

            GaugeScale.Level level = GaugeScale.Level.OK;
            if (value != null) {
                readings++;
                level = GaugeScale.level(tile.pidId, value);
                if (level == GaugeScale.Level.CRITICAL) critical++;
                if (level == GaugeScale.Level.WATCH) watch++;
                if (level.ordinal() > worstLevel.ordinal()) {
                    worstLevel = level;
                    worstDef = def;
                    worstValue = value;
                }
            }
            String text = value != null && def != null ? def.formatValue(value) : "--";
            tile.view.setReading(value == null ? Double.NaN : value, text, level, noAnswer);
            tile.view.setContentDescription(tile.name + " " + text + (def == null ? "" : " " + def.unit)
                    + (noAnswer ? ", not supported by this truck" : ""));
        }
        updateHealthChip(readings, watch, critical);
        updateAlert(worstDef, worstValue, worstLevel);
    }

    /** One line under the toolbar naming the reading that is furthest out of range. */
    private void updateAlert(PidCatalog.Pid def, double value, GaugeScale.Level level) {
        if (def == null || level == GaugeScale.Level.OK) {
            textAlert.setVisibility(View.GONE);
            return;
        }
        int color = level == GaugeScale.Level.CRITICAL ? cCrit : cWarn;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setColor((color & 0x00FFFFFF) | 0x26000000);
        bg.setStroke(Math.max(1, dp(1)), (color & 0x00FFFFFF) | 0x80000000);
        textAlert.setBackground(bg);
        textAlert.setTextColor(color);
        textAlert.setText((level == GaugeScale.Level.CRITICAL ? "CRITICAL  " : "WATCH  ")
                + def.name + ": " + PidToleranceEngine.explainAnomaly(def.id, value));
        textAlert.setVisibility(View.VISIBLE);
    }

    /** The summary chip: worst state across the readings on screen. */
    private void updateHealthChip(int readings, int watch, int critical) {
        String text;
        int color;
        if (critical > 0) {
            text = critical + " critical";
            color = cCrit;
        } else if (watch > 0) {
            text = watch + " to watch";
            color = cWarn;
        } else if (readings > 0) {
            text = "Running normal";
            color = cOk;
        } else {
            text = TrackingService.isRunning ? "Waiting for data" : "Idle";
            color = cMuted;
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(100));
        bg.setColor((color & 0x00FFFFFF) | 0x33000000);
        textHealth.setBackground(bg);
        textHealth.setText(text);
        textHealth.setTextColor(color);
    }

    /** Rebuilds the tiles from the user's saved selection, all the same size. */
    private void rebuildTiles() {
        gridTiles.removeAllViews();
        tiles.clear();
        gridTiles.setColumnCount(AppSettings.getTileColumns(this));

        for (int id : AppSettings.getDashboardPids(this)) {
            PidCatalog.Pid def = PidCatalog.get(id);
            if (def == null) continue;
            GaugeTileView view = new GaugeTileView(this);
            view.configure(def.name, def.unit, GaugeScale.forPid(id), id);
            view.setOnLongClickListener(v -> {
                showTileMenu(id);
                return true;
            });
            gridTiles.addView(view, gridParams());
            tiles.add(new Tile(id, def.name, view));
        }

        if (tiles.isEmpty()) {
            TextView hint = new TextView(this);
            hint.setText("No readings selected — tap the list button in the toolbar to pick some.");
            hint.setTextColor(cMuted);
            gridTiles.addView(hint);
        }
        updateTiles();
    }

    /** Zoom buttons: fewer columns mean larger gauges. */
    private void changeTileColumns(int delta) {
        int now = AppSettings.getTileColumns(this);
        AppSettings.setTileColumns(this, now + delta);
        if (AppSettings.getTileColumns(this) != now) rebuildTiles();
    }

    /** Hold a gauge: move it to the front, move it, or take it off the dashboard. */
    private void showTileMenu(int pidId) {
        PidCatalog.Pid def = PidCatalog.get(pidId);
        if (def == null) return;
        String[] items = {"Move to front", "Move earlier", "Move later", "Remove from dashboard"};
        new AlertDialog.Builder(this)
                .setTitle(def.name)
                .setItems(items, (dialog, which) -> {
                    List<Integer> ids = new ArrayList<>(AppSettings.getDashboardPids(this));
                    int i = ids.indexOf(pidId);
                    if (i < 0) return;
                    if (which == 0) {
                        ids.remove(i);
                        ids.add(0, pidId);
                    } else if (which == 1 && i > 0) {
                        Collections.swap(ids, i, i - 1);
                    } else if (which == 2 && i < ids.size() - 1) {
                        Collections.swap(ids, i, i + 1);
                    } else if (which == 3) {
                        ids.remove(i);
                    }
                    AppSettings.setDashboardPids(this, ids);
                    rebuildTiles();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private GridLayout.LayoutParams gridParams() {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f));
        lp.width = 0;
        int margin = dp(3);
        lp.setMargins(margin, margin, margin, margin);
        return lp;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * Lets the user pick which readings (PIDs) are read and shown. Every reading is
     * offered; ones the truck hasn't confirmed are labeled so, and their tile says
     * "Not supported" if the truck never answers them.
     */
    private void showEditDashboard() {
        Set<Integer> supported = !LiveReadings.supported.isEmpty()
                ? LiveReadings.supported : AppSettings.getSupportedPids(this);
        List<PidCatalog.Pid> options = new ArrayList<>(PidCatalog.all());
        List<Integer> current = AppSettings.getDashboardPids(this);

        String[] labels = new String[options.size()];
        boolean[] checked = new boolean[options.size()];
        for (int i = 0; i < options.size(); i++) {
            PidCatalog.Pid pid = options.get(i);
            String note = "";
            if (!pid.computed && supported.isEmpty()) note = "  — not checked on the truck yet";
            else if (!pid.computed && !supported.contains(pid.id)) note = "  — truck didn't answer";
            labels[i] = pid.label() + note;
            checked[i] = current.contains(pid.id);
        }

        new AlertDialog.Builder(this)
                .setTitle("Choose readings (PIDs) to track")
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

    /** History tab: oil life plus every scheduled item, from engine-on distance and time. */
    private void refreshFromDb() {
        final boolean needServiceList = currentTab == TAB_HISTORY;
        final boolean needDeals = currentTab == TAB_DEALS;
        final boolean needKoeo = currentTab == TAB_DIAGNOSTICS;
        final long trackingStart = AppSettings.getMaintenanceTrackingStart(this);
        final Set<String> hidden = AppSettings.getHiddenServiceItems(this);
        ioExecutor.execute(() -> {
            VehicleProfile profile = db.vehicleProfileDao().getSync();
            if (profile == null) return;
            lastProfile = profile;

            List<ServiceStatus> statuses = needServiceList || needDeals
                    ? MaintenanceScheduleEngine.computeAll(profile, db.maintenanceDao(), trackingStart,
                            db.sessionDao().milesSince(trackingStart))
                    : null;

            StringBuilder koeoSb = new StringBuilder();
            if (needKoeo) {
                for (KoeoReport report : db.koeoReportDao().latest(KOEO_REPORTS_SHOWN)) {
                    if (koeoSb.length() > 0) koeoSb.append("\n\n");
                    koeoSb.append(report.summary());
                }
                if (koeoSb.length() == 0) {
                    koeoSb.append("No key-on checks yet. One is taken automatically each time the key is "
                            + "turned ON before cranking, or tap Run Key-On Check with the key ON, engine off.");
                }
            }
            final String koeoText = koeoSb.toString();

            uiHandler.post(() -> {
                textOilLife.setText(String.format(Locale.US, "Oil life: %.0f%%", profile.oilLifePercent));
                textOdometer.setText(profile.odometerMiles > 0
                        ? String.format(Locale.US, "Odometer: %,.1f mi", profile.odometerMiles)
                        : "Odometer: not set — tap Set Odometer");
                textOilDetail.setText(String.format(Locale.US,
                        "Since last oil change: %,.1f mi, %.1f engine hrs",
                        profile.milesSinceOilChange, profile.engineHoursSinceOilChange));
                if (statuses != null && needServiceList) renderServiceList(statuses, hidden);
                if (statuses != null && needDeals) renderDeals(statuses, hidden);
                if (needKoeo) textKoeoReports.setText(koeoText);
            });
        });
    }

    /**
     * One tappable row per service: tap to record it, long-press to hide it.
     * Anything due floats to a "Needs attention" group on top; the rest sit
     * under their category. Only rebuilt when something visible changed, so
     * the 1.5 s refresh doesn't eat taps.
     */
    private void renderServiceList(List<ServiceStatus> statuses, Set<String> hidden) {
        List<ServiceStatus> visible = new ArrayList<>();
        List<ServiceStatus> attention = new ArrayList<>();
        StringBuilder signature = new StringBuilder(hidden.toString());
        for (ServiceStatus s : statuses) {
            if (hidden.contains(s.type.name())) continue;
            visible.add(s);
            if (s.dueSoon) attention.add(s);
            signature.append('|').append(serviceSubtitle(s));
        }
        if (signature.toString().equals(lastServiceListSignature)) return;
        lastServiceListSignature = signature.toString();

        Collections.sort(attention, (a, b) -> Double.compare(b.percentOfLifeUsed, a.percentOfLifeUsed));
        layoutServiceList.removeAllViews();

        if (!attention.isEmpty()) {
            addSectionHeader("Needs attention");
            for (ServiceStatus s : attention) addServiceRow(s);
        }
        for (ServiceInterval.Category category : ServiceInterval.Category.values()) {
            boolean headerAdded = false;
            for (ServiceStatus s : visible) {
                if (s.interval.category != category || s.dueSoon) continue;
                if (!headerAdded) {
                    addSectionHeader(category.label);
                    headerAdded = true;
                }
                addServiceRow(s);
            }
        }

        addSectionHeader("Other");
        addRow(cMuted, "Upgrade or other work",
                "Mods, repairs, anything not on the schedule", cMuted,
                v -> openServiceRecord("OTHER"), null);
        if (!hidden.isEmpty()) {
            addRow(Color.TRANSPARENT, "Show " + hidden.size() + " hidden item" + (hidden.size() == 1 ? "" : "s"),
                    "Brings back services you hid", cMuted,
                    v -> {
                        AppSettings.clearHiddenServiceItems(this);
                        lastDbRefresh = 0;
                    }, null);
        }
    }

    private void addServiceRow(ServiceStatus s) {
        int color = s.overdue ? cCrit
                : s.dueSoon ? cWarn
                : cOk;
        int subtitleColor = s.overdue || s.dueSoon ? color : cMuted;
        addRow(color, s.displayName, serviceSubtitle(s), subtitleColor,
                v -> openServiceRecord(s.type.name()),
                v -> {
                    confirmHide(s);
                    return true;
                });
    }

    private static String serviceSubtitle(ServiceStatus s) {
        String state = s.overdue ? "OVERDUE" : (s.dueSoon ? "Due soon" : String.format(Locale.US, "%.0f%% used", s.percentOfLifeUsed));
        String line = s.interval.action.verb + " · " + state;
        String remaining = s.remainingText();
        if (!remaining.isEmpty()) line += " · " + remaining;
        if (!s.hasRecord) line += " · not logged yet";
        return line;
    }

    private void addSectionHeader(String label) {
        addSectionHeader(layoutServiceList, label);
    }

    private void addSectionHeader(LinearLayout target, String label) {
        float density = getResources().getDisplayMetrics().density;
        TextView header = new TextView(this);
        header.setText(label.toUpperCase(Locale.US));
        header.setTextSize(12);
        header.setTypeface(null, Typeface.BOLD);
        header.setTextColor(cMuted);
        header.setLetterSpacing(0.06f);
        header.setPadding(0, Math.round(18 * density), 0, Math.round(4 * density));
        target.addView(header);
    }

    private void addRow(int stripColor, String title, String subtitle, int subtitleColor,
                        View.OnClickListener onClick, View.OnLongClickListener onLongClick) {
        addRow(layoutServiceList, stripColor, title, subtitle, subtitleColor, onClick, onLongClick);
    }

    private void addRow(LinearLayout target, int stripColor, String title, String subtitle, int subtitleColor,
                        View.OnClickListener onClick, View.OnLongClickListener onLongClick) {
        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(12 * density);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Math.round(56 * density));
        TypedValue ripple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setBackgroundResource(ripple.resourceId);
        row.setClickable(true);
        row.setOnClickListener(onClick);
        if (onLongClick != null) row.setOnLongClickListener(onLongClick);

        View strip = new View(this);
        GradientDrawable stripShape = new GradientDrawable();
        stripShape.setColor(stripColor);
        stripShape.setCornerRadius(2 * density);
        strip.setBackground(stripShape);
        LinearLayout.LayoutParams stripLp = new LinearLayout.LayoutParams(Math.round(4 * density), Math.round(36 * density));
        stripLp.setMarginEnd(pad);
        row.addView(strip, stripLp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(0, pad / 2, 0, pad / 2);
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(16);
        titleView.setTypeface(null, Typeface.BOLD);
        TextView subtitleView = new TextView(this);
        subtitleView.setText(subtitle);
        subtitleView.setTextSize(13);
        subtitleView.setTextColor(subtitleColor);
        texts.addView(titleView);
        texts.addView(subtitleView);
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView chevron = new TextView(this);
        chevron.setText("›");
        chevron.setTextSize(22);
        chevron.setTextColor(cMuted);
        chevron.setPadding(pad, 0, Math.round(4 * density), 0);
        row.addView(chevron);

        target.addView(row);

        View divider = new View(this);
        divider.setBackgroundColor(ContextCompat.getColor(this, R.color.fs_divider));
        target.addView(divider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
    }

    // ---------------------------------------------------------------- Deals tab

    /**
     * Parts for every job at 80% of its interval or past it, most urgent first,
     * then the supported adapters. Links open in the browser; the app itself
     * stays offline. Trouble codes never feed this list (see DealsMatcher).
     */
    private void renderDeals(List<ServiceStatus> statuses, Set<String> hidden) {
        List<DealsMatcher.DealGroup> groups = DealsMatcher.match(statuses, hidden);
        StringBuilder signature = new StringBuilder();
        for (DealsMatcher.DealGroup g : groups) signature.append('|').append(serviceSubtitle(g.status));
        if (signature.toString().equals(lastDealsSignature) && layoutDealsList.getChildCount() > 0) return;
        lastDealsSignature = signature.toString();
        layoutDealsList.removeAllViews();

        if (BusinessConfig.anyPaidLinks()) addDealsNote(BusinessConfig.disclosure());

        if (groups.isEmpty()) {
            addSectionHeader(layoutDealsList, "Coming due");
            addDealsNote("Nothing is due soon. Parts for a service show up here once it reaches 80% of its "
                    + "interval, early enough to order them and have them arrive before it's due.");
        }
        for (DealsMatcher.DealGroup g : groups) {
            int color = g.status.overdue ? cCrit : cWarn;
            addSectionHeader(layoutDealsList, g.status.displayName + " · " + (g.status.overdue ? "overdue" : "due soon")
                    + (g.status.remainingText().isEmpty() ? "" : " · " + g.status.remainingText()));
            for (PartOffer offer : g.offers) addOfferRow(offer, color);
        }

        addSectionHeader(layoutDealsList, "Supported OBD adapters");
        for (PartOffer offer : DealsCatalog.adapters()) addOfferRow(offer, cMuted);
    }

    private void addOfferRow(PartOffer offer, int stripColor) {
        StringBuilder subtitle = new StringBuilder(offer.vendor);
        if (!offer.label().isEmpty()) subtitle.append(" · ").append(offer.label());
        if (!offer.note.isEmpty()) subtitle.append(" · ").append(offer.note);
        addRow(layoutDealsList, stripColor, offer.title, subtitle.toString(), cMuted, v -> openLink(offer.url), null);
    }

    private void addDealsNote(String text) {
        TextView note = new TextView(this);
        note.setText(text);
        note.setTextSize(13);
        note.setTextColor(cMuted);
        note.setPadding(0, Math.round(8 * getResources().getDisplayMetrics().density), 0, 0);
        layoutDealsList.addView(note);
    }

    private void openLink(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "No browser found to open the link", Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------------- Trouble codes (Checks tab)

    private void requestCodeScan() {
        if (!AppSettings.hasObdAdapterConfigured(this)) {
            Toast.makeText(this, "Pair your OBD adapter on the Account tab first", Toast.LENGTH_LONG).show();
            return;
        }
        DiagnosticCodes.request(DiagnosticCodes.Request.SCAN);
        if (!TrackingService.isRunning) startTrackingService();
        lastCodesSignature = "";
    }

    private void confirmClearCodes() {
        if (!TrackingService.isRunning) {
            Toast.makeText(this, "Turn the key on and tap Scan for Codes first", Toast.LENGTH_LONG).show();
            return;
        }
        if (LiveReadings.engineRunning) {
            new AlertDialog.Builder(this)
                    .setTitle("Shut the engine off first")
                    .setMessage("Ford's procedure is key on, engine off. Turn the engine off, leave the key on, then clear.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Clear trouble codes?")
                .setMessage("This erases the codes, the freeze-frame data saved with them, and resets the readiness "
                        + "monitors (P1000 shows until the truck has been driven enough). If the problem isn't fixed, "
                        + "the codes come back. Write them down first if a mechanic will need them.")
                .setPositiveButton("Clear Codes", (d, w) -> {
                    DiagnosticCodes.request(DiagnosticCodes.Request.CLEAR);
                    lastCodesSignature = "";
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Rebuilt only when something on it changed, like the service list. */
    private void updateCodesTab() {
        DiagnosticCodes.Scan scan = DiagnosticCodes.lastScan != null ? DiagnosticCodes.lastScan : savedCodeScan;
        boolean busy = DiagnosticCodes.busy;
        String message = DiagnosticCodes.message;
        boolean running = TrackingService.isRunning;
        boolean paired = AppSettings.hasObdAdapterConfigured(this);
        String signature = (scan == null ? "-" : scan.serialize()) + '|' + busy + '|' + message + '|' + running + '|' + paired;
        if (signature.equals(lastCodesSignature)) return;
        lastCodesSignature = signature;

        if (scan == null) {
            textMilStatus.setText("Not scanned yet");
            textMilStatus.setTextColor(getColor(R.color.cyan_display));
        } else {
            String count = scan.codes.isEmpty() ? "no codes"
                    : scan.codes.size() + (scan.codes.size() == 1 ? " code" : " codes");
            if (scan.milOn == null) {
                textMilStatus.setText("Not reported · " + count);
                textMilStatus.setTextColor(getColor(R.color.cyan_display));
            } else {
                textMilStatus.setText((scan.milOn ? "ON" : "OFF") + " · " + count);
                textMilStatus.setTextColor(scan.milOn ? cCrit : cOk);
            }
        }

        String info;
        if (busy) {
            info = "Reading the truck…";
        } else if (!paired) {
            info = "Pair your OBD adapter on the Account tab to read codes.";
        } else if (scan == null) {
            info = running ? "Codes are read automatically when the truck connects." : "Tracking is off. Scan for Codes turns it on.";
        } else {
            info = "Last scan " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(new Date(scan.timestamp)) + (running ? "" : " · tracking is off");
        }
        if (message != null && !busy) info = message + "\n" + info;
        textCodesScanInfo.setText(info);

        buttonScanCodes.setEnabled(!busy);
        buttonClearCodes.setEnabled(!busy && scan != null && (!scan.codes.isEmpty() || Boolean.TRUE.equals(scan.milOn)));

        layoutCodesList.removeAllViews();
        if (scan == null) return;
        if (scan.codes.isEmpty()) {
            TextView none = new TextView(this);
            none.setText("No stored or pending codes.");
            none.setTextSize(16);
            none.setPadding(0, Math.round(8 * getResources().getDisplayMetrics().density), 0, 0);
            layoutCodesList.addView(none);
            return;
        }
        for (DTCReader.DtcRecord record : scan.codes) addCodeRow(record);
    }

    /** One code with everything it means written out underneath; nothing to tap through. */
    private void addCodeRow(DTCReader.DtcRecord record) {
        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(12 * density);
        int color = severityColor(record.severity);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        View strip = new View(this);
        GradientDrawable stripShape = new GradientDrawable();
        stripShape.setColor(color);
        stripShape.setCornerRadius(2 * density);
        strip.setBackground(stripShape);
        LinearLayout.LayoutParams stripLp = new LinearLayout.LayoutParams(Math.round(4 * density),
                LinearLayout.LayoutParams.MATCH_PARENT);
        stripLp.setMarginEnd(pad);
        row.addView(strip, stripLp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(0, pad, 0, pad);

        TextView title = new TextView(this);
        title.setText(record.code + "  " + record.title);
        title.setTextSize(16);
        title.setTypeface(null, Typeface.BOLD);
        texts.addView(title);

        TextView severity = new TextView(this);
        severity.setText(record.severity.label + " · " + record.mode.label);
        severity.setTextSize(14);
        severity.setTypeface(null, Typeface.BOLD);
        severity.setTextColor(color);
        severity.setPadding(0, Math.round(2 * density), 0, 0);
        texts.addView(severity);

        addCodeDetail(texts, record.severity.explanation);
        addCodeDetail(texts, record.mode.label + ": " + record.mode.explanation);
        if (record.hint != null) addCodeDetail(texts, "What to check: " + record.hint);

        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        layoutCodesList.addView(row);

        View divider = new View(this);
        divider.setBackgroundColor(ContextCompat.getColor(this, R.color.fs_divider));
        layoutCodesList.addView(divider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
    }

    private void addCodeDetail(LinearLayout parent, String text) {
        TextView detail = new TextView(this);
        detail.setText(text);
        detail.setTextSize(14);
        detail.setTextColor(cMuted);
        detail.setPadding(0, Math.round(4 * getResources().getDisplayMetrics().density), 0, 0);
        parent.addView(detail);
    }

    private int severityColor(DtcSeverity severity) {
        switch (severity) {
            case HIGH: return cCrit;
            case MEDIUM: return cWarn;
            case LOW: return ContextCompat.getColor(this, R.color.fs_val_yellow);
            case INFO: return cMuted;
            default: return Color.parseColor("#78909C");
        }
    }

    private void openServiceRecord(String type) {
        startActivity(new Intent(this, LogMaintenanceActivity.class)
                .putExtra(LogMaintenanceActivity.EXTRA_SERVICE_TYPE, type));
    }

    private void confirmHide(ServiceStatus s) {
        new AlertDialog.Builder(this)
                .setTitle("Hide " + s.displayName + "?")
                .setMessage("Use this for services that don't apply to your truck (for example, transfer case on a 2WD). "
                        + "Hidden items never send alerts. You can bring them back at the bottom of this list.")
                .setPositiveButton("Hide", (d, w) -> {
                    AppSettings.setServiceItemHidden(this, s.type.name(), true);
                    lastDbRefresh = 0;
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
