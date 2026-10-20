package com.autosentry.app.ui;

import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.autosentry.app.R;
import com.autosentry.app.data.AppDatabase;
import com.autosentry.app.data.VehicleProfile;
import com.autosentry.app.maintenance.MaintenanceScheduleEngine;
import com.autosentry.app.maintenance.ServiceInterval;
import com.autosentry.app.maintenance.ServiceStatus;
import com.autosentry.app.obd.DTCReader;
import com.autosentry.app.obd.DiagnosticCodes;
import com.autosentry.app.obd.DtcSeverity;
import com.autosentry.app.obd.LiveReadings;
import com.autosentry.app.obd.PidCatalog;
import com.autosentry.app.service.TrackingService;
import com.autosentry.app.settings.AppSettings;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Three tabs: Dashboard (live readings the user picks via "Edit Dashboard",
 * plus adapter setup), Service (oil life and the full service schedule,
 * driven by engine-on time and distance), and Codes (trouble codes from the
 * truck, each with its meaning spelled out underneath).
 */
public class MainActivity extends AppCompatActivity {
    private static final long REFRESH_INTERVAL_MS = 500L;
    private static final long DB_REFRESH_INTERVAL_MS = 1500L;
    private static final int TAB_DASHBOARD = 0, TAB_SERVICE = 1, TAB_CODES = 2;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = this::refresh;

    private AppDatabase db;
    private long lastDbRefresh = 0;
    private volatile VehicleProfile lastProfile;
    private int currentTab = TAB_DASHBOARD;

    private final Map<Integer, TextView> tileValues = new HashMap<>();
    private GridLayout gridTiles;
    private View scrollDashboard, scrollService, scrollCodes;
    private TextView textAdapterStatus, textLiveStatus, textOilLife, textOilDetail, textOdometer,
            textMilStatus, textCodesScanInfo;
    private LinearLayout layoutServiceList, layoutCodesList;
    private String lastServiceListSignature = "";
    private String lastCodesSignature = "";
    private DiagnosticCodes.Scan savedCodeScan;
    private Button buttonTabDashboard, buttonTabService, buttonTabCodes, buttonEditDashboard, buttonToggleTracking,
            buttonMaintenanceHistory, buttonPairAdapter, buttonAutoTrackingToggle,
            buttonBackgroundAccess, buttonSystemsScan, buttonDebugLog, buttonScanCodes, buttonClearCodes;

    /** Set by the service-alert notification so tapping it lands on the Service tab. */
    public static final String EXTRA_OPEN_SERVICE_TAB = "open_service_tab";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        db = AppDatabase.getInstance(this);

        gridTiles = findViewById(R.id.gridTiles);
        scrollDashboard = findViewById(R.id.scrollDashboard);
        scrollService = findViewById(R.id.scrollService);
        scrollCodes = findViewById(R.id.scrollCodes);
        textMilStatus = findViewById(R.id.textMilStatus);
        textCodesScanInfo = findViewById(R.id.textCodesScanInfo);
        layoutCodesList = findViewById(R.id.layoutCodesList);
        buttonTabCodes = findViewById(R.id.buttonTabCodes);
        buttonScanCodes = findViewById(R.id.buttonScanCodes);
        buttonClearCodes = findViewById(R.id.buttonClearCodes);
        buttonSystemsScan = findViewById(R.id.buttonSystemsScan);
        textAdapterStatus = findViewById(R.id.textAdapterStatus);
        textLiveStatus = findViewById(R.id.textLiveStatus);
        textOilLife = findViewById(R.id.textOilLife);
        textOilDetail = findViewById(R.id.textOilDetail);
        textOdometer = findViewById(R.id.textOdometer);
        layoutServiceList = findViewById(R.id.layoutServiceList);
        buttonTabDashboard = findViewById(R.id.buttonTabDashboard);
        buttonTabService = findViewById(R.id.buttonTabService);
        buttonEditDashboard = findViewById(R.id.buttonEditDashboard);
        buttonToggleTracking = findViewById(R.id.buttonToggleTracking);
        buttonMaintenanceHistory = findViewById(R.id.buttonMaintenanceHistory);
        buttonPairAdapter = findViewById(R.id.buttonPairAdapter);
        buttonAutoTrackingToggle = findViewById(R.id.buttonAutoTrackingToggle);
        buttonBackgroundAccess = findViewById(R.id.buttonBackgroundAccess);
        buttonDebugLog = findViewById(R.id.buttonDebugLog);

        if (!PermissionFlow.hasAllPermissions(this)) {
            PermissionFlow.requestAllPermissions(this);
        }

        buttonTabDashboard.setOnClickListener(v -> showTab(TAB_DASHBOARD));
        buttonTabService.setOnClickListener(v -> showTab(TAB_SERVICE));
        buttonTabCodes.setOnClickListener(v -> showTab(TAB_CODES));
        buttonScanCodes.setOnClickListener(v -> requestCodeScan());
        buttonClearCodes.setOnClickListener(v -> confirmClearCodes());
        buttonSystemsScan.setOnClickListener(v -> startActivity(new Intent(this, SystemsScanActivity.class)));
        buttonEditDashboard.setOnClickListener(v -> showEditDashboard());
        buttonToggleTracking.setOnClickListener(v -> toggleTracking());
        buttonMaintenanceHistory.setOnClickListener(v -> startActivity(new Intent(this, MaintenanceHistoryActivity.class)));
        buttonPairAdapter.setOnClickListener(v -> pickObdAdapter());
        buttonAutoTrackingToggle.setOnClickListener(v -> toggleAutoTracking());
        buttonBackgroundAccess.setOnClickListener(v -> requestBackgroundAccess());
        buttonDebugLog.setOnClickListener(v -> startActivity(new Intent(this, DebugLogActivity.class)));

