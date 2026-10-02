package com.autosentry.app.service;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/**
 * Safety net for tablets whose maker kills background services anyway:
 * every 15 minutes (Android's shortest periodic interval), restart tracking
 * if it should be running and isn't.
 */
public class TrackingWatchdogWorker extends Worker {
    private static final String WORK_NAME = "tracking_watchdog";

    public TrackingWatchdogWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    public static void schedule(Context context) {
        PeriodicWorkRequest request =
                new PeriodicWorkRequest.Builder(TrackingWatchdogWorker.class, 15, TimeUnit.MINUTES).build();
        WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    @NonNull
    @Override
    public Result doWork() {
        TrackingService.startIfEnabled(getApplicationContext(), "watchdog");
        return Result.success();
    }
}
