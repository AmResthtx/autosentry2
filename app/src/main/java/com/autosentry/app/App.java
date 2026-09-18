package com.autosentry.app;

import android.app.Application;

import com.autosentry.app.notifications.NotificationUtils;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        NotificationUtils.ensureChannels(this);
    }
}
