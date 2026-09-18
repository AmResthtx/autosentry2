package com.autosentry.app.gps;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;

import androidx.core.content.ContextCompat;

/**
 * Thin wrapper over LocationManager (no Play Services dependency needed).
 * Reports speed (mph) and per-fix distance deltas (miles) via Callback.
 */
public class GpsTracker {
    public interface Callback {
        void onFix(double latitude, double longitude, double speedMph, double distanceDeltaMiles);
    }

    private static final long MIN_UPDATE_INTERVAL_MS = 1000L;
    private static final float MIN_UPDATE_DISTANCE_M = 0f;
    private static final double METERS_TO_MILES = 0.000621371;
    private static final double MPS_TO_MPH = 2.23694;

    private final Context context;
    private final LocationManager locationManager;
    private Location lastLocation;
    private Callback callback;

    private final LocationListener listener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            double distanceDeltaMiles = 0;
            if (lastLocation != null) {
                distanceDeltaMiles = lastLocation.distanceTo(location) * METERS_TO_MILES;
            }
            double speedMph = location.hasSpeed() ? location.getSpeed() * MPS_TO_MPH : 0;
            lastLocation = location;
            if (callback != null) {
                callback.onFix(location.getLatitude(), location.getLongitude(), speedMph, distanceDeltaMiles);
            }
        }

        @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
        @Override public void onProviderEnabled(String provider) {}
        @Override public void onProviderDisabled(String provider) {}
    };

    public GpsTracker(Context context) {
        this.context = context.getApplicationContext();
        this.locationManager = (LocationManager) this.context.getSystemService(Context.LOCATION_SERVICE);
    }

    public boolean hasPermission() {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    public void start(Callback callback) {
        this.callback = callback;
        this.lastLocation = null;
        if (!hasPermission() || locationManager == null) return;

        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, MIN_UPDATE_INTERVAL_MS, MIN_UPDATE_DISTANCE_M,
                    listener, Looper.getMainLooper());
        } else if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, MIN_UPDATE_INTERVAL_MS, MIN_UPDATE_DISTANCE_M,
                    listener, Looper.getMainLooper());
        }
    }

    public void stop() {
        if (locationManager != null) {
            locationManager.removeUpdates(listener);
        }
        callback = null;
    }
}
