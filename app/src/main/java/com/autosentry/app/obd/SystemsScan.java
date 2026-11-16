package com.autosentry.app.obd;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hand-off between the Truck Systems screen and TrackingService for the
 * systems scan (see TruckProbe), the same pattern as DiagnosticCodes.
 */
public final class SystemsScan {
    /** Folder in the app's files where the last report is kept (shared through the FileProvider). */
    public static final String REPORT_DIR = "reports";
    /** The last full report, so it survives restarts and can be shared. */
    public static final String REPORT_FILE = "truck_systems_report.txt";
    /** The same report as JSON. */
    public static final String REPORT_JSON = "truck_systems_report.json";

    /** Send read requests to the truck; off means listen-only (nothing sent). */
    public static volatile boolean active = false;
    /** Show the VIN in the report; hidden by default. */
    public static volatile boolean includeVin = false;

    private static final AtomicBoolean requested = new AtomicBoolean(false);

    public static volatile boolean busy = false;
    /** What the scan is doing right now, e.g. "Ford PID sweep 412/1536". */
    public static volatile String progress = null;
    /** Why the last request didn't run, or null. */
    public static volatile String message = null;
    /** The finished report from this run; the screen falls back to the saved file when null. */
    public static volatile String lastReport = null;
    /** Set by the Stop button; the scan checks it between steps. */
    public static volatile boolean cancel = false;

    private SystemsScan() {}

    public static void request() {
        message = null;
        cancel = false;
        requested.set(true);
    }

    public static boolean take() {
        return requested.getAndSet(false);
    }
}
