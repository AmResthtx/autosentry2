package com.autosentry.app.service;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.autosentry.app.data.AppDatabase;
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
import com.autosentry.app.obd.ELM327Adapter;
import com.autosentry.app.obd.LiveReadings;
import com.autosentry.app.obd.OBDSimulator;
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
 * from GPS otherwise. Uses OBDSimulator only when no adapter has been paired
 * at all — a real adapter that fails to connect is retried, never faked.
 */
public class TrackingService extends Service {
    private static final String TAG = "TrackingService";

    /** True from start until destroy; the dashboard uses it for the Start/Stop button. */
    public static volatile boolean isRunning = false;

    private static final long POLL_INTERVAL_MS = 250L;
    private static final long RETRY_INTERVAL_MS = 5000L;
    private static final long PERSIST_INTERVAL_MS = 1000L;
    private static final long END_TRIP_AFTER_ENGINE_OFF_MS = 120_000L;
    private static final long GIVE_UP_WITHOUT_ADAPTER_MS = 10 * 60_000L;
    // A longer gap than this (reconnect, stall) is not driving time; don't bill it to the trip.
    private static final double MAX_TICK_SECONDS = 5.0;
    // Renewed on every poll tick so it never outlives the service, but survives any one gap.
    private static final long WAKE_LOCK_TIMEOUT_MS = 60_000L;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;

    private AppDatabase db;
    private GpsTracker gpsTracker;
    private ELM327Adapter realAdapter;
    private OBDSimulator simulator;
    private boolean useRealAdapter;
    private boolean started = false;
    private volatile boolean stopped = false;

    private Session activeSession;
    private VehicleProfile profile;
    private Set<Integer> supportedPids = Collections.emptySet();
    // Avoids re-notifying every tick once an item crosses 80%; cleared
    // when the item is serviced (odometerAtEvent moves the baseline back).
    private final Set<ServiceItemType> notifiedDueSoon = EnumSet.noneOf(ServiceItemType.class);

    private volatile double lastSpeedMph = 0;
    private volatile double lastLat = 0, lastLon = 0;
    private long lastTickTimestamp;
    private long lastPersistTimestamp;
    private long lastConnectedTimestamp;
    private long engineOffSince = 0;
    private int connectFailures = 0;
    private double distanceSincePoint = 0;
    private double lastInstantMpg = 0;

    private final Runnable pollTask = this::pollTick;

