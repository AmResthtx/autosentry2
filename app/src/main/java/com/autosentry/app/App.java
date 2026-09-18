package com.autosentry.app;

import android.app.Application;

import com.autosentry.app.notifications.NotificationUtils;
import com.autosentry.app.util.AppLog;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        NotificationUtils.ensureChannels(this);
        installCrashLogger();
    }

    /**
     * Catches anything that would otherwise just crash silently once this
     * tablet is sitting in the truck with nobody watching adb logcat.
     * Writes the crash to AppLog (readable via the in-app Debug Log screen),
     * then hands off to the platform's default handler so the crash still
     * behaves normally (process dies, Android shows/records it as usual).
     */
    private void installCrashLogger() {
        Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            AppLog.e(this, "UncaughtException", "Crash on thread " + thread.getName(), throwable);
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            }
        });
    }
}
