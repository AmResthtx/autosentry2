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

    public interface BinaryBody {
        void write(OutputStream out) throws IOException;
    }

    /** Returns where the file went, for the user. */
    public static String save(Context context, String fileName, Body body) throws IOException {
        String mime = fileName.endsWith(".csv") ? "text/csv"
                : fileName.endsWith(".json") ? "application/json" : "text/plain";
        return saveBinary(context, fileName, mime, stream -> write(stream, body));
    }

    /** Like {@link #save}, for files that aren't text (the backup zip). */
    public static String saveBinary(Context context, String fileName, String mime, BinaryBody body) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, mime);
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
            Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("Downloads folder refused " + fileName);
            try (OutputStream stream = context.getContentResolver().openOutputStream(uri)) {
                if (stream == null) throw new IOException("Couldn't open " + fileName);
                body.write(stream);
            } catch (IOException | RuntimeException e) {
                context.getContentResolver().delete(uri, null, null); // no half-written file left behind
                throw e;
            }
            return Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "/" + fileName;
        }
        File dir = new File(context.getExternalFilesDir(null), FOLDER);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Couldn't create " + dir);
        File file = new File(dir, fileName);
        try (OutputStream stream = new FileOutputStream(file)) {
            body.write(stream);
        }
        return file.getAbsolutePath();
    }

    private static void write(OutputStream stream, Body body) throws IOException {
        Writer out = new OutputStreamWriter(stream, StandardCharsets.UTF_8);
        body.write(out);
        out.flush();
    }
}
