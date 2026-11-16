package com.autosentry.app.data;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.room.migration.Migration;

import org.junit.Test;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * An update must never lose the owner's trips and service history. Room keeps it only when
 * there's a Migration for every version step; a missing one crashes the app on launch, and
 * reinstalling to recover wipes everything.
 */
public class DatabaseMigrationsTest {
    // Unit tests run from the app module directory.
    private static final File SCHEMAS = new File("schemas/com.autosentry.app.data.AppDatabase");

    @Test
    public void everySchemaVersionHasAMigrationFromTheOneBefore() {
        int latest = latestSchemaVersion();
        Set<String> steps = new HashSet<>();
        for (Migration m : AppDatabase.MIGRATIONS) steps.add(m.startVersion + "->" + m.endVersion);
        for (int v = 1; v < latest; v++) {
            String step = v + "->" + (v + 1);
            assertTrue("No Migration " + step + " in AppDatabase.MIGRATIONS", steps.contains(step));
        }
    }

    @Test
    public void migrationsOnlyMoveForwardOneVersion() {
        for (Migration m : AppDatabase.MIGRATIONS) {
            assertTrue("Migration " + m.startVersion + "->" + m.endVersion + " should step one version",
                    m.endVersion == m.startVersion + 1);
        }
    }

    private static int latestSchemaVersion() {
        File[] files = SCHEMAS.listFiles((dir, name) -> name.matches("\\d+\\.json"));
        assertFalse("No exported schemas in " + SCHEMAS.getAbsolutePath(), files == null || files.length == 0);
        int latest = 0;
        for (File f : files) latest = Math.max(latest, Integer.parseInt(f.getName().replace(".json", "")));
        return latest;
    }
}
