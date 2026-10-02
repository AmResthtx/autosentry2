package com.autosentry.app.ui;

import android.app.AlarmManager;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.autosentry.app.data.AppDatabase;
import com.autosentry.app.data.VehicleProfile;
import com.autosentry.app.obd.LiveReadings;
import com.autosentry.app.obd.PidCatalog;
import com.autosentry.app.service.TrackingService;
import com.autosentry.app.settings.AppSettings;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Setup wizard, phase 1: get the app talking to the truck.
 * Welcome, permissions, adapter, truck test, vehicle, readings, finish.
 * Each step saves as it goes, so leaving the wizard part way loses nothing; finishing
 * records phase 1 as done (AppSettings.setSetupPhaseDone). Later phases get their own wizard.
 */
public class SetupWizardActivity extends AppCompatActivity {
    public static final int PHASE = 1;

    private static final int STEP_WELCOME = 0;
    private static final int STEP_PERMISSIONS = 1;
    private static final int STEP_ADAPTER = 2;
    private static final int STEP_TEST = 3;
    private static final int STEP_VEHICLE = 4;
    private static final int STEP_READINGS = 5;
    private static final int STEP_DONE = 6;
    private static final String[] STEP_TITLES = {
            "Welcome", "Permissions", "OBD adapter", "Test the truck", "Your truck", "Readings", "All set"};
    private static final long REFRESH_MS = 500L;
    private static final String KEY_STEP = "step";

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private AppDatabase db;
    private int step = STEP_WELCOME;
    private TextView textTitle;
    private LinearLayout content;
    private Button buttonBack, buttonNext;

