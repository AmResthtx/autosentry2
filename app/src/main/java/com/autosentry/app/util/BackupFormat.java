package com.autosentry.app.util;

/**
 * The rules for what a backup zip may hold, kept free of Android so they are unit tested.
 * A backup is: manifest.json, settings.json, db/autosentry.db (plus -wal), and files/...
 * for photos and saved reports.
 */
public final class BackupFormat {
    public static final int FORMAT = 1;
    public static final String MANIFEST = "manifest.json";
    public static final String SETTINGS = "settings.json";
    public static final String DB_DIR = "db/";
    public static final String FILES_DIR = "files/";
    /** Folders under the app's files directory that go into a backup. Logs are left out. */
    public static final String[] FILE_FOLDERS = {"maintenance_photos", "reports", "livelogs"};

    private BackupFormat() {}

    /**
     * True when a zip entry may be written during a restore. Anything that could land outside
     * the folders above (.., absolute paths, backslashes, unknown folders) is refused, so a
     * tampered zip can't overwrite other app files.
     */
    public static boolean isSafeEntry(String name, String dbName) {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.contains("\\")
                || name.contains("..") || name.indexOf('\0') >= 0) {
            return false;
        }
        if (name.equals(MANIFEST) || name.equals(SETTINGS)) return true;
        if (name.equals(DB_DIR + dbName) || name.equals(DB_DIR + dbName + "-wal")) return true;
        for (String folder : FILE_FOLDERS) {
            String prefix = FILES_DIR + folder + "/";
            if (name.startsWith(prefix) && name.length() > prefix.length()
                    && name.indexOf('/', prefix.length()) < 0) {
                return true;
            }
        }
        return false;
    }

    /** Why a backup can't be restored into this build, in plain English; null when it can. */
    public static String refusal(int format, int backupDbVersion, int appDbVersion) {
        if (format != FORMAT) {
            return "This backup was made by a newer AutoSentry (backup format " + format
                    + "). Update the app, then restore.";
        }
        if (backupDbVersion > appDbVersion) {
            return "This backup holds a newer database (version " + backupDbVersion + ") than this app can read (version "
                    + appDbVersion + "). Update the app, then restore.";
        }
        if (backupDbVersion < 1) return "This file isn't an AutoSentry backup.";
        return null;
    }
}
