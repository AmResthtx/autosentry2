package com.autosentry.app.util;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * On-device error log, separate from Logcat. Logcat disappears the moment
 * the tablet isn't tethered to a PC (i.e. once it's actually mounted in the
 * truck) — this writes to a plain file so DebugLogActivity can show what
 * went wrong without needing adb at all.
 *
 * Deliberately a flat file, not a Room table: writes here must survive an
 * uncaught-exception handler running during process teardown, where a DB
 * transaction could be interrupted mid-write.
 */
public final class AppLog {
    private static final String LOG_FILE_NAME = "autosentry_debug_log.txt";
    private static final long MAX_LOG_BYTES = 512 * 1024L; // rotate at 512KB
    private static final SimpleDateFormat TIMESTAMP_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private AppLog() {}

    public static synchronized void e(Context context, String tag, String message, Throwable t) {
        Log.e(tag, message, t);
        StringBuilder sb = new StringBuilder();
        sb.append("ERROR [").append(tag).append("] ").append(message);
        if (t != null) {
            sb.append(" :: ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
        }
        append(context, sb.toString());
    }

    public static synchronized void i(Context context, String tag, String message) {
        Log.i(tag, message);
        append(context, "INFO  [" + tag + "] " + message);
    }

    private static void append(Context context, String line) {
        try {
            File file = logFile(context);
            rotateIfNeeded(file);
            String timestamped = TIMESTAMP_FORMAT.format(new Date()) + "  " + line + "\n";
            try (FileWriter writer = new FileWriter(file, true)) {
                writer.write(timestamped);
            }
        } catch (IOException e) {
            Log.e("AppLog", "Failed to write debug log", e);
        }
    }

    private static void rotateIfNeeded(File file) {
        if (file.exists() && file.length() > MAX_LOG_BYTES) {
            file.delete(); // simple rotation: drop and start fresh rather than growing unbounded
        }
    }

    private static File logFile(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), LOG_FILE_NAME);
    }

    public static String readAll(Context context) {
        File file = logFile(context);
        if (!file.exists()) return "(no errors logged yet)";
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(failed to read log: " + e.getMessage() + ")";
        }
    }

    public static void clear(Context context) {
        File file = logFile(context);
        if (file.exists()) file.delete();
    }

    public static File getLogFile(Context context) {
        return logFile(context);
    }
}