    @Override
    public void onCreate() {
        super.onCreate();
        db = AppDatabase.getInstance(this);
        gpsTracker = new GpsTracker(this);
        simulator = new OBDSimulator();
        NotificationUtils.ensureChannels(this);

        // Without this, the tablet suspends its CPU when the screen turns off and the
        // whole poll loop just stops — the service stays "running" but does nothing.
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoSentry:tracking");
            wakeLock.setReferenceCounted(false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (started) return START_STICKY; // already running; a second start must not spawn a second poll loop
        started = true;

        // The adapter comes from saved settings, not the intent, so a system
        // restart (which delivers a null intent) still talks to the real adapter.
        String adapterAddress = AppSettings.getObdAdapterAddress(this);
        useRealAdapter = adapterAddress != null && !adapterAddress.isEmpty();
        if (useRealAdapter) {
            realAdapter = new ELM327Adapter(adapterAddress);
        }

        try {
            startForeground(NotificationUtils.TRACKING_NOTIFICATION_ID, buildNotification("Starting..."));
        } catch (RuntimeException e) {
            // Android refused to let a background app start a foreground service.
            AppLog.e(this, TAG, "Could not start foreground service", e);
            stopSelf();
            return START_NOT_STICKY;
        }
        isRunning = true;
        LiveReadings.status = useRealAdapter ? "Connecting to OBD adapter…" : "Simulator (no adapter paired)";

        ioExecutor.execute(this::initState);
        return START_STICKY;
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

        mainHandler.post(() -> {
            try {
                gpsTracker.start((lat, lon, speedMph, distanceDeltaMiles) -> {
                    lastLat = lat;
                    lastLon = lon;
                    lastSpeedMph = speedMph;
                });
            } catch (SecurityException e) {
                // No location while in the background; speed comes from the truck instead.
            }
            mainHandler.post(pollTask);
        });
    }

    private void pollTick() {
        if (stopped) return;
        if (wakeLock != null) wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
        try {
            ioExecutor.execute(() -> {
                long delay = POLL_INTERVAL_MS;
                try {
                    if (stopped) return;
                    if (!ensureConnected() || !ensureTruckAnswering()) {
                        delay = RETRY_INTERVAL_MS;
                    } else {
                        pollOnce();
                    }
                } catch (Exception e) {
                    AppLog.e(this, TAG, "Poll failed, will reconnect", e);
                    handleLinkLoss();
                    delay = RETRY_INTERVAL_MS;
                }
                if (!stopped) mainHandler.postDelayed(pollTask, delay);
            });
        } catch (RejectedExecutionException ignored) {
            // Service is shutting down.
        }
    }

    private boolean ensureConnected() {
        if (!useRealAdapter) return true;
        if (realAdapter.isConnected()) return true;

        LiveReadings.clearValues();
        LiveReadings.status = "Connecting to OBD adapter…";
        supportedPids = Collections.emptySet();
        try {
            realAdapter.connect();
            connectFailures = 0;
            lastConnectedTimestamp = System.currentTimeMillis();
            AppLog.i(this, TAG, "OBD adapter connected");
            return true;
        } catch (IOException | InterruptedException | RuntimeException e) {
            realAdapter.disconnect();
            connectFailures++;
            if (connectFailures == 1 || connectFailures % 12 == 0) {
                AppLog.e(this, TAG, "OBD adapter connect failed (attempt " + connectFailures + ")", e);
            }
            LiveReadings.status = "Can't reach OBD adapter — retrying";
            updateNotification("Waiting for OBD adapter…");
            if (System.currentTimeMillis() - lastConnectedTimestamp > GIVE_UP_WITHOUT_ADAPTER_MS) {
                AppLog.i(this, TAG, "No adapter for a long time, stopping. Reopen the app or reconnect to restart.");
                stopSelf();
            }
            return false;
        }
    }

    /** Adapter is linked, but the truck's computer may not be awake yet (key off). */
    private boolean ensureTruckAnswering() throws IOException {
        if (!useRealAdapter) return true;
        if (!supportedPids.isEmpty()) return true;

        Set<Integer> found = realAdapter.readSupportedPids();
        if (found.isEmpty()) {
            LiveReadings.status = "Adapter connected — truck not responding (turn key on)";
            LiveReadings.clearValues();
            updateNotification("Adapter connected, truck not responding");
            return false;
        }
        supportedPids = found;
        LiveReadings.supported = found;
        AppSettings.setSupportedPids(this, found);
        AppLog.i(this, TAG, "Truck answered on " + realAdapter.describeProtocol()
                + "; supported PIDs: " + describePids(found));
        return true;
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
        want.add(PidCatalog.COOLANT);
        want.add(PidCatalog.MAF);
        want.add(PidCatalog.FUEL_RATE);

        for (int id : want) {
            PidCatalog.Pid def = PidCatalog.get(id);
            if (def == null || def.computed) continue;
            if (useRealAdapter && !supportedPids.contains(id)) continue;
            int[] data = useRealAdapter ? realAdapter.readPid(id) : simulator.readPid(id);
            double value = def.decode(data);
            if (Double.isNaN(value)) {
                LiveReadings.values.remove(id);
            } else {
                LiveReadings.values.put(id, value);
            }
        }

        long now = System.currentTimeMillis();
        Double rpm = LiveReadings.values.get(PidCatalog.RPM);
        boolean engineRunning = useRealAdapter ? (rpm != null && rpm > 0) : true;

        if (!engineRunning) {
            LiveReadings.engineRunning = false;
            LiveReadings.status = "Truck connected — engine off";
            lastTickTimestamp = now;
            if (engineOffSince == 0) engineOffSince = now;
            if (activeSession != null && now - engineOffSince > END_TRIP_AFTER_ENGINE_OFF_MS) {
                endSession(now);
            }
            updateNotification("Connected — engine off");
            return;
        }
        engineOffSince = 0;
        LiveReadings.engineRunning = true;
        if (useRealAdapter) LiveReadings.status = "Engine running";

        double dtSeconds = (now - lastTickTimestamp) / 1000.0;
        lastTickTimestamp = now;
        if (dtSeconds <= 0 || dtSeconds > MAX_TICK_SECONDS) dtSeconds = 0;

        // Distance = speed x engine-on time, the same integration MPG uses.
        Double obdSpeed = LiveReadings.values.get(PidCatalog.SPEED);
        double speedMph = obdSpeed != null ? obdSpeed : lastSpeedMph;
        double distanceDeltaMiles = speedMph * (dtSeconds / 3600.0);
        double engineHoursDelta = dtSeconds / 3600.0;

        Double maf = LiveReadings.values.get(PidCatalog.MAF);
        Double fuelRate = LiveReadings.values.get(PidCatalog.FUEL_RATE);
        boolean fuelKnown = maf != null || fuelRate != null;
        double gallonsPerHour = maf != null
                ? MpgCalculator.fuelGallonsPerHour(maf.floatValue())
                : (fuelRate != null ? fuelRate : 0);
        double gallonsDelta = gallonsPerHour * (dtSeconds / 3600.0);
        lastInstantMpg = (gallonsPerHour > 0.01 && speedMph > 0.5) ? speedMph / gallonsPerHour : 0;

        Double coolantF = LiveReadings.values.get(PidCatalog.COOLANT);

        if (activeSession == null) startSession(now);

        profile.odometerMiles += distanceDeltaMiles;
        profile.milesSinceOilChange += distanceDeltaMiles;
        profile.engineHoursSinceOilChange += engineHoursDelta;
        profile.totalFuelGallons += gallonsDelta;
        profile.oilLifePercent = OilLifeEngine.degrade(
                profile.oilLifePercent, distanceDeltaMiles, profile.severeDuty,
                rpm != null ? rpm : 0, coolantF != null ? coolantF : 0);

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
        LiveReadings.values.put(PidCatalog.COMPUTED_ODOMETER, profile.odometerMiles);

        if (now - lastPersistTimestamp >= PERSIST_INTERVAL_MS) {
            lastPersistTimestamp = now;
            persist(now, speedMph, rpm != null ? rpm : 0, maf != null ? maf : 0);
        }
    }

    private void persist(long now, double speedMph, double rpm, double maf) {
        db.vehicleProfileDao().update(profile);
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
        activeSession.startOdometerMiles = profile.odometerMiles;
        activeSession.id = db.sessionDao().insert(activeSession);
        distanceSincePoint = 0;
    }

    private void endSession(long now) {
        activeSession.endTimestamp = now;
        db.sessionDao().update(activeSession);
        db.vehicleProfileDao().update(profile);
        activeSession = null;
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
        NotificationCompat.Builder builder = NotificationUtils.trackingNotificationBuilder(this)
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
        gpsTracker.stop();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        LiveReadings.clearValues();
        LiveReadings.status = "Tracking is off";
        try {
            ioExecutor.execute(() -> {
                if (realAdapter != null) realAdapter.disconnect();
                if (activeSession != null) {
                    activeSession.endTimestamp = System.currentTimeMillis();
                    db.sessionDao().update(activeSession);
                }
                if (profile != null) db.vehicleProfileDao().update(profile);
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
