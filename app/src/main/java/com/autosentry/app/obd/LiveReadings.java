package com.autosentry.app.obd;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory hand-off between TrackingService (writer) and the dashboard
 * (reader). Live values are only meaningful while the truck is running, so
 * nothing here is persisted.
 */
public final class LiveReadings {
    /** Latest decoded value per PID id, in display units. Missing key = no reading. */
    public static final Map<Integer, Double> values = new ConcurrentHashMap<>();

    public static final String IDLE_STATUS = "Tracking is off — tap Start Tracking";

    public static volatile String status = IDLE_STATUS;
    public static volatile boolean engineRunning = false;
    public static volatile Set<Integer> supported = Collections.emptySet();

    private LiveReadings() {}

    public static void clearValues() {
        values.clear();
        engineRunning = false;
    }
}
