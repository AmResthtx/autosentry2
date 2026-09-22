package com.autosentry.app.service;

import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

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
import com.autosentry.app.obd.OBDSimulator;
import com.autosentry.app.settings.AppSettings;
import com.autosentry.app.util.AppLog;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service that runs for the life of a trip: polls the OBD adapter
 * (RPM, coolant temp, MAF) roughly once a second, takes GPS fixes for speed
 * and distance, and on every tick:
 *   1. advances the session's distance/fuel/mpg totals
 *   2. advances the vehicle profile's odometer + oil life
 *   3. persists a TripPoint row for history/graphing
 *
 * Production mode requires a real adapter. The simulator is only allowed when
 * explicitly enabled in settings for testing.
 */
public class TrackingService extends Service {
    private static final String TAG = "TrackingService";
    public static final String EXTRA_ADAPTER_ADDRESS = "adapter_address";
    private static final long POLL_INTERVAL_MS = 1000L;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private AppDatabase db;
    private GpsTracker gpsTracker;
    private ELM327Adapter realAdapter;
    private OBDSimulator simulator;
    private boolean useRealAdapter;
    private boolean usingSimulator;

    private Session activeSession;
    private VehicleProfile profile;
    private final Set<ServiceItemType> notifiedDueSoon = EnumSet.noneOf(ServiceItemType.class);

    private volatile double lastSpeedMph = 0;
    private volatile double lastLat = 0, lastLon = 0;
    private long lastTickTimestamp;

    private final Runnable pollTask = this::pollTick;

