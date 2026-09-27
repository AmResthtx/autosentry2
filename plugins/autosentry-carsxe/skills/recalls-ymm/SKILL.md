---
name: recalls-ymm
description: Check safety recalls for a year/make/model (no VIN) with the CarsXE API for AutoSentry. Use when recalls are needed and no VIN is available.
argument-hint: "[YEAR MAKE MODEL]"
allowed-tools: Bash(curl:*)
---

1. Year, make, model from $ARGUMENTS or context. None given → use `2000 Ford F-250` (AutoSentry default) and say so. Partial → reply with the missing field and stop.
2. `$CARSXE_API_KEY` empty → reply `Run /autosentry-carsxe:auth <KEY>` and stop.
3. `curl -sS "https://api.carsxe.com/v1/recalls-ymm?key=$CARSXE_API_KEY&year=<YEAR>&make=<MAKE>&model=<MODEL>&source=claude_plugin"`. URL-encode spaces. `error` field → report it and stop.
4. Output: `recall_count`, then per recall: NHTSA campaign number, component, summary, consequence, remedy, report date. `park_it`, `park_outside`, over-the-air flags first when true.
5. None → `No recalls returned for <YEAR> <MAKE> <MODEL>.`
6. Last line: `Model-line result, not VIN-specific. Run /autosentry-carsxe:recalls <VIN> for this truck.`
