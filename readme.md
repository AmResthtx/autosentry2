# AutoSentry

AutoSentry turns a phone or tablet and a Bluetooth OBD-II adapter into a vehicle-aware diagnostic monitor, a mileage-and-hours-based maintenance tracker, and a real-time trip computer — built first for a 2000 Ford F-250 7.3L Power Stroke, but designed to support other vehicles through configurable PID profiles.

The whole point is zero change to the driver's habits. Pair the OBD adapter once. The adapter remains asleep while the truck is off, wakes when the key is turned to the **ON** position, and drops its connection when the truck shuts off. An adapter never opens the Bluetooth link itself, so AutoSentry keeps a background monitor running that retries the adapter every few seconds; the trip is picked up within seconds of key-on whether or not anyone opens the app or touches the tablet.

## What it does today

- **Vehicle data monitoring** — live PIDs, DTC reading (stored/pending/permanent), and a vehicle-specific diagnostic data layer. Default signals are selected for the 7.3L Power Stroke, while the PID system is intended to allow users to add, remove, and customize monitored values.
- **7.3L thermal monitoring** — this truck's important engine-temperature signal is engine oil temperature, not a generic coolant-temperature assumption. Ford-enhanced PID support is used where available, and unavailable signals are reported honestly rather than replaced with guesses.
- **Oil life that actually moves** — degrades in real time from accumulated mileage against the 7.3L service schedule (3,000 mi severe / 5,000 mi normal), with engine hours, idling, and engine oil temperature used to identify additional severe-duty conditions. The app treats mileage and engine hours as separate usage measurements; it does not invent an unsupported factory engine-hour interval.
- **Service reminders** — oil and filter, fuel filter, air filter, transmission service, transfer case, front/rear differential, coolant service, lubrication, inspections, and common age- or mileage-based wear items can be tracked. Notifications identify approaching, due, and overdue service.
- **GPS-based trip computer** — speed, distance, and real-time MPG computed from MAF airflow and GPS speed. Every automatically detected trip is logged with distance, fuel used, and average MPG.
- **Automatic key-on tracking** — full-power monitoring (CPU awake, 4 polls a second, GPS, 1-second reconnects) runs only while the engine is turning and a trip is open. With the truck off the monitor drops to low power: adapter link closed so it can sleep, no GPS, no wake lock, and an exact alarm wakes the tablet every 15 seconds for one quick key-on check (unlocking the tablet or plugging in power checks at once). A trip starts within seconds of key-on even if the app was never opened. It restarts itself after a reboot, an app update, or a system kill (15-minute watchdog). A dropped link mid-drive is retried immediately and the gap is filled from GPS when location is allowed; the trip closes once the truck has been off (or the adapter silent) for 2 minutes. Only typing STOP or turning auto-tracking off keeps it down.
- **Connection test** — Account > Test OBD Connection checks background-start permission, location, Bluetooth, the adapter link, adapter ID, OBD-port voltage, whether the truck computer answers, and live RPM/speed/oil temp, and says which step failed.
- **Key-on check (KOEO)** — every key cycle, before cranking, AutoSentry snapshots battery voltage at the OBD port, every standard reading the truck answers (plus the 7.3L's Ford oil temp), and stored and pending trouble codes. It then times the crank (RPM above 0 but under 500), counts tries, and records the lowest cranking voltage and whether the engine started. Reports are on the Diagnostics tab; **Run Key-On Check** takes one on demand with the key ON and the engine off. Ford's commanded self-tests (on-demand KOEO self-test, injector buzz test, glow plug test) are not sent: their 7.3L request bytes aren't verified here, and the buzz test energizes injectors.
- **Pick your readings** — Dashboard > Choose Readings (PIDs) lists every supported reading; ones the truck hasn't confirmed are labeled instead of hidden.
- **Ford PID discovery** — `tools/obd_probe.py --forscan <file>` tries every 4-digit Mode 22 PID found in a FORScan export, and `--sweep 1000 1500` brute-forces a hex range; answering PIDs are logged with raw bytes so scaling can be matched against FORScan's on-screen value.
- **Honest data status** — connection loss, missing PID responses, stale readings, unsupported vehicle data, and degraded monitoring are surfaced to the user. AutoSentry does not silently present simulator data as real vehicle data.
- **Maintenance history with evidence** — users enter maintenance they performed and may attach receipts, parts photos, or installation photos. Records are labeled according to their evidence rather than being treated as verified merely because the user entered them.
- **On-device debug log** — crashes and errors are written to a local log that can be viewed and shared from the app.

## Setup wizard

First launch (and Account > Setup Wizard) walks through setup in phases. Phase 1 gets the app talking to the truck: permissions and background start, choosing the paired OBD adapter, running the truck connection test, choosing duty cycle and odometer, and picking the dashboard readings. Each step saves as you go. Later phases ship as further wizards; `AppSettings.getSetupPhaseDone` records how far you got.

## 2000 F-250 7.3L maintenance model

The maintenance schedule is a starting point, not a substitute for the truck's owner's manual, diesel supplement, service manual, component instructions, or a qualified inspection. Exact equipment, drivetrain, axle, transmission, operating environment, and prior repairs matter.

### Every oil service / regular inspection

- Change engine oil and filter at the selected normal or severe-duty interval.
- Inspect for oil, coolant, fuel, transmission, transfer-case, differential, power-steering, and brake-fluid leaks.
- Inspect radiator, heater, turbo/intercooler, and other engine hoses for cracks, swelling, softness, abrasion, and leaks.
- Inspect the serpentine belt, tensioner, pulleys, and wiring.
- Inspect battery terminals, cables, grounds, charging behavior, and starting performance.
- Inspect steering, suspension, wheel ends, brakes, tires, exhaust, and driveline components.
- Lubricate every serviceable grease fitting found on the specific truck, including steering linkage/ball-joint fittings and driveshaft slip-yoke or U-joint fittings where equipped. Some replacement parts are sealed, so the app must not claim a grease point exists unless the user or vehicle profile confirms it.

Grease matters because a fitting supplies lubricant to a serviceable joint or bearing. A dry steering joint can develop play and affect control; a dry U-joint or slip yoke can cause vibration, wear, and driveline failure. Excess grease should be wiped away because it attracts dirt and can damage seals.

### Mileage- and usage-based items

- **Engine oil and filter:** use the selected service interval; severe use includes towing, extended idling, heavy loads, dusty/off-road operation, low-speed work, and extreme temperatures.
- **Fuel filter:** replace at the scheduled interval and sooner when contaminated fuel, water, restriction, or fuel-delivery symptoms are found. Clean fuel is especially important to diesel injection components.
- **Air filter:** inspect regularly and replace when restricted, damaged, or loaded with dust. A restricted filter can reduce airflow and affect performance.
- **Automatic transmission fluid and filter:** service according to transmission, fluid specification, and normal/severe use. Towing and heat increase fluid stress.
- **Transfer-case fluid:** service if equipped and use the correct fluid for that transfer case.
- **Front and rear differential fluid:** inspect for leaks and water contamination; service more often after towing, deep water, or severe duty.
- **Coolant system:** use the correct diesel-compatible coolant and maintenance method. Check level, concentration or additive requirements, hoses, cap, radiator, and evidence of contamination or electrolysis.
- **Tires:** check pressure, tread, sidewalls, age, load rating, and uneven wear. Rotate according to tire and drivetrain requirements, and investigate alignment or suspension problems instead of simply rotating around them.
- **Brakes:** inspect pad/shoe thickness, rotors/drums, calipers, hardware, hoses, lines, parking brake, and fluid. Brake replacement is condition-based, not safely determined by mileage alone.

### Age-based items

Vehicle age increases the chance of deterioration even when mileage is low. A VIN/build date can establish vehicle age, but it cannot prove the condition of a part. The app should use age to create an inspection or replacement reminder, not claim that a component has failed.

Pay particular attention to:

- radiator and heater hoses
- turbo/intercooler hoses and clamps
- serpentine belt, tensioner, and pulleys
- batteries and cables
- coolant, brake, transmission, transfer-case, and differential fluids
- rubber steering, suspension, and driveline components
- seals, boots, wiring insulation, and corrosion-prone areas
- tires, including date code and sidewall condition

Aged hoses and belts can fail suddenly. A preventive inspection lets the owner find swelling, hardening, cracking, abrasion, or oil contamination before a roadside failure. The app should explain whether a reminder is based on age, mileage, engine hours, a measured PID trend, or a user-entered maintenance record.

### Why the app distinguishes facts from recommendations

AutoSentry separates:

- **Measured:** a value returned by the vehicle or adapter, with timestamp and trip context.
- **Recorded:** a service event entered by the owner, with optional receipt or photo evidence.
- **Calculated:** mileage, engine hours, service-life percentage, or trend derived from stored data.
- **Recommended:** an inspection, service, part, or provider suggested because a documented threshold has been approached.
- **Unavailable:** a PID or vehicle signal that did not return valid data.

This keeps the maintenance notebook useful without pretending that a mileage estimate, a VIN age calculation, or a single PID reading is proof of component condition.

## Architecture

```
obd/            ELM327Adapter, DTCReader, VINDetector
                — adapter communication, vehicle responses, and code decoding
engine/         OilLifeEngine — mileage, engine-hour, idle, and oil-temperature logic
maintenance/    ServiceInterval, ServiceStatus, MaintenanceScheduleEngine
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

## Planned directions

- Configurable PID catalog with vehicle-specific defaults and user-selected signals.
- Ford commanded KOEO tests (on-demand self-test, injector buzz, glow plug) once their 7.3L requests are verified on the truck.
- PID availability, stale-data, and drift detection with evidence-backed notifications.
- Receipt capture and clearer maintenance evidence states.
- Vehicle-specific maintenance profiles for additional makes, models, engines, and drivetrains.
- Opt-in connections to parts suppliers, mobile mechanics, and repair shops when a documented service interval creates a high-intent maintenance need.
