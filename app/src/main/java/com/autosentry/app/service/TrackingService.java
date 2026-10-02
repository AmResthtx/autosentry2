package com.autosentry.app.service;

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.autosentry.app.data.AppDatabase;
import com.autosentry.app.data.KoeoReport;
import com.autosentry.app.data.Session;
import com.autosentry.app.data.TripPoint;
import com.autosentry.app.data.VehicleProfile;
import com.autosentry.app.engine.OilLifeEngine;
import com.autosentry.app.fuel.MpgCalculator;
import com.autosentry.app.gps.GpsTracker;
import com.autosentry.app.maintenance.MaintenanceScheduleEngine;
import com.autosentry.app.maintenance.ServiceItemType;
import com.autosentry.app.maintenance.ServiceStatus;
import com.autosentry.app.notifications.NotificationUtils;
import com.autosentry.app.obd.DTCReader;
import com.autosentry.app.obd.ELM327Adapter;
import com.autosentry.app.obd.LiveReadings;
import com.autosentry.app.obd.PidCatalog;
import com.autosentry.app.settings.AppSettings;
import com.autosentry.app.util.AppLog;

import java.io.IOException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Foreground service that runs while the app is tracking: keeps the OBD
 * adapter connected (retrying if the truck or adapter isn't up yet), reads
 * every PID the dashboard wants plus the ones tracking needs, and while the
 * engine is actually running:
 *   1. adds engine-on time and distance (speed x time) to the trip totals
 *   2. advances the vehicle profile's odometer, engine hours and oil life
 *   3. saves a TripPoint about once a second
 *
 * Distance comes from the truck's own speed reading when it reports one, and
 * from GPS otherwise (and from GPS across any gap in OBD data mid-drive).
 * Requires a paired adapter; one that fails to connect is retried, never faked.
 *
 * An OBD adapter never opens the Bluetooth link itself — the tablet has to — so
 * something must stay around to notice key-on. Two modes:
 *   - Truck on (engine turning, trip open): full power. CPU kept awake, 4 polls
 *     a second, GPS on, a dropped link retried every second.
 *   - Truck off: low power. No wake lock, no GPS, adapter link closed so it can
 *     sleep; an exact alarm wakes the tablet every 15 s for one quick knock, and
 *     unlocking the screen or plugging in power knocks at once.
 * It is restarted on boot, app update and by a 15-minute watchdog, and only a
 * typed STOP or turning auto-tracking off keeps it down.
 */
public class TrackingService extends Service {
    private static final String TAG = "TrackingService";

    /** True from start until destroy; the dashboard uses it for the Start/Stop button. */
    public static volatile boolean isRunning = false;

    /** Runs the step-by-step adapter check (see runConnectionTest) on the poll thread. */
    public static final String ACTION_RUN_TEST = "com.autosentry.app.RUN_CONNECTION_TEST";
    /** Takes a key-on-engine-off snapshot now (key ON, engine off); report goes to LiveReadings.testReport. */
    public static final String ACTION_RUN_KOEO = "com.autosentry.app.RUN_KOEO_CHECK";
    private static final String ACTION_IDLE_CHECK = "com.autosentry.app.IDLE_CHECK";

    private static final long POLL_INTERVAL_MS = 250L;
    // Mid-trip link loss: retry as fast as the adapter allows (a failed connect already takes ~5 s).
    private static final long RETRY_INTERVAL_MS = 1000L;
    // Truck off: one knock this often. Covers the 7.3L's glow-plug wait between key-on and crank.
    private static final long IDLE_CHECK_INTERVAL_MS = 15_000L;
    private static final long PERSIST_INTERVAL_MS = 1000L;
    private static final long END_TRIP_AFTER_ENGINE_OFF_MS = 120_000L;
    // A longer gap than this (reconnect, stall) is not driving time; don't bill it to the trip.
    private static final double MAX_TICK_SECONDS = 5.0;
    // Renewed on every poll tick so it never outlives the service, but survives any one gap.
    private static final long WAKE_LOCK_TIMEOUT_MS = 60_000L;
    // A missed first answer (bus still waking up) must not hide oil temp for the whole drive.
    private static final long OIL_PROBE_RETRY_MS = 30_000L;
    // Oil temp moves slowly; reading it less often keeps the header switches off every poll.
    private static final long OIL_READ_INTERVAL_MS = 2_000L;
    // Cranking stays well under this and idle sits above it, so crossing it means the engine started.
    private static final double STARTED_RPM = 500.0;
    // GPS below this is parked jitter, not driving.
    private static final double GPS_MOVING_MPH = 3.0;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;

    private AppDatabase db;
    private GpsTracker gpsTracker;
    private ELM327Adapter realAdapter;
    private boolean started = false;
    private volatile boolean stopped = false;

    private Session activeSession;
    private VehicleProfile profile;
    private Set<Integer> supportedPids = Collections.emptySet();
    // True when the truck answers Ford's enhanced oil-temp PID (7.3L) instead of the standard one.
    private boolean fordOilTemp = false;
    private boolean pwmBus = false;
    private boolean canBus = false;
    // This key cycle's KOEO report: captured before cranking, saved again when the crank ends.
    private KoeoReport koeo;
    private boolean koeoDue = false;
    private long crankStart = 0;
    private long lastOilProbe = 0;
    private long lastOilRead = 0;
    // Avoids re-notifying every tick once an item crosses 80%; cleared
    // when the item is serviced (odometerAtEvent moves the baseline back).
    private final Set<ServiceItemType> notifiedDueSoon = EnumSet.noneOf(ServiceItemType.class);

    private volatile double lastSpeedMph = 0;
    private volatile double lastLat = 0, lastLon = 0;
    private long lastTickTimestamp;
    private long lastPersistTimestamp;
    // Last moment the truck answered (or the service started); ends a trip once the link stays down.
    private long lastConnectedTimestamp;
    private String lastConnectError;
    private boolean gpsOn = false;
    // GPS miles since the last OBD tick; billed only when a gap in OBD data hides the driving.
    private final DoubleAdder gpsGapMiles = new DoubleAdder();
    private volatile long lastGpsMovingTimestamp = 0;
    private long engineOffSince = 0;
    private int connectFailures = 0;
    private double distanceSincePoint = 0;
    private double lastInstantMpg = 0;
    // Driving not yet written to the profile row; flushed as deltas once a second.
    private double pendingMiles, pendingHours, pendingGallons, pendingOilPercent;
    // Shown on the dashboard after the service stops, instead of the plain "off" status.
    private volatile String stopReason;

    private final Runnable pollTask = this::pollTick;
    // True while the poll loop is parked on the idle alarm; whoever flips it back owns restarting the loop.
    private final AtomicBoolean idleWaiting = new AtomicBoolean(false);
    private PendingIntent idleCheckIntent;

    // Screen unlocked or power plugged in (truck-powered chargers switch on at key-on): knock now.
    private final BroadcastReceiver wakeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            wakeFromIdle();
        }
    };

    /**
     * Starts tracking unless the user turned it off (auto-tracking off or typed STOP).
     * Used by app launch, boot, the Bluetooth receiver and the watchdog.
     */
    public static void startIfEnabled(Context context, String why) {
        if (isRunning) return;
        if (!AppSettings.isAutoTrackingEnabled(context) || AppSettings.isTrackingPaused(context)) return;
        if (!AppSettings.hasObdAdapterConfigured(context)) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            ContextCompat.startForegroundService(context, new Intent(context, TrackingService.class));
            AppLog.i(context, TAG, "Tracking started (" + why + ")");
        } catch (RuntimeException e) {
            // Android 12+ blocks background starts unless the app is exempt from battery optimization.
            AppLog.e(context, TAG, "Android blocked tracking start (" + why
                    + "); allow background start in the Account tab", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        db = AppDatabase.getInstance(this);
        gpsTracker = new GpsTracker(this);
        NotificationUtils.ensureChannels(this);

        // Without this, the tablet suspends its CPU when the screen turns off and the
        // whole poll loop just stops — the service stays "running" but does nothing.
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoSentry:tracking");
            wakeLock.setReferenceCounted(false);
        }

        idleCheckIntent = PendingIntent.getService(this, 0,
                new Intent(this, TrackingService.class).setAction(ACTION_IDLE_CHECK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        IntentFilter wakeEvents = new IntentFilter(Intent.ACTION_USER_PRESENT);
        wakeEvents.addAction(Intent.ACTION_SCREEN_ON);
        wakeEvents.addAction(Intent.ACTION_POWER_CONNECTED);
        ContextCompat.registerReceiver(this, wakeReceiver, wakeEvents, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean runTest = intent != null && ACTION_RUN_TEST.equals(intent.getAction());
        boolean runKoeo = intent != null && ACTION_RUN_KOEO.equals(intent.getAction());
        if (started) {
            // Already running; a second start must not spawn a second poll loop.
            if (runTest) queueConnectionTest();
            if (runKoeo) queueKoeoCheck();
            if (runTest || runKoeo || (intent != null && ACTION_IDLE_CHECK.equals(intent.getAction()))) wakeFromIdle();
            return START_STICKY;
        }
        started = true;

        // The adapter comes from saved settings, not the intent, so a system
        // restart (which delivers a null intent) still talks to the real adapter.
        String adapterAddress = AppSettings.getObdAdapterAddress(this);

        try {
            startInForeground();
        } catch (RuntimeException e) {
            // Android refused the foreground service (missing permission or background start).
            AppLog.e(this, TAG, "Could not start foreground service", e);
            stopReason = "Couldn't start tracking: " + e.getMessage();
            if (runTest || runKoeo) endTestEarly("✗ " + stopReason);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (adapterAddress == null || adapterAddress.isEmpty()) {
            stopReason = "No OBD adapter paired — tap Pair OBD Adapter in Account";
            if (runTest || runKoeo) endTestEarly("✗ No OBD adapter paired — tap Pair OBD Adapter first.");
            stopSelf();
            return START_NOT_STICKY;
        }
        realAdapter = new ELM327Adapter(adapterAddress);
        isRunning = true;
        LiveReadings.status = "Connecting to OBD adapter…";

        ioExecutor.execute(this::initState);
        if (runTest) queueConnectionTest();
        if (runKoeo) queueKoeoCheck();
        return START_STICKY;
    }

    /**
     * Android 14+ checks each declared foreground type: location needs a location
     * grant and is refused when started from the background (the Bluetooth
     * receiver), connectedDevice needs BLUETOOTH_CONNECT. Ask only for what is
     * granted, and fall back to OBD-only tracking when location is refused.
     */
    private void startInForeground() {
        int connected = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
        int location = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        boolean btGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
        int types = (btGranted ? connected : 0) | (gpsTracker.hasPermission() ? location : 0);
        if (types == 0) types = connected; // fails below with Android's own permission message
        android.app.Notification notification = buildNotification("Starting...");
        try {
            ServiceCompat.startForeground(this, NotificationUtils.TRACKING_NOTIFICATION_ID, notification, types);
        } catch (RuntimeException e) {
            if (types == (connected | location)) {
                AppLog.i(this, TAG, "Location foreground refused, tracking without GPS: " + e.getMessage());
                ServiceCompat.startForeground(this, NotificationUtils.TRACKING_NOTIFICATION_ID, notification, connected);
            } else {
                throw e;
            }
        }
    }

    private void initState() {
        profile = db.vehicleProfileDao().getSync();
        if (profile == null) {
            profile = VehicleProfile.newDefault();
            db.vehicleProfileDao().insert(profile);
        }

        // A session still open from a crash or kill is a finished trip, not this one.
        Session stale = db.sessionDao().getActive();
        if (stale != null) {
            TripPoint last = db.tripPointDao().getLastForSession(stale.id);
            stale.endTimestamp = last != null ? last.timestamp : System.currentTimeMillis();
            db.sessionDao().update(stale);
        }

        long now = System.currentTimeMillis();
        lastTickTimestamp = now;
        lastPersistTimestamp = now;
        lastConnectedTimestamp = now;

        mainHandler.post(pollTask);
    }

    /** Main thread only. GPS runs from truck contact until the trip ends, not all night in the driveway. */
    private void startGps() {
        if (gpsOn) return;
        gpsOn = true;
        gpsGapMiles.reset();
        try {
            gpsTracker.start((lat, lon, speedMph, distanceDeltaMiles) -> {
                lastLat = lat;
                lastLon = lon;
                lastSpeedMph = speedMph;
                if (speedMph >= GPS_MOVING_MPH) {
                    lastGpsMovingTimestamp = System.currentTimeMillis();
                    gpsGapMiles.add(distanceDeltaMiles);
                }
            });
        } catch (SecurityException e) {
            // No location while in the background; speed comes from the truck instead.
        }
    }

    private void stopGps() {
        if (!gpsOn) return;
        gpsOn = false;
        gpsTracker.stop();
        lastSpeedMph = 0;
    }

    private void pollTick() {
        if (stopped) return;
        if (wakeLock != null) wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
        try {
            ioExecutor.execute(() -> {
                long delay = POLL_INTERVAL_MS;
                boolean truckOff;
                try {
                    if (stopped) return;
                    if (!ensureConnected() || !ensureTruckAnswering()) {
                        delay = RETRY_INTERVAL_MS;
                        truckOff = activeSession == null; // mid-trip: keep retrying at full speed
                    } else {
                        pollOnce();
                        truckOff = parkedLongEnough(System.currentTimeMillis());
                    }
                } catch (Exception e) {
                    AppLog.e(this, TAG, "Poll failed, will reconnect", e);
                    handleLinkLoss();
                    delay = RETRY_INTERVAL_MS;
                    truckOff = activeSession == null;
                }
                if (stopped) return;
                if (truckOff) {
                    waitForKeyOn();
                } else {
                    mainHandler.postDelayed(pollTask, delay);
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Service is shutting down.
        }
    }

    /** Connected but no trip and the engine has been off as long as it takes to close one. */
    private boolean parkedLongEnough(long now) {
        return activeSession == null && engineOffSince != 0
                && now - engineOffSince > END_TRIP_AFTER_ENGINE_OFF_MS;
    }

    /**
     * Low-power mode until the next knock: drop the adapter link so it can sleep, stop GPS,
     * release the wake lock, and let an exact alarm wake the tablet for the next check.
     */
    private void waitForKeyOn() {
        finishKoeo(); // key cycle over: keep whatever the crank showed (or that it never cranked)
        if (realAdapter.isConnected()) {
            realAdapter.disconnect();
            supportedPids = Collections.emptySet();
        }
        engineOffSince = 0;
        LiveReadings.clearValues();
        LiveReadings.status = "Truck off — waiting for key-on";
        updateNotification("Waiting for key-on");
        mainHandler.post(this::stopGps);

        AlarmManager alarms = getSystemService(AlarmManager.class);
        boolean exactAllowed = alarms != null
                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms());
        if (!exactAllowed) {
            // Without exact alarms nothing wakes a sleeping tablet on time, so stay awake
            // and knock on a timer rather than miss key-on.
            mainHandler.postDelayed(pollTask, IDLE_CHECK_INTERVAL_MS);
            return;
        }
        idleWaiting.set(true);
        try {
            alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + IDLE_CHECK_INTERVAL_MS, idleCheckIntent);
        } catch (SecurityException e) {
            // Permission pulled since the check; stay awake instead (unless a wake event already took over).
            if (idleWaiting.compareAndSet(true, false)) mainHandler.postDelayed(pollTask, IDLE_CHECK_INTERVAL_MS);
            return;
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    /** Ends the low-power wait early (alarm fired, screen on, power in, test asked) and polls now. */
    private void wakeFromIdle() {
        if (!idleWaiting.compareAndSet(true, false)) return; // loop already running
        if (wakeLock != null) wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
        AlarmManager alarms = getSystemService(AlarmManager.class);
        if (alarms != null) alarms.cancel(idleCheckIntent);
        mainHandler.post(pollTask);
    }

    private boolean ensureConnected() {
        if (realAdapter.isConnected()) return true;

        LiveReadings.clearValues();
        LiveReadings.status = "Connecting to OBD adapter…";
        supportedPids = Collections.emptySet();
        try {
            realAdapter.connect();
            AppLog.i(this, TAG, "OBD adapter connected"
                    + (connectFailures > 0 ? " after " + connectFailures + " attempts" : ""));
            connectFailures = 0;
            lastConnectError = null;
            return true;
        } catch (IOException | InterruptedException | RuntimeException e) {
            realAdapter.disconnect();
            connectFailures++;
            lastConnectError = e.getMessage();
            // Normal while the truck is off (adapter asleep), so log the outage once, not every retry.
            if (connectFailures == 1) {
                AppLog.e(this, TAG, "OBD adapter not reachable, retrying until it is", e);
            }
            LiveReadings.status = "Waiting for OBD adapter (truck off?) — retrying";
            updateNotification("Waiting for the truck — connects automatically at key-on");
            endTripIfOffline(System.currentTimeMillis());
            return false;
        }
    }

    /**
     * Truck off means the adapter sleeps and the link drops; close the trip once that has
     * lasted as long as an engine-off would, unless GPS shows we are still driving.
     */
    private void endTripIfOffline(long now) {
        if (now - lastConnectedTimestamp <= END_TRIP_AFTER_ENGINE_OFF_MS) return;
        if (now - lastGpsMovingTimestamp <= END_TRIP_AFTER_ENGINE_OFF_MS) return;
        if (activeSession != null) {
            endSession(now);
        } else {
            mainHandler.post(this::stopGps); // key-on without a drive still started GPS
        }
    }

    /** Adapter is linked, but the truck's computer may not be awake yet (key off). */
    private boolean ensureTruckAnswering() throws IOException {
        if (!supportedPids.isEmpty()) return true;

        Set<Integer> found = realAdapter.readSupportedPids();
        if (found.isEmpty()) {
            LiveReadings.status = "Adapter connected — truck not responding (turn key on)";
            LiveReadings.clearValues();
            updateNotification("Adapter connected, truck not responding");
            endTripIfOffline(System.currentTimeMillis());
            return false;
        }
        supportedPids = found;
        lastConnectedTimestamp = System.currentTimeMillis();
        mainHandler.post(this::startGps);
        String protocol = realAdapter.describeProtocol();
        String upper = protocol.toUpperCase(java.util.Locale.US);
        pwmBus = upper.contains("PWM");
        canBus = upper.contains("CAN") || upper.contains("15765");
        if (koeo == null) koeoDue = true; // fresh truck contact: snapshot it if the engine isn't running yet
        probeFordOilTemp();
        AppLog.i(this, TAG, "Truck answered on " + protocol
                + "; supported PIDs: " + describePids(found)
                + "; Ford oil temp (22 1310): " + describeOilTempSource());
        return true;
    }

    /**
     * Standard Mode 01 oil temp when the truck has it; otherwise Ford's enhanced PID,
     * which only exists on the J1850 PWM bus. Also offers "Engine Oil Temp" in the
     * dashboard editor when either source works.
     */
    private void probeFordOilTemp() throws IOException {
        lastOilProbe = System.currentTimeMillis();
        fordOilTemp = pwmBus && !supportedPids.contains(PidCatalog.ENGINE_OIL_TEMP)
                && !Double.isNaN(realAdapter.readFordEngineOilTempC());
        Set<Integer> offered = new LinkedHashSet<>(supportedPids);
        if (fordOilTemp) offered.add(PidCatalog.ENGINE_OIL_TEMP);
        LiveReadings.supported = offered;
        AppSettings.setSupportedPids(this, offered);
    }

    private String describeOilTempSource() {
        if (supportedPids.contains(PidCatalog.ENGINE_OIL_TEMP)) return "not needed, standard PID 5C answers";
        if (!pwmBus) return "not tried, bus is not J1850 PWM";
        if (fordOilTemp) return "yes";
        return "no answer, reply '" + realAdapter.lastEnhancedReply() + "' (retrying every 30 s)";
    }

    private static void endTestEarly(String report) {
        LiveReadings.testReport = report;
        LiveReadings.testRunning = false;
    }

    private void queueConnectionTest() {
        LiveReadings.testRunning = true;
        LiveReadings.testReport = "Starting test…";
        try {
            ioExecutor.execute(this::runConnectionTest);
        } catch (RejectedExecutionException e) {
            endTestEarly("✗ Tracking is shutting down — try again.");
        }
    }

    /**
     * Walks the whole chain the trip depends on — phone settings, Bluetooth link,
     * adapter, truck computer, live readings — and says which link is broken.
     * Runs on the poll thread, so it never fights the poll loop for the adapter.
     */
    private void runConnectionTest() {
        StringBuilder r = new StringBuilder();
        boolean ok = true;
        try {
            PowerManager pm = getSystemService(PowerManager.class);
            boolean exempt = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
            ok &= testLine(r, exempt, "Background start allowed",
                    "Background start NOT allowed — Android can block key-on auto-start. Fix: Account > Allow background start");
            AlarmManager alarms = getSystemService(AlarmManager.class);
            testLine(r, Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                            || (alarms != null && alarms.canScheduleExactAlarms()),
                    "Alarms allowed (low-power waiting while the truck is off)",
                    "Alarms not allowed — the tablet stays awake while the truck is off. Fix: Account > Allow low-power waiting");
            testLine(r, gpsTracker.hasPermission(), "Location allowed (GPS fills OBD gaps)",
                    "Location not allowed — distance relies on the truck's speed only");

            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            boolean btOn = bt != null && bt.isEnabled();
            ok &= testLine(r, btOn, "Bluetooth on", "Bluetooth is off — turn it on");
            if (!btOn) return;
            String name = AppSettings.getObdAdapterName(this);
            r.append("• Adapter: ").append(name != null ? name : "?")
                    .append(" (").append(AppSettings.getObdAdapterAddress(this)).append(")\n");
            publishTest(r, true);

            long t0 = System.currentTimeMillis();
            boolean wasConnected = realAdapter.isConnected();
            boolean linked = ensureConnected();
            ok &= testLine(r, linked,
                    wasConnected ? "Bluetooth link up" : "Bluetooth link up (connected in "
                            + (System.currentTimeMillis() - t0) / 100 / 10.0 + " s)",
                    "Can't reach the adapter: " + lastConnectError
                            + ". Is it plugged in and awake? Most adapters sleep until the key is turned on.");
            if (!linked) return;

            r.append("• Adapter ID: ").append(realAdapter.identify()).append('\n');
            r.append("• Voltage at OBD port: ").append(realAdapter.readVoltage())
                    .append(" (about 12.6 V key off, 13.5–14.5 V engine running)\n");
            publishTest(r, true);

            boolean truck = ensureTruckAnswering();
            ok &= testLine(r, truck, "Truck computer answering on " + realAdapter.describeProtocol()
                            + " — " + supportedPids.size() + " standard readings available",
                    "Truck computer not answering — turn the key to ON and run the test again");
            if (!truck) return;

            for (int id : new int[]{PidCatalog.RPM, PidCatalog.SPEED, PidCatalog.ENGINE_OIL_TEMP}) {
                PidCatalog.Pid def = PidCatalog.get(id);
                double value = Double.NaN;
                if (id == PidCatalog.ENGINE_OIL_TEMP && fordOilTemp) {
                    double c = realAdapter.readFordEngineOilTempC();
                    if (!Double.isNaN(c)) value = c * 9.0 / 5.0 + 32.0;
                } else if (supportedPids.contains(id)) {
                    value = def.decode(realAdapter.readPid(id));
                }
                r.append("• ").append(def.label()).append(": ")
                        .append(Double.isNaN(value) ? "no answer" : def.formatValue(value)).append('\n');
                publishTest(r, true);
            }
        } catch (IOException | RuntimeException e) {
            ok = false;
            r.append("✗ Adapter stopped answering mid-test: ").append(e.getMessage()).append('\n');
            handleLinkLoss();
        } finally {
            r.append(ok ? "\nPASS — the truck will be tracked from key-on."
                    : "\nFix the ✗ items above, then run the test again.");
            publishTest(r, false);
            AppLog.i(this, TAG, "Connection test:\n" + r);
        }
    }

    private static boolean testLine(StringBuilder r, boolean pass, String passText, String failText) {
        r.append(pass ? "✓ " : "✗ ").append(pass ? passText : failText).append('\n');
        LiveReadings.testReport = r.toString();
        return pass;
    }

    private static void publishTest(StringBuilder r, boolean running) {
        LiveReadings.testReport = r.toString();
        LiveReadings.testRunning = running;
    }

    /**
     * KOEO: the first look at the truck in a key cycle, before the engine turns, gets a
     * snapshot. After that, RPM between 0 and STARTED_RPM is cranking: time it and track
     * the lowest battery voltage until the engine starts or the crank gives up.
     */
    private void trackKeyOnAndCrank(long now, Double rpm, boolean engineRunning) throws IOException {
        if (koeoDue) {
            koeoDue = false;
            if (!engineRunning) captureKoeo(now); // already running = joined mid-drive, nothing to capture
        }
        if (koeo == null || koeo.started || rpm == null) return; // no RPM answer this tick: no information
        if (rpm > 0 && rpm < STARTED_RPM) {
            if (crankStart == 0) {
                crankStart = now;
                koeo.crankAttempts++;
            }
            double v = realAdapter.readVolts();
            if (v > 0 && (koeo.minCrankVolts == 0 || v < koeo.minCrankVolts)) koeo.minCrankVolts = v;
        } else if (rpm >= STARTED_RPM) {
            koeo.started = true;
            if (crankStart != 0) {
                koeo.crankSeconds = (now - crankStart) / 1000.0;
            } else if (koeo.crankAttempts == 0) {
                koeo.crankAttempts = 1; // cranked and caught between two readings
            }
            finishKoeo();
        } else if (crankStart != 0) {
            koeo.crankSeconds = (now - crankStart) / 1000.0; // RPM fell back to 0: that try didn't start
            crankStart = 0;
            db.koeoReportDao().update(koeo);
        }
    }

    private void captureKoeo(long now) throws IOException {
        finishKoeo();
        KoeoReport r = new KoeoReport();
        r.timestamp = now;
        r.keyOnVolts = realAdapter.readVolts();
        StringBuilder text = new StringBuilder();
        for (PidCatalog.Pid def : PidCatalog.all()) {
            if (def.computed || !supportedPids.contains(def.id)) continue;
            double value = def.decode(realAdapter.readPid(def.id));
            if (!Double.isNaN(value)) text.append(def.label()).append(": ").append(def.formatValue(value)).append('\n');
        }
        if (fordOilTemp) {
            double c = realAdapter.readFordEngineOilTempC();
            PidCatalog.Pid oil = PidCatalog.get(PidCatalog.ENGINE_OIL_TEMP);
            if (!Double.isNaN(c)) text.append(oil.label()).append(": ").append(oil.formatValue(c * 9.0 / 5.0 + 32.0)).append('\n');
        }
        r.readings = text.toString().trim();
        r.storedCodes = readCodes(DTCReader.Mode.STORED_03);
        r.pendingCodes = readCodes(DTCReader.Mode.PENDING_07);
        r.id = db.koeoReportDao().insert(r);
        koeo = r;
        crankStart = 0;
        AppLog.i(this, TAG, "Key-on check:\n" + r.summary());
    }

    /** Comma-separated codes, "" for none, null when the request itself got no reply. */
    private String readCodes(DTCReader.Mode mode) throws IOException {
        String raw = realAdapter.readTroubleCodesRaw(mode);
        if (raw.trim().isEmpty() || !raw.contains(">")) return null; // timed out
        return android.text.TextUtils.join(",", DTCReader.parse(raw, mode, canBus));
    }

    /** Saves the open KOEO report as it stands and closes it. */
    private void finishKoeo() {
        if (koeo == null) return;
        db.koeoReportDao().update(koeo);
        String summary = koeo.summary();
        AppLog.i(this, TAG, "Key cycle result: " + summary.substring(summary.lastIndexOf("Crank:")));
        koeo = null;
        crankStart = 0;
    }

    private void queueKoeoCheck() {
        LiveReadings.testRunning = true;
        LiveReadings.testReport = "Checking…";
        try {
            ioExecutor.execute(this::runKoeoCheck);
        } catch (RejectedExecutionException e) {
            endTestEarly("✗ Tracking is shutting down — try again.");
        }
    }

    /** On-demand KOEO snapshot from the Diagnostics tab. Needs key ON, engine off. */
    private void runKoeoCheck() {
        StringBuilder r = new StringBuilder();
        try {
            if (!ensureConnected()) {
                r.append("✗ Can't reach the adapter (").append(lastConnectError)
                        .append("). Turn the key to ON and try again.");
                return;
            }
            if (!ensureTruckAnswering()) {
                r.append("✗ Truck computer not answering. Turn the key to ON (engine off) and try again.");
                return;
            }
            PidCatalog.Pid rpmDef = PidCatalog.get(PidCatalog.RPM);
            double rpm = supportedPids.contains(PidCatalog.RPM)
                    ? rpmDef.decode(realAdapter.readPid(PidCatalog.RPM)) : Double.NaN;
            if (rpm > 0) {
                r.append("✗ Engine is running. Shut it off, leave the key ON, and run the check again.");
                return;
            }
            koeoDue = false;
            captureKoeo(System.currentTimeMillis());
            r.append(koeo.summary()).append("\n\nCrank whenever you're ready — the start is timed and saved.");
        } catch (IOException | RuntimeException e) {
            r.append("✗ Adapter stopped answering: ").append(e.getMessage());
            handleLinkLoss();
        } finally {
            publishTest(r, false);
        }
    }

    private void handleLinkLoss() {
        if (realAdapter != null) realAdapter.disconnect();
        supportedPids = Collections.emptySet();
        LiveReadings.clearValues();
        LiveReadings.status = "Lost connection to OBD adapter — retrying";
    }

    private void pollOnce() throws IOException {
        Set<Integer> want = new LinkedHashSet<>(AppSettings.getDashboardPids(this));
        want.add(PidCatalog.RPM);
        want.add(PidCatalog.SPEED);
        want.add(PidCatalog.MAF);
        want.add(PidCatalog.FUEL_RATE);
        want.add(PidCatalog.ENGINE_OIL_TEMP);

        for (int id : want) {
            PidCatalog.Pid def = PidCatalog.get(id);
            if (def == null || def.computed) continue;
            if (!supportedPids.contains(id)) continue;
            int[] data = realAdapter.readPid(id);
            double value = def.decode(data);
            if (Double.isNaN(value)) {
                LiveReadings.values.remove(id);
            } else {
                LiveReadings.values.put(id, value);
            }
        }
        long now = System.currentTimeMillis();
        if (!fordOilTemp && pwmBus && !supportedPids.contains(PidCatalog.ENGINE_OIL_TEMP)
                && now - lastOilProbe >= OIL_PROBE_RETRY_MS) {
            probeFordOilTemp();
            if (fordOilTemp) AppLog.i(this, TAG, "Ford oil temp (22 1310) is answering now");
        }
        if (fordOilTemp && now - lastOilRead >= OIL_READ_INTERVAL_MS) {
            lastOilRead = now;
            double oilC = realAdapter.readFordEngineOilTempC();
            if (Double.isNaN(oilC)) {
                LiveReadings.values.remove(PidCatalog.ENGINE_OIL_TEMP);
            } else {
                LiveReadings.values.put(PidCatalog.ENGINE_OIL_TEMP, oilC * 9.0 / 5.0 + 32.0);
            }
        }

        lastConnectedTimestamp = now;
        Double rpm = LiveReadings.values.get(PidCatalog.RPM);
        Double obdSpeed = LiveReadings.values.get(PidCatalog.SPEED);
        // One dropped RPM answer must not pause the trip while the truck is plainly moving.
        boolean engineRunning = (rpm != null && rpm > 0) || (obdSpeed != null && obdSpeed > 0);
        trackKeyOnAndCrank(now, rpm, engineRunning);

        if (!engineRunning) {
            LiveReadings.engineRunning = false;
            LiveReadings.status = "Truck connected — engine off";
            lastTickTimestamp = now;
            gpsGapMiles.reset();
            if (engineOffSince == 0) engineOffSince = now;
            if (activeSession != null && now - engineOffSince > END_TRIP_AFTER_ENGINE_OFF_MS) {
                endSession(now);
            }
            updateNotification("Connected — engine off");
            return;
        }
        engineOffSince = 0;
        LiveReadings.engineRunning = true;
        LiveReadings.status = "Engine running";

        double rawDtSeconds = (now - lastTickTimestamp) / 1000.0;
        lastTickTimestamp = now;
        boolean gap = rawDtSeconds > MAX_TICK_SECONDS;
        double dtSeconds = (rawDtSeconds <= 0 || gap) ? 0 : rawDtSeconds;
        double gapGpsMiles = gpsGapMiles.sumThenReset();

        // Distance = speed x engine-on time, the same integration MPG uses.
        double speedMph = obdSpeed != null ? obdSpeed : lastSpeedMph;
        double distanceDeltaMiles = speedMph * (dtSeconds / 3600.0);
        double engineHoursDelta = dtSeconds / 3600.0;
        if (gap && gapGpsMiles > 0) {
            // The OBD link dropped mid-drive (reconnect, stall); GPS saw the miles driven meanwhile.
            distanceDeltaMiles = gapGpsMiles;
            engineHoursDelta = rawDtSeconds / 3600.0;
            AppLog.i(this, TAG, String.format(java.util.Locale.US,
                    "Filled a %.0f s OBD gap with %.2f GPS miles", rawDtSeconds, gapGpsMiles));
        }

        Double maf = LiveReadings.values.get(PidCatalog.MAF);
        Double fuelRate = LiveReadings.values.get(PidCatalog.FUEL_RATE);
        boolean fuelKnown = maf != null || fuelRate != null;
        double gallonsPerHour = maf != null
                ? MpgCalculator.fuelGallonsPerHour(maf.floatValue())
                : (fuelRate != null ? fuelRate : 0);
        double gallonsDelta = gallonsPerHour * (dtSeconds / 3600.0);
        lastInstantMpg = (gallonsPerHour > 0.01 && speedMph > 0.5) ? speedMph / gallonsPerHour : 0;

        // Oil life runs on engine oil temp, the 7.3L's real thermal signal; no reading adds no heat penalty.
        Double oilTempF = LiveReadings.values.get(PidCatalog.ENGINE_OIL_TEMP);

        if (activeSession == null) startSession(now);

        pendingMiles += distanceDeltaMiles;
        pendingHours += engineHoursDelta;
        pendingGallons += gallonsDelta;
        pendingOilPercent += OilLifeEngine.percentUsed(distanceDeltaMiles, profile.severeDuty,
                rpm != null ? rpm : 0, oilTempF != null ? oilTempF : 0);

        activeSession.distanceMiles += distanceDeltaMiles;
        activeSession.fuelGallonsUsed += gallonsDelta;
        activeSession.maxSpeedMph = Math.max(activeSession.maxSpeedMph, speedMph);
        if (activeSession.fuelGallonsUsed > 0) {
            activeSession.avgMpg = activeSession.distanceMiles / activeSession.fuelGallonsUsed;
        }
        distanceSincePoint += distanceDeltaMiles;

        // Computed dashboard tiles
        if (fuelKnown) {
            LiveReadings.values.put(PidCatalog.COMPUTED_INSTANT_MPG, lastInstantMpg);
        } else {
            LiveReadings.values.remove(PidCatalog.COMPUTED_INSTANT_MPG);
        }
        if (activeSession.avgMpg > 0) {
            LiveReadings.values.put(PidCatalog.COMPUTED_TRIP_MPG, activeSession.avgMpg);
        } else {
            LiveReadings.values.remove(PidCatalog.COMPUTED_TRIP_MPG);
        }
        LiveReadings.values.put(PidCatalog.COMPUTED_TRIP_MILES, activeSession.distanceMiles);
        LiveReadings.values.put(PidCatalog.COMPUTED_TRIP_TIME, (now - activeSession.startTimestamp) / 1000.0);
        LiveReadings.values.put(PidCatalog.COMPUTED_ODOMETER, profile.odometerMiles + pendingMiles);

        if (now - lastPersistTimestamp >= PERSIST_INTERVAL_MS) {
            lastPersistTimestamp = now;
            persist(now, speedMph, rpm != null ? rpm : 0, maf != null ? maf : 0);
        }
    }

    /** Writes pending driving as deltas, then reloads the row to pick up UI edits (odometer, oil change). */
    private void flushProfile() {
        if (pendingMiles > 0 || pendingHours > 0 || pendingGallons > 0 || pendingOilPercent > 0) {
            db.vehicleProfileDao().applyDrive(pendingMiles, pendingHours, pendingGallons, pendingOilPercent);
            pendingMiles = pendingHours = pendingGallons = pendingOilPercent = 0;
        }
        VehicleProfile fresh = db.vehicleProfileDao().getSync();
        if (fresh != null) profile = fresh;
    }

    private void persist(long now, double speedMph, double rpm, double maf) {
        flushProfile();
        db.sessionDao().update(activeSession);

        TripPoint point = new TripPoint();
        point.sessionId = activeSession.id;
        point.timestamp = now;
        point.latitude = lastLat;
        point.longitude = lastLon;
        point.speedMph = speedMph;
        point.distanceDeltaMiles = distanceSincePoint;
        point.rpm = (int) Math.round(rpm);
        point.mafGramsPerSec = (float) maf;
        point.instantMpg = lastInstantMpg;
        db.tripPointDao().insert(point);
        distanceSincePoint = 0;

        checkServiceThresholds();

        updateNotification(String.format(java.util.Locale.US,
                "%.0f mph | %.1f MPG | Oil life %.0f%%",
                speedMph, lastInstantMpg, profile.oilLifePercent));
    }

    private void startSession(long now) {
        activeSession = new Session();
        activeSession.startTimestamp = now;
        activeSession.startOdometerMiles = profile.odometerMiles + pendingMiles;
        activeSession.id = db.sessionDao().insert(activeSession);
        distanceSincePoint = 0;
        mainHandler.post(this::startGps);
    }

    private void endSession(long now) {
        activeSession.endTimestamp = now;
        db.sessionDao().update(activeSession);
        flushProfile();
        activeSession = null;
        mainHandler.post(this::stopGps);
    }

    private void checkServiceThresholds() {
        List<ServiceStatus> dueSoon = MaintenanceScheduleEngine.dueSoonOrOverdue(profile, db.maintenanceDao());
        Set<ServiceItemType> stillDue = EnumSet.noneOf(ServiceItemType.class);
        for (ServiceStatus status : dueSoon) {
            stillDue.add(status.type);
            if (!notifiedDueSoon.contains(status.type)) {
                notifiedDueSoon.add(status.type);
                postServiceAlert(status);
            }
        }
        notifiedDueSoon.retainAll(stillDue);
    }

    private void postServiceAlert(ServiceStatus status) {
        String text = status.overdue
                ? String.format(java.util.Locale.US, "%s is overdue (%.0f%% of service life used)", status.displayName, status.percentOfLifeUsed)
                : String.format(java.util.Locale.US, "%s at %.0f%% of service life — %.0f mi remaining", status.displayName, status.percentOfLifeUsed, status.milesRemaining());

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NotificationUtils.CHANNEL_ALERTS)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(status.overdue ? "Maintenance overdue" : "Maintenance due soon")
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);

        android.app.NotificationManager manager =
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(2000 + status.type.ordinal(), builder.build());
        }
    }

    private static String describePids(Set<Integer> pids) {
        StringBuilder sb = new StringBuilder();
        for (int pid : new java.util.TreeSet<>(pids)) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format("%02X", pid));
        }
        return sb.toString();
    }

    private android.app.Notification buildNotification(String text) {
        NotificationCompat.Builder builder = NotificationUtils.trackingNotificationBuilder(this, activeSession != null)
                .setContentText(text);
        return builder.build();
    }

    private void updateNotification(String text) {
        android.app.NotificationManager manager =
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NotificationUtils.TRACKING_NOTIFICATION_ID, buildNotification(text));
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopped = true;
        isRunning = false;
        mainHandler.removeCallbacks(pollTask);
        unregisterReceiver(wakeReceiver);
        AlarmManager alarms = getSystemService(AlarmManager.class);
        if (alarms != null) alarms.cancel(idleCheckIntent);
        gpsTracker.stop();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        LiveReadings.clearValues();
        LiveReadings.supported = Collections.emptySet();
        LiveReadings.status = stopReason != null ? stopReason : LiveReadings.IDLE_STATUS;
        try {
            ioExecutor.execute(() -> {
                if (realAdapter != null) realAdapter.disconnect();
                if (activeSession != null) {
                    activeSession.endTimestamp = System.currentTimeMillis();
                    db.sessionDao().update(activeSession);
                }
                finishKoeo();
                if (profile != null) flushProfile();
            });
        } catch (RejectedExecutionException ignored) {
        }
        ioExecutor.shutdown();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
