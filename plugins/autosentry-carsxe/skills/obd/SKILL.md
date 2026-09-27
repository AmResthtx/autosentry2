---
name: obd
description: Decode OBD-II DTCs (e.g. P0300, P1211, U0100) with the CarsXE API for AutoSentry. Use when a DTC appears in conversation, DTCReader output, a debug log, or when DTCReader returns "Unknown code — check OBD-II database".
argument-hint: "<CODE> [CODE ...]"
allowed-tools: Bash(curl:*)
---

AutoSentry default vehicle: 2000 Ford F-250 7.3L Power Stroke. Local code map: `DTC_MAP` in `app/src/main/java/com/autosentry/app/obd/DTCReader.java`.

1. Codes from $ARGUMENTS or context. Uppercase. Reject anything not matching `^[PCBU][0-3][0-9A-F]{3}$`; name the rejected code and skip it.
2. `$CARSXE_API_KEY` empty → reply `Run /autosentry-carsxe:auth <KEY>` and stop.
3. Per code: `curl -sS "https://api.carsxe.com/obdcodesdecoder?key=$CARSXE_API_KEY&code=<CODE>&source=claude_plugin"`. Treat an `error` field as no data (HTTP 200 can carry it).
4. Output per code:
   - `Code`: code + DTCReader mode if known (Stored 03 / Pending 07 / Permanent 0A) — Measured
   - `CarsXE`: description, system, causes, fixes exactly as returned — Reference
   - `DTC_MAP`: local text if present; if it conflicts with CarsXE, print both and write `CONFLICT`
   - `Diagnostics tab`: one of Fuel/HPOP, Injectors, Turbo/Boost, Oil, Coolant, Electrical, Glow Plugs, Other
   - `Severity`: only if returned; else `not returned`
   - `Next step`: from returned fixes — Recommended
   - `Deals`: parts named in the response; else omit
5. No data → `Unavailable`. Never fill causes, fixes, or severity from memory.
6. Code missing from `DTC_MAP` and CarsXE returned a description → output the line `DTC_MAP.put("<CODE>", "<description>");`. Do not edit the file unless told.