    // Choices held between renders; saved when the step is left.
    private volatile boolean severeDuty = true;
    private volatile String odometerText = "";
    private Set<Integer> chosenPids;
    private TextView testReport;
    private Button testRunButton;
    private final Runnable testPoll = new Runnable() {
        @Override
        public void run() {
            if (step != STEP_TEST || testReport == null) return;
            testReport.setText(LiveReadings.testReport);
            testRunButton.setEnabled(!LiveReadings.testRunning);
            updateNav();
            uiHandler.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = AppDatabase.getInstance(this);
        if (savedInstanceState != null) step = savedInstanceState.getInt(KEY_STEP, STEP_WELCOME);
        chosenPids = new LinkedHashSet<>(AppSettings.getDashboardPids(this));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        textTitle = new TextView(this);
        textTitle.setTextSize(20);
        textTitle.setPadding(dp(16), dp(16), dp(16), dp(8));
        root.addView(textTitle);

        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(8), dp(16), dp(16));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setPadding(dp(8), dp(8), dp(8), dp(8));
        buttonBack = new Button(this);
        buttonBack.setText("Back");
        buttonBack.setOnClickListener(v -> goBack());
        buttonNext = new Button(this);
        buttonNext.setOnClickListener(v -> goNext());
        nav.addView(buttonBack, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        nav.addView(buttonNext, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(nav);
        // Android 15 draws behind the system bars; keep Back/Next above the navigation bar.
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(0, 0, 0, bars.bottom);
            return insets;
        });
        setContentView(root);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                goBack();
            }
        });

        ioExecutor.execute(() -> {
            if (db.vehicleProfileDao().getSync() == null) db.vehicleProfileDao().insert(VehicleProfile.newDefault());
            VehicleProfile p = db.vehicleProfileDao().getSync();
            if (p != null) {
                severeDuty = p.severeDuty;
                odometerText = p.odometerMiles > 0 ? String.format(Locale.US, "%.0f", p.odometerMiles) : "";
            }
        });
        render();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(KEY_STEP, step);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The user may have just come back from a system settings screen.
        if (step == STEP_PERMISSIONS || step == STEP_ADAPTER) render();
        if (step == STEP_TEST) uiHandler.post(testPoll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        uiHandler.removeCallbacks(testPoll);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ioExecutor.shutdown();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (step == STEP_PERMISSIONS) render();
    }

    // ---- navigation ----

    private void goBack() {
        if (step == STEP_WELCOME) {
            finish();
            return;
        }
        leaveStep();
        step--;
        render();
    }

    private void goNext() {
        if (step == STEP_DONE) {
            AppSettings.setSetupPhaseDone(this, PHASE);
            TrackingService.startIfEnabled(this, "setup finished");
            finish();
            return;
        }
        leaveStep();
        step++;
        render();
    }

    /** Saves whatever the current step collected. */
    private void leaveStep() {
        uiHandler.removeCallbacks(testPoll);
        if (step == STEP_VEHICLE) {
            final boolean severe = severeDuty;
            final String odo = odometerText.trim();
            ioExecutor.execute(() -> {
                db.vehicleProfileDao().setSevereDuty(severe);
                try {
                    if (!odo.isEmpty()) db.vehicleProfileDao().setOdometer(Double.parseDouble(odo));
                } catch (NumberFormatException ignored) {
                    // Left as it was; the Account tab can still set it.
                }
            });
        } else if (step == STEP_READINGS) {
            AppSettings.setDashboardPids(this, new ArrayList<>(chosenPids));
        }
    }

    private boolean canAdvance() {
        switch (step) {
            case STEP_PERMISSIONS:
                return PermissionFlow.hasRequiredPermissions(this);
            case STEP_ADAPTER:
                return AppSettings.hasObdAdapterConfigured(this);
            default:
                return true;
        }
    }

    private boolean testPassed() {
        return !LiveReadings.testRunning && LiveReadings.testReport.contains("PASS");
    }

    private void updateNav() {
        buttonBack.setVisibility(step == STEP_WELCOME ? View.INVISIBLE : View.VISIBLE);
        boolean enabled = canAdvance();
        String label = "Next";
        if (step == STEP_WELCOME) label = "Start";
        else if (step == STEP_DONE) label = "Finish";
        else if (step == STEP_TEST) {
            enabled = !LiveReadings.testRunning;
            label = testPassed() ? "Next" : "Skip for now";
        }
        buttonNext.setText(label);
        buttonNext.setEnabled(enabled);
    }

    // ---- rendering ----

    private void render() {
        textTitle.setText("Setup " + PHASE + " · Step " + (step + 1) + " of " + STEP_TITLES.length
                + " — " + STEP_TITLES[step]);
        content.removeAllViews();
        testReport = null;
        switch (step) {
            case STEP_WELCOME: renderWelcome(); break;
            case STEP_PERMISSIONS: renderPermissions(); break;
            case STEP_ADAPTER: renderAdapter(); break;
            case STEP_TEST: renderTest(); break;
            case STEP_VEHICLE: renderVehicle(); break;
            case STEP_READINGS: renderReadings(); break;
            default: renderDone(); break;
        }
        updateNav();
    }

    private void renderWelcome() {
        body("This wizard connects AutoSentry to your truck. It takes a few minutes and you can stop at any point; "
                + "each step is saved.");
        body("Have ready:\n• Your OBD adapter plugged into the truck's OBD port\n"
                + "• The key in the ON position (engine off is fine) for the truck test\n"
                + "• The adapter paired in Android Bluetooth settings (the wizard shows you where)");
        body("Steps: permissions, pick the adapter, test the truck, describe the truck, choose the readings to show.");
    }

    private void renderPermissions() {
        boolean bt = PermissionFlow.hasRequiredPermissions(this);
        status(bt, "Bluetooth (Nearby devices) — required to talk to the adapter");
        status(granted(android.Manifest.permission.ACCESS_FINE_LOCATION),
                "Location — optional, GPS fills in distance if the truck's speed drops out");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            status(granted(android.Manifest.permission.POST_NOTIFICATIONS),
                    "Notifications — optional, service reminders and the tracking notice");
        }
        Button grant = button("Grant permissions", v -> PermissionFlow.requestMissingPermissions(this));
        grant.setEnabled(!bt || !granted(android.Manifest.permission.ACCESS_FINE_LOCATION));

        PowerManager pm = getSystemService(PowerManager.class);
        boolean exempt = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        status(exempt, "Background start — lets tracking begin at key-on without opening the app");
        status(exactAlarmsAllowed(), "Alarms — lets the tablet sleep while the truck is off and still catch key-on");
        button("Allow background start and alarms", v -> requestBackgroundAccess());
        if (!bt) body("Bluetooth is required to continue.");
    }

    private void renderAdapter() {
        String name = AppSettings.getObdAdapterName(this);
        if (AppSettings.hasObdAdapterConfigured(this)) {
            status(true, "Adapter: " + (name != null ? name : "OBD adapter")
                    + " (" + AppSettings.getObdAdapterAddress(this) + ")");
        } else {
            body("No adapter chosen yet.");
        }
        body("If your adapter isn't in the list, pair it first in Android Bluetooth settings "
                + "(plug it in, key ON, then pair; the code is usually 1234 or 0000), then come back.");
        button(AppSettings.hasObdAdapterConfigured(this) ? "Choose a different adapter" : "Choose adapter",
                v -> pickAdapter());
        button("Open Bluetooth settings", v -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
    }

    private void renderTest() {
        body("Turn the key to ON (engine off is fine), then run the test. It checks the Bluetooth link, the adapter, "
                + "the truck computer and a few live readings, and says which link is broken if something fails.");
        testRunButton = button("Run truck test", v -> startTest());
        testReport = new TextView(this);
        testReport.setTextSize(15);
        testReport.setTextIsSelectable(true);
        testReport.setPadding(0, dp(12), 0, 0);
        testReport.setText(LiveReadings.testReport);
        content.addView(testReport);
        body("Skipping is fine if the truck is out of reach. Run the test later from the Account tab; "
                + "the readings step will then show every reading as unchecked.");
        uiHandler.post(testPoll);
    }

    private void startTest() {
        LiveReadings.testRunning = true;
        LiveReadings.testReport = "Testing… (up to 30 s if the adapter is asleep)";
        AppSettings.setTrackingPaused(this, false);
        try {
            ContextCompat.startForegroundService(this, new Intent(this, TrackingService.class)
                    .setAction(TrackingService.ACTION_RUN_TEST));
        } catch (RuntimeException e) {
            LiveReadings.testRunning = false;
            LiveReadings.testReport = "✗ Couldn't start tracking: " + e.getMessage();
        }
    }

    private void renderVehicle() {
        body("2000 Ford F-250 7.3L Power Stroke — the only vehicle profile in this version. "
                + "Service intervals and oil temperature limits are set for it.");
        body("How is the truck driven?");
        RadioGroup duty = new RadioGroup(this);
        RadioButton severe = new RadioButton(this);
        severe.setText("Severe duty (towing, idling, short trips, dust, extremes): oil every 3,000 mi");
        severe.setId(View.generateViewId());
        RadioButton normal = new RadioButton(this);
        normal.setText("Normal duty: oil every 5,000 mi");
        normal.setId(View.generateViewId());
        duty.addView(severe);
        duty.addView(normal);
        duty.check(severeDuty ? severe.getId() : normal.getId());
        duty.setOnCheckedChangeListener((g, id) -> severeDuty = id == severe.getId());
        content.addView(duty);

        body("Odometer (miles), optional:");
        EditText odo = new EditText(this);
        odo.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        odo.setText(odometerText);
        odo.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) { odometerText = s.toString(); }
        });
        content.addView(odo);
    }

    private void renderReadings() {
        Set<Integer> supported = !LiveReadings.supported.isEmpty()
                ? LiveReadings.supported : AppSettings.getSupportedPids(this);
        body("Pick what to show on the dashboard. You can change this any time with Edit Dashboard."
                + (supported.isEmpty() ? " The truck hasn't been checked yet, so none can be confirmed." : ""));
        for (PidCatalog.Pid pid : PidCatalog.all()) {
            String note = "";
            if (!pid.computed && supported.isEmpty()) note = "  — not checked on the truck yet";
            else if (!pid.computed && !supported.contains(pid.id)) note = "  — truck didn't answer";
            CheckBox box = new CheckBox(this);
            box.setText(pid.label() + note);
            box.setChecked(chosenPids.contains(pid.id));
            box.setOnCheckedChangeListener((b, on) -> {
                if (on) chosenPids.add(pid.id);
                else chosenPids.remove(pid.id);
            });
            content.addView(box);
        }
    }

    private void renderDone() {
        String name = AppSettings.getObdAdapterName(this);
        status(PermissionFlow.hasRequiredPermissions(this), "Bluetooth permission");
        status(AppSettings.hasObdAdapterConfigured(this), "Adapter: " + (name != null ? name : "not chosen"));
        status(testPassed(), testPassed() ? "Truck test passed" : "Truck test not passed yet — run it from the Account tab");
        status(true, "Dashboard: " + chosenPids.size() + " readings");
        body("Tracking starts by itself when the truck is keyed on. Tap Finish to open the app.");
        body("Later setup phases will add more to this wizard; you'll find it under Account > Setup Wizard.");
    }

    // ---- actions ----

    private boolean granted(String perm) {
        return ContextCompat.checkSelfPermission(this, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private boolean exactAlarmsAllowed() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        AlarmManager alarms = getSystemService(AlarmManager.class);
        return alarms != null && alarms.canScheduleExactAlarms();
    }

    /** Same flow as the Account tab: battery exemption first, then exact alarms. */
    private void requestBackgroundAccess() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            if (!exactAlarmsAllowed() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:" + getPackageName())));
            } else {
                Toast.makeText(this, "Already allowed", Toast.LENGTH_SHORT).show();
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

    private void pickAdapter() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Toast.makeText(this, "This device has no Bluetooth adapter", Toast.LENGTH_LONG).show();
            return;
        }
        Set<BluetoothDevice> bonded;
        try {
            if (!adapter.isEnabled()) {
                Toast.makeText(this, "Turn on Bluetooth first", Toast.LENGTH_LONG).show();
                return;
            }
            bonded = adapter.getBondedDevices();
        } catch (SecurityException e) {
            Toast.makeText(this, "Bluetooth permission required — go back one step", Toast.LENGTH_LONG).show();
            return;
        }
        if (bonded.isEmpty()) {
            Toast.makeText(this, "No paired devices — pair the adapter in Bluetooth settings first", Toast.LENGTH_LONG).show();
            return;
        }
        BluetoothDevice[] devices = bonded.toArray(new BluetoothDevice[0]);
        String[] labels = new String[devices.length];
        String[] names = new String[devices.length];
        for (int i = 0; i < devices.length; i++) {
            String name;
            try {
                name = devices[i].getName();
            } catch (SecurityException e) {
                name = null;
            }
            names[i] = name != null ? name : "OBD Adapter";
            labels[i] = (name != null ? name : "Unknown device") + "  (" + devices[i].getAddress() + ")";
        }
        new AlertDialog.Builder(this)
                .setTitle("Which device is your OBD adapter?")
                .setItems(labels, (dialog, which) -> {
                    AppSettings.setObdAdapter(this, devices[which].getAddress(), names[which]);
                    AppSettings.setAutoTrackingEnabled(this, true);
                    AppSettings.setTrackingPaused(this, false);
                    // A running session must pick up the newly chosen adapter.
                    if (TrackingService.isRunning) stopService(new Intent(this, TrackingService.class));
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- view helpers ----

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView body(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(16);
        t.setPadding(0, dp(6), 0, dp(6));
        content.addView(t);
        return t;
    }

    private void status(boolean ok, String text) {
        body((ok ? "✓ " : "✗ ") + text);
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(b, lp);
        return b;
    }
}
