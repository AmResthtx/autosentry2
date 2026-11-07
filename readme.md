# AutoSentry

**A local-first, hands-off ownership record for the 2000 Ford F-250 7.3L Power Stroke.**

Set it up once. After setup, AutoSentry connects, records trips, and monitors
live data automatically. You do not have to remember to open an app before
driving. Review the trip and truck data later, with the truck off.

AutoSentry is built to catch problems before they become expensive repairs. It
tracks maintenance intervals so you do not have to, and shows parts for
scheduled service that is coming due.

Your vehicle data stays on your device by default. The app currently has no
Android `INTERNET` permission.

## Contents

- [Privacy (local-first)](#privacy-local-first)
- [What AutoSentry does](#what-autosentry-does)
- [Computed MPG](#computed-mpg)
- [Unavailable PIDs](#unavailable-pids)
- [Data labels](#data-labels)
- [Free plan, paid add-on, and deals](#free-plan-paid-add-on-and-deals)
- [Supporting the project](#supporting-the-project)
- [Roadmap](#roadmap)
- [Honest status](#honest-status)
- [How it works](#how-it-works)
- [Building](#building)

## Privacy (local-first)

AutoSentry stores vehicle and service data on the device. Monitoring does not
require an account or cloud service, and the app currently has no Android
`INTERNET` permission.

Parts offers appear on the optional Deals tab, and links open in your browser.
Any future network feature, including anonymous PID-coverage sharing, will be
opt-in and off by default. The privacy information will be updated before any
such feature ships.

## What AutoSentry does

- **Tracks trips automatically.** After setup, AutoSentry checks for the truck,
  connects to the paired adapter, records the trip, and monitors live data.
  You do not need to open the app before each drive.
- **Helps catch problems early.** The dashboard shows available live readings.
  The Checks tab includes trouble-code reads and a read-only Truck Systems scan.
- **Tracks maintenance.** The service schedule and alerts help keep maintenance
  from slipping through the cracks. The service log keeps local records.
- **Builds an ownership record.** Trip and service records help document care
  of this specific truck over time.
- **Shows parts for service.** The Deals tab matches offers to scheduled jobs
  that are due soon or overdue. It does not use trouble codes to choose offers.

AutoSentry is designed around the 2000 F-250 7.3L Power Stroke. Truck and
adapter responses vary, so readings are identified by what the truck actually
answers rather than assumed to be available.

## Computed MPG

`Instant MPG` and trip MPG are calculated by AutoSentry. They are not read
directly from the truck as an OBD PID.

The 7.3L has no MAF sensor and probably does not answer standard fuel-rate PID
`0x5E`. MPG works with the simulator, but it is not available on the real
truck yet. Verified Ford fuel data is needed first. The experimental MFDES PID
from issue [#28](https://github.com/AmResthtx/autosentry2/issues/28) is not
verified fuel data.

For the real truck, AutoSentry shows **“needs Ford fuel data, coming later”**,
not a made-up MPG number.

## Unavailable PIDs

AutoSentry discovers supported standard PIDs dynamically when it connects.
Different trucks may answer different requests.

- **Not supported by this truck:** The truck refused the support request with
  NRC `12`, or did not answer during the support probe. The app labels the PID
  instead of treating it as a working reading.
- **`--`:** A supported PID had no usable reading at that moment. This is a
  temporary missing value, not proof that the truck does not support it.
- **Unverified:** An experimental Ford PID that has not been confirmed on the
  truck. It is labeled as unverified and does not drive alerts or severity.

## Data labels

- **Measured:** Read from the truck or a device sensor.
- **Recorded:** Saved from a trip, service entry, or other event.
- **Calculated:** Derived by AutoSentry from available readings or records.
- **Recommended:** A maintenance interval or other suggested action.
- **Unavailable:** No usable, verified value is available.

## Free plan, paid add-on, and deals

### Free plan

The free plan includes:

- Automatic trip tracking
- Live dashboard
- Trouble codes and Truck Systems scan
- Service schedule and alerts
- Service log
- Local backup and export

### Paid add-on

The resale report is a separate paid add-on, on top of the free plan. It is
intended as a one-time purchase, not a subscription. The report and purchase
feature are not available yet.

### Parts deals

The Deals tab is optional: you choose whether to view offers or open their
links. Offers appear only under a scheduled service item that is due soon or
overdue, never next to trouble codes. Paid placements are labeled **Sponsored**;
commission links are labeled **Affiliate link**. Links open in the browser, not
inside AutoSentry.

## Supporting the project

If AutoSentry is useful to you, you are invited to support the project with
feedback, testing, and word of mouth. The long-term direction is for
supplier-funded placements to help reduce or remove costs for owners. That is
a goal, not a promise that the app will be free forever.

## Roadmap

Priorities:

1. **Data-safe updates and local backup.** Protect local records as the app is
   updated and make backups dependable.
2. **Complete maintenance alerts.** Alert on every scheduled item by miles,
   engine hours, and time.
3. **Codes verified on the truck.** Reads are retried, and failed reads are
   reported as failures; show “no codes” only when the truck answered. Verify
   this behavior on the 2000 F-250 7.3L.

## Honest status

Trouble-code reads and clear-codes have not been tested on the truck yet.
Although failed reads are retried and reported as failures, verify these
features on a real 2000 F-250 7.3L before relying on them. Experimental Ford
PIDs, including MFDES, are also unverified.

## How it works

AutoSentry uses a paired Bluetooth OBD adapter and runs local tracking on the
device. When automatic tracking is enabled, it checks for the truck in the
background and records a trip when the engine is running. The dashboard shows
readings the truck supports; the Truck Systems scan reports what responds.
The scan is read-only.

The app includes a setup wizard for permissions, adapter selection, truck
connection, vehicle information, and dashboard readings. Adapter and truck
behavior can vary; an unanswered request is not reported as a successful
reading.

## Truck Systems scan (read-only)

Open **Truck Systems** in the app and tap Run Scan. By default the scan only listens for 30 seconds and sends the truck nothing. Tick *Send read requests* to also ask the truck (a few minutes). A read-only lock (`ReadOnlyGuard`) sits on the adapter's single write point for the whole scan, and the report lists every request that went out. Each report is saved to Downloads/AutoSentry as text and JSON. The VIN is hidden unless *Show the VIN* is ticked.

## Building

```bash
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`. Local
and pull-request builds use the debug signing key unless release-signing
credentials are configured.
