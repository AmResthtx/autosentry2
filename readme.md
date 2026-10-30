# AutoSentry

built for the 7.3L Power Stroke.**

Pair an OBDLink adapter once. From then on, every time the key turns and the
wait-to-start light comes on, AutoSentry wakes up and records the truck.
It tracks mileage and engine hours and warns you before maintenance is due.
When a service comes due, it shows you the parts for that job. It also keeps
a service history, backed by receipts, that you can show a buyer when you
sell the truck.

---

## The problem

- 7.3L trucks are 20+ years old and sell for close to what they cost new.
  Owners keep them running with strict, disciplined maintenance.
- Service records live in memory, shoeboxes, and spreadsheets. CARFAX only
  sees work done by dealers and shops, so driveway maintenance and upgrades
  never show up.
- OBD apps get opened once and forgotten. When the truck acts up, nobody
  was recording.

## The solution

### Zero friction
- Connect the adapter once. After that, key on means monitoring on.
  There's no app to open and no trip to start.
- Recording starts at key-on, so cranking and hard-start data is already
  saved when a crank/no-start happens.
- When something sounds off, the owner opens the app and nothing is missing.

### Dashboard
- Live readings the owner chooses as important to them.
- Engine hour and mileage tracking.

### Maintenance tracking
- On first setup, the owner estimates when each item was last serviced.
  Every item is tracked from day one, and the estimates are labeled as estimates.
- Every maintenance category tracks its own miles, engine hours, and time
  since last service. Whichever limit comes first sets the due date.
- Oil is the most frequent item, not a special case.
- Fuel filter: every other oil change while running the stock filter.
- Alerts fire before service is due, early enough to order parts and have
  them arrive in time.

### Verified history
- Log maintenance and upgrades with receipts and photos.
- Each record shows how it was verified:
  - Bought through AutoSentry: confirmed by order data
  - Receipt matched to the owner (e.g., last 4 of the card)
  - Receipt plus photo of the installed part
  - Receipt attached, not matched (gifted parts)
  - Estimated / self-reported
- Resale report: a CARFAX-style report for your truck, including DIY work,
  backed by miles and hours the app recorded itself.

## Business model

- **Supplier placement (primary):** parts suppliers pay to be in front of
  owners at the moment a service comes due. These are high-intent buyers:
  maintenance is a necessity to keep the truck on the road, not an
  impulse buy.
- **Affiliate commission** on parts bought through the app.
- **Resale report:** a one-time purchase when the owner sells.
- **Trust rule:** only recommend quality parts at a better price, and label
  sponsored placements. One bad part recommendation costs the community's
  trust for good.

## Why the 7.3L first

- Owners are loyal to proper maintenance and keep these trucks for life.
- They already think in engine hours and miles, and already keep records.
- The community is tight: forums, Facebook groups, and YouTube channels,
  where word of mouth moves fast.
- There's no good software for them. FORScan diagnoses but doesn't track
  maintenance. CARFAX doesn't know engine hours or DIY work.
- It's a rebellion against new vehicles: keep the truck you trust running
  forever.

## Why it wins

1. **Zero friction:** it fits into starting the truck. There's nothing to
   remember.
2. **It owns the service history:** months of verified records mean owners
   don't switch.
3. **Nobody else has this data:** miles and engine hours the app measured,
   tied to verified service records.

## Principles

- Show only real data. Measured, recorded, calculated, estimated, and
  unavailable are always labeled as such.
- Alerts must be accurate. The community forgives nothing.

## What we're adding

**Already working:** auto-start at key-on, live dashboard, trip MPG
(simulator only so far; see below), mileage and engine-hour tracking, oil
life, service schedule with due-soon alerts, a maintenance log with photos,
a setup wizard, and a key-on check with crank timing on every start.

**In progress, in priority order:**

1. **Last-service estimates at setup.** Ask the owner when each item was
   last done. Today an item with no record never alerts, so it never shows
   a part.
2. **One system for every item.** Oil moves into the general schedule
   instead of running on its own.
3. **Engine hours and time limits for every item.** Today most items count
   miles only. Engine hours are tracked for oil only, and the month limits
   are set but never checked, so a truck that sits never gets a
   time-based alert.
4. **Fuel filter rule.** Every other oil change on the stock filter, with
   a stock vs. aftermarket filter setting.
5. **Verification level on every record.** From "bought through
   AutoSentry" down to "self-reported."
6. **OBDLink recommendation in setup.** MX+ or LX. The app uses classic
   Bluetooth, and the CX is Bluetooth Low Energy only, so it won't connect.
