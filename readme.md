# AutoSentry

**FORScan meets CARFAX, built for the 7.3L Power Stroke.**

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

**Already working:** auto-start at key-on, live dashboard, trip MPG,
mileage and engine-hour tracking, oil life, service schedule with
due-soon alerts, and a maintenance log with photos.

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
7. **Crank/no-start snapshot on every start.** RPM, battery voltage, and
   pressure if the truck reports it.
8. **Fault codes on the Codes tab.** Built, not yet tested on the truck:
   stored and pending codes read at every connect, a clear-codes button
   (key on, engine off only), and 206 7.3L codes, each with its severity
   and what to check shown right under it. The Truck Systems scan
   (Dashboard tab) reports every module and PID this truck answers.
9. **Deals tied to each service alert.** The right parts for the job
   that's coming due.
10. **Resale report export.** A PDF of the verified service history.
11. **Opt-in anonymous data sharing.** Needed to show suppliers how many
    trucks are coming due for each part. The app keeps all data on the
    phone today.

## Roadmap

- Other makes, models, and engines through configurable vehicle profiles.
- Direct supplier partnerships once the numbers show demand.
