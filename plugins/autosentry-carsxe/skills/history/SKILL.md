---
name: history
description: Pull a vehicle history report for a VIN with the CarsXE API for AutoSentry. Use when title status, accidents, owner count, odometer history, theft records, or history content for the service-history PDF report is needed.
argument-hint: "<VIN> [odometer=N]"
allowed-tools: Bash(curl:*)
---

1. VIN from $ARGUMENTS or context. Must match `^[A-HJ-NPR-Z0-9]{17}$`; else reply `Invalid VIN` and stop.
2. `$CARSXE_API_KEY` empty → reply `Run /autosentry-carsxe:auth <KEY>` and stop.
3. `curl -sS "https://api.carsxe.com/history?key=$CARSXE_API_KEY&vin=<VIN>&source=claude_plugin"`. `error` field → report it and stop.
4. `RED FLAGS` first: salvage, rebuilt, flood, or lemon title; any odometer reading lower than an earlier one; theft record. None → `RED FLAGS: none returned`.
5. Then: title status, accident/damage records, owner count, odometer readings with dates, theft records. Missing → `Unavailable`.
6. `odometer=N` given (AutoSentry `VehicleProfile.odometerMiles`) → difference from the latest history reading — Calculated.
7. Service entries in the report are third-party, never AutoSentry Recorded evidence.
8. Privacy: output owner count only. Never output names, addresses, or contact data, even if returned.