7. **Fault codes on the Checks tab.** Built, not yet tested on the truck:
   stored and pending codes read at every connect, a clear-codes button
   (key on, engine off only), and 206 7.3L codes, each with its severity
   and what to check shown right under it. A read that gets no real
   answer is retried, up to 3 tries per mode, and if it still fails the
   tab says "Couldn't read trouble codes" with the reason and a Try Again
   button, never "no codes". Clear Codes only works after a read that
   succeeded, and the codes from just before each clear are kept on the
   tab. The Truck Systems scan (Checks tab) reports every module and PID
   this truck answers.
8. **Deals tied to each service alert.** The right parts for the job
   that's coming due.
9. **Resale report export.** A PDF of the verified service history.
10. **Opt-in anonymous data sharing.** Needed to show suppliers how many
    trucks are coming due for each part. The app keeps all data on the
    phone today.
11. **Real MPG on the 7.3L.** The truck has no MAF sensor and likely doesn't
    answer the standard fuel-rate PID, so MPG needs Ford's fuel-quantity
    PID (MFDES, experimental in the PID catalog) instead.

## Roadmap

- Other makes, models, and engines through configurable vehicle profiles.
- Direct supplier partnerships once the numbers show demand.

---

## How it works

Landing page: https://amresthtx.github.io/autosentry2/ (source in `site/`, published by `.github/workflows/pages.yml`).

- **Automatic key-on tracking** — full-power monitoring (CPU awake, 4 polls a second, GPS, 1-second reconnects) runs only while the engine is turning and a trip is open. With the truck off the monitor drops to low power: adapter link closed so it can sleep, no GPS, no wake lock, and an exact alarm wakes the tablet every 15 seconds for one quick key-on check (unlocking the tablet or plugging in power checks at once). A trip starts within seconds of key-on even if the app was never opened. It restarts itself after a reboot, an app update, or a system kill (15-minute watchdog). A dropped link mid-drive is retried immediately and the gap is filled from GPS when location is allowed; the trip closes once the truck has been off (or the adapter silent) for 2 minutes. Only typing STOP or turning auto-tracking off keeps it down.
- **Connection test** — Account > Test OBD Connection checks background-start permission, location, Bluetooth, the adapter link, adapter ID, OBD-port voltage, whether the truck computer answers, and live RPM/speed/oil temp, and says which step failed.
- **Key-on check (KOEO)** — every key cycle, before cranking, AutoSentry snapshots battery voltage at the OBD port, every standard reading the truck answers (plus the 7.3L's Ford oil temp), and stored and pending trouble codes. It then times the crank (RPM above 0 but under 500), counts tries, and records the lowest cranking voltage and whether the engine started. Reports are on the Checks tab; **Run Key-On Check** takes one on demand with the key ON and the engine off. Ford's commanded self-tests (on-demand KOEO self-test, injector buzz test, glow plug test) are not sent: their 7.3L request bytes aren't verified here, and the buzz test energizes injectors.
- **Pick your readings** — the list button in the Dashboard toolbar lists every supported reading (about 35, including coolant, load, EGR, torque, injection timing and, on the 7.3L, Ford ICP and IPR); ones the truck hasn't confirmed are labeled instead of hidden. The dashboard has a left menu, a light toolbar, and a grid of dial tiles with a white needle, round-number scale, colored value and the name underneath (the colors are per gauge and don't mean anything; an out-of-range gauge gets an amber or red border). The zoom buttons change how many tiles fit across, and holding a tile moves or removes it.
- **Ford PID discovery** — `tools/obd_probe.py --forscan <file>` tries every 4-digit Mode 22 PID found in a FORScan export, and `--sweep 1000 1500` brute-forces a hex range; answering PIDs are logged with raw bytes so scaling can be matched against FORScan's on-screen value.
- **Trouble codes** — stored and pending codes and the check engine light are read every time the truck starts answering (by the key-on check when the engine is off), and on demand from the Checks tab. Each code shows its 7.3L meaning, a severity and what to check. A read only says "no codes" when the truck really answered that way: each reply is sorted into codes, none, NO DATA, refused (7F) or failed (timeout, adapter or bus error, garbage), and NO DATA and failures are retried up to 3 times, 500 ms apart. NO DATA counts as none only when the same read proved the PCM is answering: Mode 01 PID 01 reporting zero stored codes and the light off for stored codes, or any real PID 01 or Mode 03 reply for pending. Fewer codes than PID 01's count is an incomplete read. A read that still fails shows "Couldn't read trouble codes after 3 tries" with the reason and a Try Again button, is saved that way across restarts, and is recorded as failed on the key-on report. Retries check RPM between tries, so a crank is never held up: if the starter turns mid-read, the read stops, is recorded as "the engine started before codes could be read", and codes are read again once the engine is running. **Clear Codes** (Mode 04) only runs key on, engine off, after a read that succeeded; it reads the codes once more first and keeps that list (and the light state) on the Checks tab, so a clear never loses the evidence.
- **Truck Systems scan** — Checks > Scan Truck Systems reports the adapter, the bus, every module that answers, every standard reading with its live value, and a Mode 22 sweep of the PCM, as a text report you can share. It only reads.

