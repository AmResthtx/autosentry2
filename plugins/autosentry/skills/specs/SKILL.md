---
name: specs
description: Decode a 17-character VIN with NHTSA's free vPIC API for AutoSentry vehicle profiles. Use when a VIN is given or VINDetector returns one and year, make, model, engine, or drivetrain is needed.
argument-hint: "<VIN>"
allowed-tools: Bash(curl:*)
---

1. VIN from $ARGUMENTS or context. Must match `^[A-HJ-NPR-Z0-9]{17}$`; else reply `Invalid VIN` and stop.
2. `curl -sS "https://vpic.nhtsa.dot.gov/api/vehicles/decodevinvalues/<VIN>?format=json"`. Use `Results[0]`.
3. `ErrorCode` not `0` → print `ErrorCode` and `ErrorText` first. The decode may be partial or the VIN misread.
4. Output only: ModelYear, Make, Model, Trim, DisplacementL, EngineCylinders, FuelTypePrimary, TransmissionStyle, DriveType. Empty field → `Unavailable`. Never fill from memory.
5. `Profile match`:
   - `exact`: 2000 Ford F-250, 7.3L diesel — AutoSentry defaults apply.
   - `engine`: Ford 7.3L diesel, other year/model — `ServiceItemType` intervals target 7.3L F-250–F-550; confirm against owner's manual and diesel supplement.
   - `none`: no AutoSentry profile; 7.3L PID defaults, oil-life logic, and service intervals do not apply.
6. `Vehicle age` (current year − ModelYear) — Calculated. Feeds age-based inspection reminders only; not proof of part condition.
