# AutoSentry

AutoSentry turns a phone or tablet and a cheap ELM327/OBDLink Bluetooth adapter
into a ForScan-style diagnostic tool, a mileage-accurate maintenance tracker,
and a real-time MPG/trip computer — built first for a 2000 Ford F-250 7.3L
Power Stroke, but not tied to it.

The whole point is zero babysitting. Pair the OBD adapter once. After that,
walking up to the truck with the phone in your pocket is the only "action" —
the adapter connects, tracking starts, and it stops itself when you walk away.

## What it does today

- **OBD-II / diagnostics** — live PIDs (RPM, coolant temp, MAF), DTC reading
  (stored/pending/permanent), with a 7.3L Power Stroke-specific code
  dictionary. Runs against a simulator when no adapter is attached, so the
  app is testable without hardware.
- **Oil life that actually moves** — degrades in real time from accumulated
  mileage against Ford's own published 7.3L intervals (3,000 mi severe /
  5,000 mi normal), with a severe-duty multiplier for heavy idling or
  overheating. This was the reason the app got rebuilt: the old version set
  oil life once and never touched it again.
- **Full manufacturer service schedule** — oil, fuel filter, air filter,
  transmission fluid, transfer case, front/rear differential, and coolant,
  each tracked against Ford's Normal vs. Severe Service intervals. Fires a
  notification the moment any item crosses 80% of its service life, well
  before it's actually due.
- **GPS-based trip computer** — speed, distance, and real-time MPG computed
  from MAF airflow + GPS speed (diesel AFR/density constants), not a rough
  estimate. Every trip is logged with distance, fuel used, and average MPG.
- **Hands-off tracking** — pair the adapter once; a manifest-registered
  Bluetooth receiver starts/stops tracking automatically on connect/disconnect,
  even if the app was never manually opened.
- **On-device debug log** — since this runs on a tablet permanently mounted
  in the truck, not tethered to a laptop, crashes and errors write to a local
  log viewable and shareable straight from the app.

## Architecture

```
obd/            ELM327Adapter (real hardware), OBDSimulator (dev/testing),
                 DTCReader, VINDetector — the ForScan-equivalent core
engine/          OilLifeEngine — mileage-based degradation
maintenance/     ServiceInterval, ServiceStatus, MaintenanceScheduleEngine
                 — Ford's manufacturer intervals + %-of-life tracking
fuel/            MpgCalculator — MAF + GPS speed -> instantaneous/interval MPG
gps/             GpsTracker — LocationManager wrapper, no Play Services dep
service/         TrackingService — foreground service tying OBD + GPS +
                 persistence + notifications together every poll tick
receiver/        BluetoothConnectionReceiver — auto start/stop on adapter
                 connect/disconnect
data/            Room entities/DAOs (PIDs, sessions, trip points, vehicle
                 profile, maintenance events)
ui/              MainActivity (dashboard), DebugLogActivity
```

All storage is local (Room + SharedPreferences). No network calls, no
account, no cloud dependency — it works the same with or without signal.

## Building

```bash
./gradlew assembleRelease
```

Outputs to `app/build/outputs/apk/release/app-release.apk`, signed with the
Android debug key (fine for sideloading onto your own device; not intended
for Play Store distribution as-is).

## Where this could go

The current app is a single-vehicle tool built to solve one truck's problem.
A few directions worth considering if it grows past that — none of these are
committed, just live options:

- **Parts seller partnerships.** The app already knows exactly what's due
  and when (80%-of-life alerts, per-item mileage remaining). That's a
  natural point to surface "order the parts now" — affiliate links or direct
  integration with parts sellers, from small regional diesel shops to
  larger retailers, timed to when the user is actually about to need
  something rather than generic ads.
- **Dash cam partnership — the evidence layer.** A bundled dash cam tied to
  the app isn't just an accessory; it's the thing that makes the insurance
  angle below actually work. Recorded video is close to undeniable in a
  claim: it clears the driver outright when they weren't at fault, and it
  ends the argument fast when they were. Either way, it collapses the part
  of a claim that costs insurers the most — the drawn-out
  he-said/she-said/lawyer-said dispute over fault. A fleet where every
  driver has a camera rolling is a fleet with dramatically fewer contested
  claims, which is a real, defensible cost advantage, not just a marketing
  angle.
- **Usage-based insurance, potentially through a captive insurer.** The app
  already collects real driving behavior, mileage, and maintenance
  compliance; the dash cam adds undeniable fault evidence on top of that.
  Combined, that's a materially better risk pool than an insurer underwriting
  blind — good enough that the rate doesn't need to dramatically undercut
  competitors to be profitable, just noticeably beat them, because claims
  cost less to resolve and fewer are fraudulent or disputed. Verified safety
  upgrades (e.g. a documented aftermarket bumper) could stack as additional
  discounts on top, the same way they'd lower risk in the real world. This
  is the most complex path by far — insurance is a heavily regulated,
  state-by-state business — and would need real actuarial and legal
  expertise before it's anything more than an idea, but the dash cam is
  what makes the economics plausible in the first place.
- **Community/fleet angle.** The per-vehicle architecture (VehicleProfile,
  ServiceInterval tables) generalizes past one F-250 — other makes/models
  just need their own interval tables and PID sets. A shared, crowd-sourced
  library of manufacturer intervals and known-good PID definitions (instead
  of every user having to look theirs up manually, the way this README's
  numbers were sourced) is the more grounded, near-term version of "this
  becomes more than one truck's app."

None of the above changes anything about how the app works today — it's
still a fully local, no-account, no-tracking-of-you tool. Any future
direction that involves sharing data externally (insurance, parts ordering,
fleet sync) would be opt-in, not a default.
