# Updating AutoSentry without losing data

Short version: install the new APK over the old one. Trips, maintenance records, photos,
settings and the paired adapter all stay. Tracking restarts by itself.

## What has to be true for an update to keep data

Android keeps an app's data through an update only when all three hold. If any fails,
Android refuses the install and offers "uninstall first", and uninstalling erases the data.

| Rule | How AutoSentry meets it |
|---|---|
| Same app ID | Always `com.autosentry.app`. |
| Same signing key | Every APK built by GitHub Actions (releases, and the debug APK on every pull request) is signed with one persistent key. |
| Build number not lower than the installed one | The build number is the GitHub Actions run number, which only goes up. |

The app's Info button (the "i" in the dashboard toolbar) shows the version, the build number,
and whether the build carries the persistent key or a throwaway test key.

## What was wrong before

- **Pull-request builds used a throwaway key.** Only master releases were signed with the
  persistent key. A pull-request (debug) build was signed with a debug key that GitHub's
  build machine makes fresh on every run. So a pull-request build could never install over a
  release, or over another pull-request build, without an uninstall.
- **A release built without the key fell back to the debug key.** `app/build.gradle.kts`
  did this silently. Such an APK installs once and can then never be updated. A release
  without the key is now left unsigned, which Android won't install at all.
- **Pull-request runs kept no APK.** There was nothing to download from the run.
- The database had no destructive fallback (confirmed), so the database itself was not the
  cause. Its layout is now also exported and checked in CI so it stays that way.

## One-time switch (only if the installed app has a different key)

If the tablet has a pull-request or locally built APK installed from before this change,
its key differs from the persistent one. Android will refuse the update once. Do this once:

1. Open the installed app. Account tab, **Back up everything**. This needs a build that has
   the button. If the installed build is older, use **Save trips to tablet** for the trips;
   maintenance records and photos from that old build can't be carried over.
2. Copy the backup zip from `Downloads/AutoSentry` off the tablet (USB or cloud), in case.
3. Uninstall AutoSentry.
4. Install the new APK.
5. Account tab, **Restore from a backup**, pick the zip. The app closes; open it again.
6. Re-grant the permissions the setup wizard asks for. Android forgets them on uninstall.

After that, every later APK from GitHub installs straight over the top.

If the tablet already runs a release APK from the Releases page (v0.1.0-buildN), no switch
is needed: those were already signed with the persistent key.

## What survives an update, and where it lives

| Data | Where | Survives update | In a backup |
|---|---|---|---|
| Trips, odometer, oil life, maintenance, key-on reports | Room database `autosentry.db` | Yes | Yes |
| Paired adapter, dashboard choice, setup-wizard progress, alert state, last code scan, codes saved before each clear | Settings file `autosentry_settings` | Yes | Yes |
| Maintenance photos | App files, `maintenance_photos/` | Yes | Yes |
| Systems scan reports, live-data recordings | App files, `reports/`, `livelogs/` | Yes | Yes |
| Debug log | App files, `logs/` | Yes | No |
| Reports and CSV files you saved | `Downloads/AutoSentry` on the tablet | Yes, and survive an uninstall too | They already are files |
| Permissions (Bluetooth, location, notifications) | Android | Yes | No, re-grant after a reinstall |

Android's own cloud backup is off for this app (`allowBackup="false"`), so an uninstall
erases everything in the first five rows. That is why the backup button exists.

## Tracking after an update

Installing an update stops the running app. Android then sends `MY_PACKAGE_REPLACED`;
`BootReceiver` gets it and starts the tracking service again (unless you typed STOP or
turned auto-tracking off). Starting the app process also re-arms the 15-minute watchdog. The
debug log gets a line: `App updated to build N; restarting tracking`.

Not confirmed on the tablet: some manufacturers' battery savers block this background
start. If the log shows "Android blocked tracking start", use Account, **Allow background
start**. Opening the app always starts tracking.

## Database changes

`AppDatabase` is at version 4 with migrations 1 to 2, 2 to 3 and 3 to 4, and no destructive
fallback: a layout it can't migrate makes the app stop with an error instead of erasing
history.

- The layout is exported to `app/schemas/` on every build. CI fails if it differs from the
  committed file. That catches an entity change made without a version bump.
- `MigrationTest` builds a version 1, 2 and 3 database with rows in it, opens each with the
  current code, and checks the rows.
- To change the layout: bump `AppDatabase.VERSION`, add a `Migration`, add a case to
  `MigrationTest`, and commit the new file CI prints under `app/schemas/`.

## For whoever maintains the build

- The key is in four repository secrets: `AUTOSENTRY_RELEASE_KEYSTORE_BASE64`,
  `AUTOSENTRY_RELEASE_STORE_PASSWORD`, `AUTOSENTRY_RELEASE_KEY_ALIAS`,
  `AUTOSENTRY_RELEASE_KEY_PASSWORD`. Never commit the keystore or passwords. Keep an offline
  copy of the keystore: if it is lost, no future build can update an installed app.
- Each run's summary page shows the APK's signing-key fingerprint (SHA-256). Two APKs update
  each other only when it matches.
- Pull requests from forks can't read the secrets. They still build, with a test key, and
  the APK is named `...-TESTKEY-debug.apk`.
- Don't rename or recreate `.github/workflows/build.yml`: GitHub would restart the run
  number at 1. The workflow refuses to publish a release whose build number isn't above
  the last release's. If the number ever must restart, set `AUTOSENTRY_VERSION_CODE` in the
  workflow to a number above the last release.
- Artifacts from a run are downloaded as a zip (GitHub does that). Unzip, then tap the APK.
  They are kept 30 days. Master releases attach the APK directly, with no zip.