## Setup wizard

First launch (and Account > Setup Wizard) walks through setup in phases. Phase 1 gets the app talking to the truck: permissions and background start, choosing the paired OBD adapter, running the truck connection test, choosing duty cycle and odometer, and picking the dashboard readings. Each step saves as you go. Later phases ship as further wizards; `AppSettings.getSetupPhaseDone` records how far you got.

## Architecture

```
obd/            ELM327Adapter, DTCReader, DtcDictionary, VINDetector, TruckProbe
                — adapter communication, vehicle responses, code decoding,
                the 7.3L trouble-code list, and the Truck Systems scan
engine/         OilLifeEngine — mileage, engine-hour, idle, and oil-temperature logic
maintenance/    ServiceInterval, ServiceStatus, MaintenanceScheduleEngine,
                ServiceAlertPolicy
                — vehicle service intervals and due-soon/overdue tracking
fuel/           MpgCalculator — MAF + GPS speed -> instantaneous/interval MPG
gps/            GpsTracker — LocationManager wrapper, no Play Services dependency
service/        TrackingService — foreground OBD + GPS persistence and notifications
service/        TrackingWatchdogWorker — restarts tracking if the system killed it
receiver/       BluetoothConnectionReceiver, BootReceiver — restart tracking on adapter
                connect, reboot, and app update
data/           Room entities/DAOs for PIDs, sessions, trip points, vehicle profile,
                maintenance events, and photo attachments
ui/             MainActivity, DebugLogActivity, LogMaintenanceActivity,
                MaintenanceHistoryActivity
```

All storage is local (Room + SharedPreferences). No account or cloud dependency is required for the monitoring workflow.

## Truck Systems scan (read-only)

Open **Truck Systems** in the app and tap Run Scan. By default the scan only listens for 30 seconds and sends the truck nothing. Tick *Send read requests* to also ask the truck (a few minutes). A read-only lock (`ReadOnlyGuard`) sits on the adapter's single write point for the whole scan, and the report lists every request that went out. Each report is saved to Downloads/AutoSentry as text and JSON. The VIN is hidden unless *Show the VIN* is ticked.

## Building

```bash
./gradlew assembleRelease
```

Outputs to `app/build/outputs/apk/release/app-release.apk`. Local and pull-request builds use the debug key unless persistent signing credentials are configured; published GitHub release builds require the persistent key described below.

The version and build number show in the title bar (e.g. `v0.1.0 (build 41)`). CI builds use the GitHub Actions run number; local builds are build 1.

### Installing updates without losing data

Android keeps AutoSentry's local data when an APK is installed as an update. Keep the application ID (`com.autosentry.app`) unchanged, install a build with a higher version code, and sign every update with the same key. Do not uninstall AutoSentry or clear its storage.

Published GitHub release APKs use a persistent signing keystore. Create a keystore once and store it securely; add its Base64-encoded contents and credentials as repository Actions secrets named `AUTOSENTRY_RELEASE_KEYSTORE_BASE64`, `AUTOSENTRY_RELEASE_STORE_PASSWORD`, `AUTOSENTRY_RELEASE_KEY_ALIAS`, and `AUTOSENTRY_RELEASE_KEY_PASSWORD`. The workflow requires these secrets for branch-push releases. Pull-request builds may use the debug key and should not be used as release updates.

For example, create a new keystore with `keytool -genkeypair -v -keystore autosentry-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias autosentry`, then Base64-encode the file when adding the secret. Never commit the keystore or its passwords. **If the installed app was signed with a different key, the first update signed by this keystore will be rejected by Android.** Use the same original signing key for the transition; if it is unavailable, back up data before uninstalling because the signing key cannot be changed while preserving Android's app data.