        rebuildTiles();
        savedCodeScan = AppSettings.getLastCodeScan(this);
        showTab(getIntent().getBooleanExtra(EXTRA_OPEN_SERVICE_TAB, false) ? TAB_SERVICE : TAB_DASHBOARD);

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

    private void showTab(int tab) {
        currentTab = tab;
        View[] pages = {scrollDashboard, scrollService, scrollCodes};
        Button[] buttons = {buttonTabDashboard, buttonTabService, buttonTabCodes};
        ColorStateList selected = ColorStateList.valueOf(getColor(R.color.colorPrimary));
        ColorStateList idle = ColorStateList.valueOf(getColor(R.color.card_border));
        for (int i = 0; i < pages.length; i++) {
            pages[i].setVisibility(i == tab ? View.VISIBLE : View.GONE);
            buttons[i].setEnabled(i != tab);
            buttons[i].setBackgroundTintList(i == tab ? selected : idle);
        }
        lastDbRefresh = 0; // refresh the service tab right away
        lastCodesSignature = ""; // and the codes tab
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

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra(EXTRA_OPEN_SERVICE_TAB, false)) showTab(TAB_SERVICE);
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
        if (currentTab == TAB_CODES) updateCodesTab();
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
        final boolean needServiceList = currentTab == TAB_SERVICE;
        final long trackingStart = AppSettings.getMaintenanceTrackingStart(this);
        final Set<String> hidden = AppSettings.getHiddenServiceItems(this);
        ioExecutor.execute(() -> {
            VehicleProfile profile = db.vehicleProfileDao().getSync();
            if (profile == null) return;
            lastProfile = profile;

            List<ServiceStatus> statuses = needServiceList
                    ? MaintenanceScheduleEngine.computeAll(profile, db.maintenanceDao(), trackingStart)
                    : null;

            uiHandler.post(() -> {
                textOilLife.setText(String.format(Locale.US, "Oil life: %.0f%%", profile.oilLifePercent));
                textOdometer.setText(String.format(Locale.US, "Odometer: %,.1f mi", profile.odometerMiles));
                textOilDetail.setText(String.format(Locale.US,
                        "Since last oil change: %,.1f mi, %.1f engine hrs",
                        profile.milesSinceOilChange, profile.engineHoursSinceOilChange));
                if (statuses != null) renderServiceList(statuses, hidden);
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
        addRow(Color.parseColor("#9E9E9E"), "Upgrade or other work",
                "Mods, repairs, anything not on the schedule", Color.parseColor("#757575"),
                v -> openServiceRecord("OTHER"), null);
        if (!hidden.isEmpty()) {
            addRow(Color.TRANSPARENT, "Show " + hidden.size() + " hidden item" + (hidden.size() == 1 ? "" : "s"),
                    "Brings back services you hid", Color.parseColor("#757575"),
                    v -> {
                        AppSettings.clearHiddenServiceItems(this);
                        lastDbRefresh = 0;
                    }, null);
        }
    }

    private void addServiceRow(ServiceStatus s) {
        int color = s.overdue ? Color.parseColor("#D32F2F")
                : s.dueSoon ? Color.parseColor("#F57C00")
                : Color.parseColor("#43A047");
        int subtitleColor = s.overdue || s.dueSoon ? color : Color.parseColor("#757575");
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
        float density = getResources().getDisplayMetrics().density;
        TextView header = new TextView(this);
        header.setText(label.toUpperCase(Locale.US));
        header.setTextSize(12);
        header.setTypeface(null, Typeface.BOLD);
        header.setTextColor(Color.parseColor("#757575"));
        header.setLetterSpacing(0.06f);
        header.setPadding(0, Math.round(18 * density), 0, Math.round(4 * density));
        layoutServiceList.addView(header);
    }

    private void addRow(int stripColor, String title, String subtitle, int subtitleColor,
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
        chevron.setTextColor(Color.parseColor("#9E9E9E"));
        chevron.setPadding(pad, 0, Math.round(4 * density), 0);
        row.addView(chevron);

        layoutServiceList.addView(row);

        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#1F000000"));
        layoutServiceList.addView(divider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
    }

    // ---------------------------------------------------------------- Codes tab

    private void requestCodeScan() {
        if (!AppSettings.hasObdAdapterConfigured(this)) {
            Toast.makeText(this, "Pair your OBD adapter on the Dashboard tab first", Toast.LENGTH_LONG).show();
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
                textMilStatus.setTextColor(Color.parseColor(scan.milOn ? "#D32F2F" : "#43A047"));
            }
        }

        String info;
        if (busy) {
            info = "Reading the truck…";
        } else if (!paired) {
            info = "Pair your OBD adapter on the Dashboard tab to read codes.";
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
        divider.setBackgroundColor(Color.parseColor("#1F000000"));
        layoutCodesList.addView(divider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
    }

    private void addCodeDetail(LinearLayout parent, String text) {
        TextView detail = new TextView(this);
        detail.setText(text);
        detail.setTextSize(14);
        detail.setTextColor(Color.parseColor("#9E9E9E"));
        detail.setPadding(0, Math.round(4 * getResources().getDisplayMetrics().density), 0, 0);
        parent.addView(detail);
    }

    private static int severityColor(DtcSeverity severity) {
        switch (severity) {
            case HIGH: return Color.parseColor("#D32F2F");
            case MEDIUM: return Color.parseColor("#F57C00");
            case LOW: return Color.parseColor("#FBC02D");
            case INFO: return Color.parseColor("#9E9E9E");
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
