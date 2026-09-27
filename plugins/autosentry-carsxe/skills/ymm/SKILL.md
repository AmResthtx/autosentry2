---
name: ymm
description: Get specs, or list years/makes/models/trims/variants, by year/make/model with the CarsXE YMM APIs for AutoSentry. Use when no VIN is available — adding a vehicle profile or picking year/make/model/trim.
argument-hint: "<YEAR> <MAKE> <MODEL> [TRIM] | options [YEAR] [MAKE] [MODEL]"
allowed-tools: Bash(curl:*)
---

1. `$CARSXE_API_KEY` empty → reply `Run /autosentry-carsxe:auth <KEY>` and stop.
2. Year + make + model given and not `options` → specs:
   `curl -sS "https://api.carsxe.com/v1/ymm?key=$CARSXE_API_KEY&year=<YEAR>&make=<MAKE>&model=<MODEL>&source=claude_plugin"` + `&trim=` only if given.
   Output trims, engine, transmission, drivetrain. Then `Profile match` exactly as defined in the `specs` skill.
3. Otherwise → options list:
   `curl -sS "https://api.carsxe.com/v1/ymm-options?key=$CARSXE_API_KEY&source=claude_plugin"` + only the filters given (`dimension`, `year`, `make`, `model`, `trim`).
   No filters → years; year → makes; make → models; make + model → variants. Output the one list as bullets; show `message` if present.
4. `dimension=variants` + year + make without model bills 1 unit per model (`modelCount`). Stop and ask before calling.
5. URL-encode spaces. `error` field → report it and stop. Missing field → `Unavailable`.
