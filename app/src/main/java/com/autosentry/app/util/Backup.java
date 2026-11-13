package com.autosentry.app.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;

import com.autosentry.app.BuildConfig;
import com.autosentry.app.data.AppDatabase;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Everything AutoSentry knows about the truck in one zip in Downloads/AutoSentry: the
 * database (trips, maintenance, key-on reports), the settings (paired adapter, dashboard,
 * setup progress, saved code scans) and the photos.
 *
 * App updates keep all of this on their own. The backup is for the cases an update can't
 * cover: uninstalling (Android backups are off for this app), moving to another tablet, and
 * the one-time uninstall needed to move onto the persistent signing key (docs/UPDATES.md).
 */
public final class Backup {
    private static final String PREFS_NAME = "autosentry_settings";
    private static final long MAX_ENTRY_BYTES = 512L * 1024 * 1024;

    /** True while a restore is replacing files; tracking must not start (and open the database) meanwhile. */
    public static volatile boolean restoring = false;

    private Backup() {}

    /** Writes a backup zip to Downloads/AutoSentry and returns where it went. */
    public static String export(Context context) throws IOException {
        Context app = context.getApplicationContext();
        String name = "autosentry-backup-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".zip";
        return TabletFiles.saveBinary(app, name, "application/zip", out -> write(app, out));
    }

