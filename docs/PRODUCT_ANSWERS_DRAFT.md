# AutoSentry: product answers (draft, 2026-10-20)

Drafted from what the repo already says (`readme.md`, `docs/PROMO_PLAN.md`, `docs/FORD_73_PIDS_AND_KOEO.md`, `docs/RED_TEAM_ANALYSIS.md`, and the code). **[your call]** marks a preference or number the repo doesn't settle. Change those before handing this back.

---

## Vision

### What 3 outcomes 12 months from now mean "we won"?

1. **Trucks run it hands-off.** 250 7.3L trucks have had an automatic trip in the last 30 days. Nobody opened the app to start one. **[your call: the number]**
2. **Owners trust it.** No wrong alert or wrong "all clear" goes unfixed. Play rating is 4.5 or higher, and fewer than 20% of complaints are about adapters. No tester has lost service history in an update.
3. **The record is worth something.** Owners have exported resale reports and used them to sell real trucks, and the first affiliate or report revenue has come in. That shows both the moat and the business model work.

### 1. What are we explicitly not building?

- **Commanded tests:** injector buzz, KOEO/KOER on-demand, glow plug, cylinder contribution, any `31 xx`. They stay out until the request bytes are captured from this truck (see `FORD_73_PIDS_AND_KOEO.md`), and even then they're never automatic.
- **Writing to the PCM:** tuning, programming, or module configuration. We never send `11`, `14` (outside Clear Codes), `27`, `2F`, `30`, or `34`-`3B`.
- **Inferred root-cause diagnosis**, such as "voltage sag + fuel pressure drop = corroded ground". The red-team analysis shows we can't back it up. We show codes and what to check, not conclusions.
- **Repair procedures, how-to guides, and torque-spec libraries.** The "Learn" flow in `flows.md` waits until after v1.
- **A second vehicle profile** until the 7.3 loop is proven (see Q5).
- iOS, BLE adapters, fleet or multi-user accounts, cloud accounts, social or community features, and manual fuel logging (Fuelly already does that).
- **Our own parts store or inventory.** We use affiliate links and labeled placements only.

---

## User + Market

### 4. Primary user?

**The DIY owner.** One person, one truck (sometimes two), who does their own maintenance and keeps the truck for life. The buyer or inspector *reads* the resale report but isn't a user we design screens for. Fleet operators and shop techs are out of scope. A shop tech already has FORScan or IDS.

### 5. Is 7.3L-only the strategy or a wedge?

**A wedge.** We stay 7.3-only until the core loop works and earns: auto-track → alert → log with proof → resale report. Expansion follows `PROMO_PLAN.md` Phase 4 (6.0/6.4/6.7 → Cummins → Duramax), ordered by tester demand. A profile ships only after it's tested on a real truck.

The architecture rule for now: anything specific to the 7.3 (PIDs, codes, intervals, header bytes) lives in vehicle-profile data, not scattered through the code. Then a second engine is new data, not a rewrite. We don't build the second profile early.

### 6. What is the "aha" in the first 5 minutes?

**"It knows my truck."** Within 5 minutes of install:

1. The setup wizard's connection test passes step by step, or names the exact step that failed.
2. The dashboard shows readings a generic app can't: Ford engine oil temp, ICP, and IPR.
3. After the owner estimates when each item was last done, the History tab lists what's due next on *this* truck, in miles, hours, and time.

The second aha comes the next day: the owner starts the truck without opening the app, and the trip is already recorded. That's the demo video moment.

---

## Trust + Safety Boundaries

### 7. Diagnostic guidance vs. repair advice: where's the line?

