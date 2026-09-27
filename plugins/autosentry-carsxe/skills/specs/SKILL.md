---
name: specs
description: Decode a 17-character VIN to vehicle specs with the CarsXE API for AutoSentry vehicle profiles. Use when a VIN is given or VINDetector returns one and year, make, model, engine, drivetrain, or build date is needed.
argument-hint: "<VIN> [deepdata]"
allowed-tools: Bash(curl:*)
---

1. VIN from $ARGUMENTS or context. Must match `^[A-HJ-NPR-Z0-9]{17}$`; else reply `Invalid VIN` and stop.
2. `$CARSXE_API_KEY` empty → reply `Run /autosentry-carsxe:auth <KEY>` and stop.
3. `curl -sS "https://api.carsxe.com/specs?key=$CARSXE_API_KEY&vin=<VIN>&source=claude_plugin"`; append `&deepdata=true` only if requested. `error` field → report it and stop.
4. Output only: Year, Make, Model, Trim, Engine (displacement, cylinders, fuel), Transmission, Drivetrain, GVWR, Build date. Missing field → `Unavailable`. Never fill from memory.
5. `Profile match`:
   - `exact`: 2000 Ford F-250, 7.3L diesel — AutoSentry defaults apply.
   - `engine`: Ford 7.3L diesel, other year/model — `ServiceItemType` intervals target 7.3L F-250–F-550; confirm against owner's manual and diesel supplement.
   - `none`: no AutoSentry profile; 7.3L PID defaults, oil-life logic, and service intervals do not apply.
6. `Vehicle age` (years from build date, else model year) — Calculated. Feeds age-based inspection reminders only; not proof of part condition.
