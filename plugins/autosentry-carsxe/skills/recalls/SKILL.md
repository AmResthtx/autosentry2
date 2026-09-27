---
name: recalls
description: Check open safety recalls for a VIN with the CarsXE API for AutoSentry. Use when a VIN is known and recalls, safety campaigns, or recall content for the service-history PDF report are needed.
argument-hint: "<VIN>"
allowed-tools: Bash(curl:*)
---

1. VIN from $ARGUMENTS or context. Must match `^[A-HJ-NPR-Z0-9]{17}$`; else reply `Invalid VIN` and stop. No VIN but year/make/model known → use the `recalls-ymm` skill.
2. `$CARSXE_API_KEY` empty → reply `Run /autosentry-carsxe:auth <KEY>` and stop.
3. `curl -sS "https://api.carsxe.com/v1/recalls?key=$CARSXE_API_KEY&vin=<VIN>&source=claude_plugin"`. `error` field → report it and stop.
4. Output: open count, then per recall: campaign number, component, defect, remedy, remedy status, date. Recalls with `park_it` or `park_outside` true go first, marked `PARK IT` / `PARK OUTSIDE`.
5. None returned → `No open recalls returned for <VIN> as of <today>.`
6. All recall data is Reference. For the service-history PDF, list campaign number and remedy status only.
