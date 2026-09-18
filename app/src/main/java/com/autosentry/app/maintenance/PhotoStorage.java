package com.autosentry.app.maintenance;

import android.content.Context;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Locale;

/**
 * Copies every attached photo into this app's own storage, regardless of
 * whether it came from the camera or the gallery. Point of the feature is a
 * durable, timestamped record tied to the vehicle — a photo left referenced
 * only by its original gallery URI would vanish the moment the user deletes
 * it from their gallery app, which defeats the purpose.
 */
public final class PhotoStorage {
    private static final String DIR_NAME = "maintenance_photos";
    private static final SimpleDateFormat FILENAME_FORMAT =
            new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US);

    private PhotoStorage() {}

    private static File photosDir(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), DIR_NAME);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** Creates an empty file + content:// Uri for the camera app to write a capture into. */
    public static File newCaptureFile(Context context) {
        String name = "IMG_" + FILENAME_FORMAT.format(new java.util.Date()) + ".jpg";
        return new File(photosDir(context), name);
    }

    public static Uri toContentUri(Context context, File file) {
        return FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
    }

    /** Copies an imported (gallery/picker) image into local storage, returning the new local file. */
    public static File importFrom(Context context, Uri sourceUri) throws IOException {
        String name = "IMG_" + FILENAME_FORMAT.format(new java.util.Date()) + ".jpg";
        File dest = new File(photosDir(context), name);
        try (InputStream in = context.getContentResolver().openInputStream(sourceUri);
             OutputStream out = new FileOutputStream(dest)) {
            if (in == null) throw new IOException("Could not open source image");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        return dest;
    }
}