    @Override
    public void onCreate() {
        super.onCreate();
        db = AppDatabase.getInstance(this);
        gpsTracker = new GpsTracker(this);
        simulator = new OBDSimulator();
        NotificationUtils.ensureChannels(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String adapterAddress = intent != null ? intent.getStringExtra(EXTRA_ADAPTER_ADDRESS) : null;
        useRealAdapter = adapterAddress != null && !adapterAddress.isEmpty();
        usingSimulator = false;
        if (useRealAdapter) {
            realAdapter = new ELM327Adapter(adapterAddress);
        }

        startForeground(NotificationUtils.TRACKING_NOTIFICATION_ID, buildNotification("Starting monitoring..."));
        ioExecutor.execute(this::startSessionAndAdapter);
        return START_STICKY;
    }

    private void startSessionAndAdapter() {
        profile = db.vehicleProfileDao().getSync();
        if (profile == null) {
            profile = VehicleProfile.newDefault();
            db.vehicleProfileDao().insert(profile);
        }

        Session existing = db.sessionDao().getActive();
        if (existing != null) {
            activeSession = existing;
        } else {
            activeSession = new Session();
            activeSession.startTimestamp = System.currentTimeMillis();
            activeSession.startOdometerMiles = profile.odometerMiles;
            long id = db.sessionDao().insert(activeSession);
            activeSession.id = id;
        }

        if (useRealAdapter) {
            try {
                realAdapter.connect();
                AppLog.i(this, TAG, "Connected to OBD adapter");
            } catch (Exception e) {
                AppLog.e(this, TAG, "OBD connect failed; real monitoring unavailable", e);
                useRealAdapter = false;
                if (!AppSettings.isSimulatorModeEnabled(this)) {
                    updateNotification("Waiting for OBD adapter");
                }
            }
        }

        lastTickTimestamp = System.currentTimeMillis();
        mainHandler.post(() -> {
            gpsTracker.start((lat, lon, speedMph, distanceDeltaMiles) -> {
                lastLat = lat;
                lastLon = lon;
                lastSpeedMph = speedMph;
            });
            mainHandler.postDelayed(pollTask, POLL_INTERVAL_MS);
        });
    }

    private void pollTick() {
        ioExecutor.execute(() -> {
            try {
                int rpm = 0;
                int coolantC = -40;
                float maf = 0f;
                boolean haveRealReadings = false;

                if (useRealAdapter && realAdapter != null && realAdapter.isConnected()) {
                    try {
                        rpm = realAdapter.readRPM();
                        coolantC = realAdapter.readCoolantTemp();
                        maf = realAdapter.readMAF();
                        haveRealReadings = true;
                    } catch (Exception e) {
                        AppLog.e(this, TAG, "Failed reading OBD data; real monitoring unavailable", e);
                        useRealAdapter = false;
                    }
                }

                if (!haveRealReadings && AppSettings.isSimulatorModeEnabled(this)) {
                    usingSimulator = true;
                    rpm = simulator.readRPM();
                    coolantC = simulator.readCoolantTemp();
                    maf = simulator.readMAF();
                    haveRealReadings = true;
                }

                if (!haveRealReadings) {
                    updateNotification("Waiting for OBD adapter");
                    mainHandler.postDelayed(pollTask, POLL_INTERVAL_MS);
                    return;
                }

                double coolantF = coolantC * 9.0 / 5.0 + 32.0;
                long now = System.currentTimeMillis();
                double dtSeconds = Math.max(0.001, (now - lastTickTimestamp) / 1000.0);
                lastTickTimestamp = now;

                double distanceDeltaMiles = lastSpeedMph * (dtSeconds / 3600.0);
                double engineHoursDelta = dtSeconds / 3600.0;
                double gallonsDelta = MpgCalculator.gallonsForInterval(maf, dtSeconds);
                double instantMpg = MpgCalculator.instantMpg(lastSpeedMph, maf);

                profile.odometerMiles += distanceDeltaMiles;
                profile.milesSinceOilChange += distanceDeltaMiles;
                profile.engineHoursSinceOilChange += engineHoursDelta;
                profile.totalFuelGallons += gallonsDelta;
                profile.oilLifePercent = OilLifeEngine.degrade(
                        profile.oilLifePercent, distanceDeltaMiles, profile.severeDuty, rpm, coolantF);
                db.vehicleProfileDao().update(profile);

                checkServiceThresholds();

                activeSession.distanceMiles += distanceDeltaMiles;
                activeSession.fuelGallonsUsed += gallonsDelta;
                activeSession.maxSpeedMph = Math.max(activeSession.maxSpeedMph, lastSpeedMph);
                if (activeSession.fuelGallonsUsed > 0) {
                    activeSession.avgMpg = activeSession.distanceMiles / activeSession.fuelGallonsUsed;
                }
                db.sessionDao().update(activeSession);

                TripPoint point = new TripPoint();
                point.sessionId = activeSession.id;
                point.timestamp = now;
                point.latitude = lastLat;
                point.longitude = lastLon;
                point.speedMph = lastSpeedMph;
                point.distanceDeltaMiles = distanceDeltaMiles;
                point.rpm = rpm;
                point.mafGramsPerSec = maf;
                point.instantMpg = instantMpg;
                db.tripPointDao().insert(point);

                String statusText = usingSimulator
                        ? String.format("SIM | %.0f mph | %.1f MPG | Oil life %.0f%%",
                                lastSpeedMph, instantMpg, profile.oilLifePercent)
                        : String.format("%.0f mph | %.1f MPG | Oil life %.0f%%",
                                lastSpeedMph, instantMpg, profile.oilLifePercent);
                updateNotification(statusText);
            } catch (Exception e) {
                AppLog.e(this, TAG, "Poll tick failed", e);
            }

            mainHandler.postDelayed(pollTask, POLL_INTERVAL_MS);
        });
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
                ? String.format("%s is overdue (%.0f%% of service life used)", status.displayName, status.percentOfLifeUsed)
                : String.format("%s at %.0f%% of service life — %.0f mi remaining", status.displayName, status.percentOfLifeUsed, status.milesRemaining());

        androidx.core.app.NotificationCompat.Builder builder = new androidx.core.app.NotificationCompat.Builder(this, NotificationUtils.CHANNEL_TRACKING)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Maintenance due soon")
                .setContentText(text)
                .setAutoCancel(true);

        android.app.NotificationManager manager =
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(2000 + status.type.ordinal(), builder.build());
        }
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
        mainHandler.removeCallbacks(pollTask);
        gpsTracker.stop();
        if (realAdapter != null) {
            realAdapter.disconnect();
        }
        if (activeSession != null) {
            activeSession.endTimestamp = System.currentTimeMillis();
            ioExecutor.execute(() -> db.sessionDao().update(activeSession));
        }
        ioExecutor.shutdown();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
