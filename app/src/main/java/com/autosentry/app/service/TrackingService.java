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
import com.autosentry.app.notifications.NotificationUtils;
import com.autosentry.app.obd.ELM327Adapter;
import com.autosentry.app.obd.OBDSimulator;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service that runs for the life of a trip: polls the OBD adapter
 * (RPM, coolant temp, MAF) roughly once a second, takes GPS fixes for speed
 * and distance, and on every tick:
 *   1. advances the session's distance/fuel/mpg totals
 *   2. advances the vehicle profile's odometer + oil life (this is the part
 *      the old app never did — oil life must move every tick, not just once)
 *   3. persists a TripPoint row for history/graphing
 *
 * Uses OBDSimulator when no adapter address is configured, so the app is
 * testable without hardware attached.
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

    private Session activeSession;
    private VehicleProfile profile;

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
        if (useRealAdapter) {
            realAdapter = new ELM327Adapter(adapterAddress);
        }

        startForeground(NotificationUtils.TRACKING_NOTIFICATION_ID, buildNotification("Starting..."));

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
            } catch (Exception e) {
                Log.e(TAG, "OBD connect failed, falling back to simulator", e);
                useRealAdapter = false;
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
                int rpm;
                int coolantC;
                float maf;
                if (useRealAdapter && realAdapter.isConnected()) {
                    rpm = realAdapter.readRPM();
                    coolantC = realAdapter.readCoolantTemp();
                    maf = realAdapter.readMAF();
                } else {
                    rpm = simulator.readRPM();
                    coolantC = simulator.readCoolantTemp();
                    maf = simulator.readMAF();
                }
                double coolantF = coolantC * 9.0 / 5.0 + 32.0;

                long now = System.currentTimeMillis();
                double dtSeconds = Math.max(0.001, (now - lastTickTimestamp) / 1000.0);
                lastTickTimestamp = now;

                double distanceDeltaMiles = lastSpeedMph * (dtSeconds / 3600.0);
                double engineHoursDelta = dtSeconds / 3600.0;
                double gallonsDelta = MpgCalculator.gallonsForInterval(maf, dtSeconds);
                double instantMpg = MpgCalculator.instantMpg(lastSpeedMph, maf);

                // This tick is the fix for the old bug: profile mutates every poll,
                // not just once at trip start.
                profile.odometerMiles += distanceDeltaMiles;
                profile.milesSinceOilChange += distanceDeltaMiles;
                profile.engineHoursSinceOilChange += engineHoursDelta;
                profile.totalFuelGallons += gallonsDelta;
                profile.oilLifePercent = OilLifeEngine.degrade(
                        profile.oilLifePercent, distanceDeltaMiles, engineHoursDelta, rpm, coolantF);
                db.vehicleProfileDao().update(profile);

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

                updateNotification(String.format(
                        "%.0f mph | %.1f MPG | Oil life %.0f%%",
                        lastSpeedMph, instantMpg, profile.oilLifePercent));
            } catch (Exception e) {
                Log.e(TAG, "Poll tick failed", e);
            }

            mainHandler.postDelayed(pollTask, POLL_INTERVAL_MS);
        });
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
