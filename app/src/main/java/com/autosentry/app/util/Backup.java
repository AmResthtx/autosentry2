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

    /** A dated name for a backup file the user is about to choose a place for. */
    public static String suggestedName() {
        return "autosentry-backup-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".zip";
    }

    /**
     * True when {@link #export} can't reach a folder that outlives the app. Before Android 10
     * it writes under the app's own external folder, which Android deletes on uninstall, and
     * surviving an uninstall is the whole point of a backup. There the user picks the place
     * and {@link #exportTo} writes to it.
     */
    public static boolean needsChosenDestination() {
        return android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q;
    }

    /** Writes a backup zip to a stream the caller opened (a file the user chose). */
    public static void exportTo(Context context, OutputStream out) throws IOException {
        write(context.getApplicationContext(), out);
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
            // Both of these can refuse the backup, so both run before anything live is touched.
            Map<String, Object> decodedSettings = decodeSettings(settings);
            File checkedDb = prepareDatabase(app, stagedDb);
            return replace(app, staging, checkedDb, manifest, decodedSettings);
        } finally {
            deleteTree(staging);
            app.deleteDatabase(CHECK_DB_NAME);
        }
    }

    private static final String CHECK_DB_NAME = "restore-check.db";

    /**
     * Proves the backup's database is one this app can run on, before it replaces the live
     * one: a copy is opened by Room itself, which migrates an older layout and then compares
     * every table with what the app expects. A file that merely looks like a database fails
     * here, with the live data untouched. Photo paths are then pointed at this install's
     * photo folder, since the backup may come from a tablet or user profile where the app's
     * storage sits somewhere else. Returns the checked copy, ready to move into place.
     */
    private static File prepareDatabase(Context app, File stagedDb) throws IOException {
        app.deleteDatabase(CHECK_DB_NAME);
        File check = app.getDatabasePath(CHECK_DB_NAME);
        File dir = check.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Couldn't create " + dir);
        copy(stagedDb, check);
        File stagedWal = new File(stagedDb.getPath() + "-wal");
        if (stagedWal.isFile()) copy(stagedWal, new File(check.getPath() + "-wal"));

        AppDatabase db = AppDatabase.open(app, CHECK_DB_NAME);
        try {
            androidx.sqlite.db.SupportSQLiteDatabase sql = db.getOpenHelper().getWritableDatabase();
            File photos = new File(app.getFilesDir(), "maintenance_photos");
            java.util.List<Object[]> moved = new java.util.ArrayList<>();
            try (android.database.Cursor c = sql.query("SELECT id, filePath FROM maintenance_attachments")) {
                while (c.moveToNext()) {
                    String path = c.getString(1);
                    if (path == null || path.isEmpty()) continue;
                    String here = new File(photos, new File(path).getName()).getAbsolutePath();
                    if (!here.equals(path)) moved.add(new Object[]{here, c.getLong(0)});
                }
            }
            for (Object[] row : moved) {
                sql.execSQL("UPDATE maintenance_attachments SET filePath = ? WHERE id = ?", row);
            }
        } catch (RuntimeException e) {
            throw new IOException("The backup's database isn't one this app can read (" + e.getMessage() + ").");
        } finally {
            db.close();
        }
        return check;
    }

    /** The point of no return: everything before this only read the backup. */
    private static String replace(Context app, File staging, File stagedDb, JSONObject manifest,
                                  Map<String, Object> settings) throws IOException {
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

    /**
     * Turns the backup's settings into typed values, or refuses the backup. Runs before
     * anything is replaced, so damaged settings can't leave the backup's database paired with
     * this tablet's old settings.
     */
    private static Map<String, Object> decodeSettings(JSONObject settings) throws IOException {
        Map<String, Object> decoded = new java.util.LinkedHashMap<>();
        try {
            JSONArray names = settings.names();
            for (int i = 0; names != null && i < names.length(); i++) {
                String key = names.getString(i);
                JSONObject item = settings.getJSONObject(key);
                switch (item.getString("t")) {
                    case "b": decoded.put(key, item.getBoolean("v")); break;
                    case "i": decoded.put(key, item.getInt("v")); break;
                    case "l": decoded.put(key, item.getLong("v")); break;
                    case "f": decoded.put(key, (float) item.getDouble("v")); break;
                    case "s": decoded.put(key, item.getString("v")); break;
                    case "ss": {
                        Set<String> set = new java.util.HashSet<>();
                        JSONArray values = item.getJSONArray("v");
                        for (int j = 0; j < values.length(); j++) set.add(values.getString(j));
                        decoded.put(key, set);
                        break;
                    }
                    default: throw new IOException("The backup's settings are damaged (unknown kind for " + key + ").");
                }
            }
        } catch (JSONException e) {
            throw new IOException("The backup's settings are damaged.");
        }
        return decoded;
    }

    @SuppressWarnings("unchecked")
    private static int applySettings(Context app, Map<String, Object> settings) throws IOException {
        SharedPreferences.Editor edit = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear();
        for (Map.Entry<String, Object> e : settings.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Boolean) edit.putBoolean(e.getKey(), (Boolean) v);
            else if (v instanceof Integer) edit.putInt(e.getKey(), (Integer) v);
            else if (v instanceof Long) edit.putLong(e.getKey(), (Long) v);
            else if (v instanceof Float) edit.putFloat(e.getKey(), (Float) v);
            else if (v instanceof String) edit.putString(e.getKey(), (String) v);
            else edit.putStringSet(e.getKey(), (Set<String>) v);
        }
        if (!edit.commit()) throw new IOException("Couldn't save the restored settings.");
        return settings.size();
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
