# AutoSentry

**A local-first, hands-off ownership record for the 2000 Ford F-250 7.3L Power Stroke.**

Set it up once. After setup, AutoSentry connects, records trips, and monitors
live data automatically. You do not have to remember to open an app before
driving. Review the trip and truck data later, with the truck off.

AutoSentry is built to catch problems before they become expensive repairs. It
tracks maintenance intervals so you do not have to, and its long-term plan is
to offer a good deal on the parts for a service that is coming due.

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

Any future network features, including optional anonymous PID-coverage
sharing or parts deals, will be opt-in and off by default. The privacy
information will be updated before any such feature ships.

## What AutoSentry does

- **Tracks trips automatically.** After setup, AutoSentry checks for the truck,
  connects to the paired adapter, records the trip, and monitors live data.
  You can review the saved information later without opening the app before
  each drive.
- **Helps catch problems early.** The dashboard shows available live readings.
  Checks include trouble-code reads and a read-only Truck Systems scan.
- **Tracks maintenance.** The service schedule and alerts help keep maintenance
  from slipping through the cracks. The service log keeps local records.
- **Builds an ownership record.** Trip and service records help document care
  of this specific truck over time.

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
  truck. It is labeled **Unverified** and does not drive alerts or severity.

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
intended as a one-time purchase, not a subscription. It is not part of the free
tier, and the purchase feature is not available yet.

### Parts deals

Parts deals are opt-in. They will only be offered with the owner's permission,
as clearly labeled placements tied to a scheduled service item. They will
never appear next to trouble codes. Deals are part of the future plan and are
not available yet.

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
3. **Codes verified on the truck.** Retry reads where appropriate. A failed
   read must always be reported as failed; show “no codes” only after the
   truck has answered.

## Honest status

Trouble-code reads and clear-codes have not been tested on the truck yet.
Treat them as unverified until they are checked on a real 2000 F-250 7.3L.
Experimental Ford PIDs, including MFDES, are also unverified.

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

## Building

```bash
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`. Local
and pull-request builds use the debug signing key unless release-signing
credentials are configured.