- **Guidance (we do it):** what the code means on a 7.3, how serious it is, what to inspect or measure, and what the truck reported, with how each value was obtained (measured, calculated, estimated).
- **Repair advice (we don't):** "replace part X", procedures, or a conclusion drawn from several signals.
- **Maintenance is different.** A due service item may name the parts for that job, because it's scheduled maintenance, not a diagnosis.
- **Hard rule: no parts deals or sponsored placements on trouble codes.** If a parts offer sits next to a guess, it looks like we steered the diagnosis for a commission, and the community won't forgive that.

### 8. What will the app never automate?

- **Clearing codes.** It always stays a manual button, key on and engine off, with a confirmation. Before clearing, the app saves the codes and the MIL state to history so nothing is lost. **[check: the code doesn't save them first today]**
- **Commanded tests and output actuation**, ever.
- **Marking a service done.** Only the owner logs a service. The app never infers one from data.
- **Purchases**, of any kind.
- **Turning off tracking.** Only typing STOP does that (current behavior, keep it).

### 9. How conservative when confidence is low?

**Quiet by default, never falsely reassuring.**

- **Experimental or unverified PIDs** (MAP, BARO, EBP, FPW, MFDES): show the value, labeled "unverified". No colored borders, no alerts, and nothing feeds a severity.
- **Trouble codes:** the code is a fact, so its dictionary severity stands. A code missing from the 7.3 list is shown raw ("not in the 7.3 list"), never hidden.
- **Threshold anomalies** (a reading that's off): no alerts until the app has learned *this* truck's baseline, per the red-team analysis.
- **A failed read says it failed (decided).** The product is built on trust, so before the app concludes anything, it retries the code read. "No codes" appears only when a read succeeds and the truck actually reports none. If every attempt fails, it says "Couldn't read trouble codes" and gives the reason. It never shows "no codes" and never stays silent. **This is a bug today:** the Checks tab tries once and treats a timeout or NO DATA as "no codes" (`TrackingService.scanCodes`, `DTCReader.java:85`).

---

## UX / Design Direction

### 10. What about the last APK design should be kept? **[your call: confirm against the APK you like]**

My read of the current build (#24):

- **Layout:** left menu, a light toolbar, and a grid of dial tiles. Each tile has a white needle, a round-number scale, the value in color, and the name underneath. Zoom changes how many tiles fit across, and holding a tile moves or removes it.
- **Navigation:** five tabs: Dashboard · Checks · History · Deals · Account.
- **Density:** dense on the dashboard, and the owner picks what's on it.
- **Tone:** plain English, short sentences, and every value labeled with where it came from.

### 11. Operator dashboard or guided workflow?

**Both, split by job.** Anything that only *observes* is an operator view: live and dense (Dashboard, the Truck Systems report). Anything that *changes the truck or the record* is a guided, step-by-step flow: setup, Key-On Check, Clear Codes, Log Service, export.

### 12. Which screens are sacred? **[your call]**

1. The Dashboard tile grid
2. The code layout on Checks: code → meaning → severity → what to check, right under it
3. The service list on History: status, what's due, and which limit (miles, hours, or time) comes first

Free to change: the setup wizard (later phases still ship), Deals (not built), and Account.

---

## Diagnostics Roadmap

### 13. Priority order

1. **Trip metrics: miles and engine-hour accuracy.** The maintenance engine runs on them, and they're the data nobody else has. (MPG comes last: it needs MFDES verified.)
2. **Codes:** built, but not yet tested on the truck.
3. **Live gauges:** they exist. Fix silent failures (no `7F` parsing).
4. **Truck Systems scan:** built and read-only. Polish only.
5. **Freeze frame:** cheap to add next to codes, but expect thin data. The 2001 capture shows Mode 01 supports only PIDs 01, 0B, 0C and 0D.
6. **Mode 22 exploration:** a developer tool (`tools/obd_probe.py`), not an owner feature.

### 14. When is the Codes tab "done"?

All of these hold:

- It reads stored codes, pending codes, and the MIL, and it's **verified on the real truck**.
- **It retries before deciding.** Up to 3 tries per request, with a short pause between them, and only when an attempt fails. A normal read stays one try.
- It tells apart "codes found", "confirmed no codes", "couldn't read after 3 tries", and "truck refused (`7F`)". It says "no codes" only when the truck answered. It cross-checks the stored-code count from Mode 01 PID 01: if the truck says 2 codes are stored but none came back, the read failed. It is not a clean result.
- **Clear Codes is blocked after a failed read.** You can't clear what you couldn't read.
- Each code shows its 7.3 meaning, severity, and what to check. Unknown codes are shown raw.
- **Code history:** first seen, cleared, and came back.
- Clear Codes is guarded (key on, engine off, confirm) and saves the codes before clearing.

### 15. Truck Systems scan: raw or translated?

**Both.** Put a short owner summary on top ("4 modules answered, 2 codes, 3 readings unverified"). The full mechanic-grade raw report goes below it, and that's what the share button sends, because it'll be pasted into a forum thread or handed to a mechanic.

---

## Data Quality + Hardware Reality

### 16. What happens when a reading isn't supported?

Three different states, never mixed up:

- **Not supported by this truck:** the truck said so (NRC 12) or never answered during the probe. It's off the dashboard by default and still listed in the picker as "not supported". (The picker already does this.)
- **`--`:** a momentary no-reading on a supported PID.
- **Unverified:** an experimental PID, labeled as such.

MPG specifically shows "needs Ford fuel data, coming later", never a made-up number.

### 17. Slow but thorough, or fast but partial?

- **Automatic** (key-on check, trip polling): fast and partial. It must never delay recording or the crank capture.
- **On demand** (Truck Systems scan): slow and thorough is fine, if it has a progress bar, a cancel button, and partial results as they come in.

### 18. Adapter floor

**A vetted list.** OBDLink MX+ and LX are officially supported. The CX won't work because it's BLE only. Other adapters are labeled "may work, not supported". The connection test already shows which step failed. Detect common clone ID strings and warn. Before v1, validate at least 3 adapter models (`PROMO_PLAN.md`).

---

## Maintenance System

### 19. A strict Ford baseline or Ford + community?

**Ford + community, labeled.** Ford's severe or normal interval is the baseline, picked by the duty cycle chosen at setup. Community rules (such as the fuel filter every other oil change on the stock filter) are added, and **each interval shows where it came from** (Ford, or community practice).

### 20. Full customization or tune around the defaults?

**Full edit, with the default always visible.** Owners can change any interval, add custom items (for aftermarket parts), hide items that don't apply (already supported), and reset to the default. The app always shows when they've moved off the default.

### 21. Alert philosophy

**Keep minimal-smart** (`ServiceAlertPolicy`): alerts only after a trip ends, one bundled notification, one alert per stage, and a reminder every 14 days when overdue.

**One conflict to fix:** the readme promises alerts "early enough to order parts and have them arrive in time", but STANDARD items (coolant, trans fluid, diffs, brake fluid…) alert only *once overdue*. Proposal: give STANDARD items one "order parts now" alert at a lead time based on how fast this truck adds miles and hours (about 2 weeks out), then nothing more until overdue. The Deals model depends on that alert too.

---

## Business Model

### 22. One-time, subscription, or freemium? **[your call, but the readme already chose]**

**Free for owners, with one paid add-on. No subscription.**

- Revenue comes from parts affiliate commissions plus labeled supplier placements on service alerts.
- The resale report is a one-time purchase.

### 23. What's free forever, and what's paid?

- **Free forever:** auto-tracking, the dashboard, codes and the Truck Systems scan, the service schedule and alerts, the service log with photos, and local backup and export.
- **Paid:** the resale report PDF (one-time, per export or per truck). Later, possibly a second-vehicle profile.

Supplier placement costs owners nothing.

### 24. Revenue soon, or trust first?

**Trust and adoption first.** Set up the affiliate link plumbing early, because it's cheap and tests the model. Don't sell supplier placement until anonymous data (Q27) can show how many trucks are coming due for each part.

---

## Privacy + Ownership

### 25. Does all data stay local by default?

**Yes.** It already does: the `INTERNET` permission was removed (`SECURITY_HARDENING_SUMMARY.md`). It's a selling point, so lead with it on Play and on the landing page.

### 26. Cloud sync plan?

**Not within 12 months. But local backup is a v1 blocker.** `allowBackup="false"` means a dead or lost tablet wipes the service history, and the history is the moat. Ship encrypted export and import to a file the owner picks (Drive, SD card, USB) before v1. Owners share with a mechanic or buyer through the report and the share sheet, not through sync.

### 27. Anonymous telemetry?

**Opt-in only, off by default, in two separate switches:**

1. **PID and adapter coverage:** which PIDs answered on which PCM and adapter. It's very useful and low-risk. Start with a manual "share this scan" before building anything automatic.
2. **Aggregate "trucks coming due for each part"** for suppliers, later.

Either one means adding back network access and updating the Play Data Safety form.

---

## Release Strategy

### 28. What must be true before a public v1?

- **Data survives updates:** persistent signing, Room migrations, a CI schema guard. That's this branch, `fix/safe-app-updates`.
- **Local backup and restore** work.
- **Codes** are verified on the truck. Reads are retried, and a failed read always says it failed, never "no codes".
- **`7F` and `NO DATA` handling**, so failures aren't silent.
- **The EOT formula** is confirmed with a cold soak (EOT equals ambient at key-on after sitting overnight).
- **Adapters:** the list is validated on at least 3 models.
- **Play closed test:** 12+ testers for 14 days, and no crash-on-connect reports.
- **Play paperwork:** privacy policy, declarations for exact alarms, background location, and the foreground service, plus the disclaimer shown in the app.
- **The minified release build** is tested on a device.

### 29. What's the biggest risk right now?

**Flaky adapter behavior.** Silent failures turn it into wrong guidance: a failed read shows "no codes" and a dropped PID shows `--` with no reason. It's also the top cause of one-star reviews and support load. **UI churn** comes second, and it's self-inflicted: two dashboard redesigns in a row (#23, #24). The merge rubric should stop it.

### 30. If the next release ships only 3 things?

1. **Data-safe updates:** finish and merge `fix/safe-app-updates`.
2. **A maintenance engine that can alert on every item:** last-service estimates at setup, one schedule including oil, and miles + hours + time limits for every item (readme priorities 1-3).
3. **Codes you can trust:** verified on the truck. Reads are retried, a failed read says it failed, and "no codes" only appears when the truck actually reported none.
