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

    /**
     * How each reading the service is asking for is doing (live, no answer, refused, not
     * offered by this truck). Empty while the truck isn't connected.
     */
    public static final Map<Integer, PollPlan.State> states = new ConcurrentHashMap<>();
    /** The last few minutes of every reading, for the graphs. */
    public static final ReadingHistory history = new ReadingHistory();

    /** Set by the graph screen to start and stop recording to a CSV file; the service does the writing. */
    public static volatile boolean recordRequested = false;
    /** True while rows are being written. */
    public static volatile boolean recording = false;
    /** The latest thing worth saying about recording, e.g. where the file was saved. */
    public static volatile String recordStatus = null;

    /** Output of the Account-tab connection test, built up line by line as it runs. */
    public static volatile String testReport = "";
    public static volatile boolean testRunning = false;

    private LiveReadings() {}

    public static void clearValues() {
        values.clear();
        states.clear();
        engineRunning = false;
    }
}
