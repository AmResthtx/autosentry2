package com.autosentry.app.util;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * Saves reports and exports on the tablet itself, so nothing needs internet:
 * Downloads/AutoSentry (Android 10+), visible in the Files app and over USB.
 * Older Android versions get the app's own folder, reachable over USB under
 * Android/data/com.autosentry.app/files/AutoSentry.
 */
public final class TabletFiles {
    private static final String FOLDER = "AutoSentry";

    public interface Body {
        void write(Writer out) throws IOException;
    }

    private TabletFiles() {}

    public static String save(Context context, String fileName, String text) throws IOException {
        return save(context, fileName, out -> out.write(text));
    }

    /** Returns where the file went, for the user. */
    public static String save(Context context, String fileName, Body body) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, fileName.endsWith(".csv") ? "text/csv" : "text/plain");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
            Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("Downloads folder refused " + fileName);
            try (OutputStream stream = context.getContentResolver().openOutputStream(uri)) {
                if (stream == null) throw new IOException("Couldn't open " + fileName);
                write(stream, body);
            }
            return Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "/" + fileName;
        }
        File dir = new File(context.getExternalFilesDir(null), FOLDER);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Couldn't create " + dir);
        File file = new File(dir, fileName);
        try (OutputStream stream = new FileOutputStream(file)) {
            write(stream, body);
        }
        return file.getAbsolutePath();
    }

    private static void write(OutputStream stream, Body body) throws IOException {
        Writer out = new OutputStreamWriter(stream, StandardCharsets.UTF_8);
        body.write(out);
        out.flush();
    }
}