    private static void write(Context app, OutputStream out) throws IOException {
        AppDatabase db = AppDatabase.getInstance(app);
        ZipOutputStream zip = new ZipOutputStream(out);
        IOException[] failure = new IOException[1];
        // Holding a write transaction keeps the tracking service from changing the file
        // mid-copy; the -wal file goes along, so anything not yet merged comes too.
        db.runInTransaction(() -> {
            try {
                File dbFile = app.getDatabasePath(AppDatabase.DB_NAME);
                int dbVersion = db.getOpenHelper().getWritableDatabase().getVersion();
                JSONObject manifest = new JSONObject();
                manifest.put("format", BackupFormat.FORMAT);
                manifest.put("created", System.currentTimeMillis());
                manifest.put("appVersion", BuildConfig.VERSION_NAME);
                manifest.put("appBuild", BuildConfig.VERSION_CODE);
                manifest.put("dbVersion", dbVersion);
                putText(zip, BackupFormat.MANIFEST, manifest.toString(2));
                putText(zip, BackupFormat.SETTINGS, settingsJson(app).toString(2));
                putFile(zip, BackupFormat.DB_DIR + AppDatabase.DB_NAME, dbFile);
                File wal = new File(dbFile.getPath() + "-wal");
                if (wal.isFile()) putFile(zip, BackupFormat.DB_DIR + AppDatabase.DB_NAME + "-wal", wal);
            } catch (IOException e) {
                failure[0] = e;
            } catch (JSONException e) {
                failure[0] = new IOException("Couldn't write the settings", e);
            }
        });
        if (failure[0] != null) throw failure[0];
        for (String folder : BackupFormat.FILE_FOLDERS) {
            File[] files = new File(app.getFilesDir(), folder).listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (f.isFile()) putFile(zip, BackupFormat.FILES_DIR + folder + "/" + f.getName(), f);
            }
        }
        zip.finish();
    }

    /** Every saved setting with its type, so a restore puts back exactly what was there. */
    private static JSONObject settingsJson(Context app) throws JSONException {
        JSONObject json = new JSONObject();
        for (Map.Entry<String, ?> e : app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getAll().entrySet()) {
            Object v = e.getValue();
            JSONObject item = new JSONObject();
            if (v instanceof Boolean) item.put("t", "b").put("v", v);
            else if (v instanceof Integer) item.put("t", "i").put("v", v);
            else if (v instanceof Long) item.put("t", "l").put("v", v);
            else if (v instanceof Float) item.put("t", "f").put("v", (double) (Float) v);
            else if (v instanceof String) item.put("t", "s").put("v", v);
            else if (v instanceof Set) item.put("t", "ss").put("v", new JSONArray((Set<?>) v));
            else continue;
            json.put(e.getKey(), item);
        }
        return json;
    }

    /**
     * Replaces this install's data with a backup's. The caller must have stopped tracking.
     * The zip is unpacked and checked in full before anything is touched, so a bad file
     * changes nothing. Returns a one-line description of what was restored. The app must be
     * restarted afterwards: open screens still hold the old data.
     */
    public static String restore(Context context, InputStream source) throws IOException {
        Context app = context.getApplicationContext();
        File staging = new File(app.getCacheDir(), "restore");
        deleteTree(staging);
        try {
            unpack(source, staging);
            JSONObject manifest;
            JSONObject settings;
            try {
                manifest = new JSONObject(readText(new File(staging, BackupFormat.MANIFEST)));
                settings = new JSONObject(readText(new File(staging, BackupFormat.SETTINGS)));
            } catch (JSONException | IOException e) {
                throw new IOException("This file isn't an AutoSentry backup.");
            }
            File stagedDb = new File(staging, BackupFormat.DB_DIR + AppDatabase.DB_NAME);
            if (!stagedDb.isFile()) throw new IOException("This backup has no database in it.");
            String refusal = BackupFormat.refusal(manifest.optInt("format", -1), manifest.optInt("dbVersion", -1),
                    AppDatabase.VERSION);
            if (refusal != null) throw new IOException(refusal);
            checkOpens(stagedDb);
            return replace(app, staging, stagedDb, manifest, settings);
        } finally {
            deleteTree(staging);
        }
    }

    /** The point of no return: everything before this only read the backup. */
    private static String replace(Context app, File staging, File stagedDb, JSONObject manifest, JSONObject settings)
            throws IOException {
        try {
            AppDatabase.closeInstance();
            File dbFile = app.getDatabasePath(AppDatabase.DB_NAME);
            File dbDir = dbFile.getParentFile();
            if (dbDir != null && !dbDir.isDirectory() && !dbDir.mkdirs()) throw new IOException("Couldn't create " + dbDir);
            for (String suffix : new String[]{"", "-wal", "-shm", "-journal"}) {
                File old = new File(dbFile.getPath() + suffix);
                if (old.exists() && !old.delete()) throw new IOException("Couldn't replace " + old.getName());
            }
            copy(stagedDb, dbFile);
            File stagedWal = new File(stagedDb.getPath() + "-wal");
            if (stagedWal.isFile()) copy(stagedWal, new File(dbFile.getPath() + "-wal"));

            int files = 0;
            for (String folder : BackupFormat.FILE_FOLDERS) {
                File target = new File(app.getFilesDir(), folder);
                deleteTree(target);
                File[] staged = new File(staging, BackupFormat.FILES_DIR + folder).listFiles();
                if (staged == null) continue;
                if (!target.mkdirs()) throw new IOException("Couldn't create " + target);
                for (File f : staged) {
                    copy(f, new File(target, f.getName()));
                    files++;
                }
            }
            int restoredSettings = applySettings(app, settings);
            return "Restored the backup from " + new SimpleDateFormat("MMM d, yyyy h:mm a", Locale.US)
                    .format(new Date(manifest.optLong("created"))) + " (AutoSentry " + manifest.optString("appVersion", "?")
                    + " build " + manifest.optInt("appBuild") + "): database, " + restoredSettings + " settings, "
                    + files + " photos and reports.";
        } catch (IOException | RuntimeException e) {
            throw new IOException("The restore stopped partway (" + e.getMessage() + "). What was on this tablet before "
                    + "is in the safety backup saved to Downloads/AutoSentry just now: restore that file.");
        }
    }

    private static void unpack(InputStream source, File staging) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(source)) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            boolean any = false;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                if (!BackupFormat.isSafeEntry(entry.getName(), AppDatabase.DB_NAME)) {
                    throw new IOException("This file isn't an AutoSentry backup (unexpected item: " + entry.getName() + ").");
                }
                any = true;
                File target = new File(staging, entry.getName());
                File dir = target.getParentFile();
                if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Couldn't unpack the backup");
                long total = 0;
                try (OutputStream out = new FileOutputStream(target)) {
                    int n;
                    while ((n = zip.read(buffer)) > 0) {
                        total += n;
                        if (total > MAX_ENTRY_BYTES) throw new IOException("The backup is too large to be real.");
                        out.write(buffer, 0, n);
                    }
                }
            }
            if (!any) throw new IOException("This file isn't an AutoSentry backup.");
        }
    }

    /** The staged database must be a real SQLite file before the live one is replaced with it. */
    private static void checkOpens(File stagedDb) throws IOException {
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(stagedDb.getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
            if (db.getVersion() < 1 || db.getVersion() > AppDatabase.VERSION) {
                throw new IOException("The backup's database is version " + db.getVersion()
                        + ", which this app can't read. Update the app, then restore.");
            }
        } catch (android.database.SQLException e) {
            throw new IOException("The backup's database is damaged: " + e.getMessage());
        }
    }

    private static int applySettings(Context app, JSONObject settings) throws IOException {
        SharedPreferences.Editor edit = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear();
        int count = 0;
        try {
            JSONArray names = settings.names();
            for (int i = 0; names != null && i < names.length(); i++) {
                String key = names.getString(i);
                JSONObject item = settings.getJSONObject(key);
                switch (item.getString("t")) {
                    case "b": edit.putBoolean(key, item.getBoolean("v")); break;
                    case "i": edit.putInt(key, item.getInt("v")); break;
                    case "l": edit.putLong(key, item.getLong("v")); break;
                    case "f": edit.putFloat(key, (float) item.getDouble("v")); break;
                    case "s": edit.putString(key, item.getString("v")); break;
                    case "ss": {
                        Set<String> set = new java.util.HashSet<>();
                        JSONArray values = item.getJSONArray("v");
                        for (int j = 0; j < values.length(); j++) set.add(values.getString(j));
                        edit.putStringSet(key, set);
                        break;
                    }
                    default: continue;
                }
                count++;
            }
        } catch (JSONException e) {
            throw new IOException("The backup's settings are damaged.");
        }
        if (!edit.commit()) throw new IOException("Couldn't save the restored settings.");
        return count;
    }

    private static void putText(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void putFile(ZipOutputStream zip, String name, File file) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        try (InputStream in = new FileInputStream(file)) {
            pipe(in, zip);
        }
        zip.closeEntry();
    }

    private static void copy(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            pipe(in, out);
        }
    }

    private static void pipe(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
    }

    private static String readText(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            pipe(in, bytes);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File c : children) deleteTree(c);
        file.delete();
    }
}
