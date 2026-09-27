---
name: value
description: Get market value for a VIN with the CarsXE API for AutoSentry. Use when asked what the truck is worth, resale or trade-in value, or value content for the paid service-history PDF report.
argument-hint: "<VIN> [state=XX] [mileage=N] [condition=excellent|clean|average|rough]"
allowed-tools: Bash(curl:*)
---

1. VIN from $ARGUMENTS or context. Must match `^[A-HJ-NPR-Z0-9]{17}$`; else reply `Invalid VIN` and stop. Parse optional `state`, `mileage`, `condition`. Do not ask for missing optionals.
2. `$CARSXE_API_KEY` empty → reply `Run /autosentry-carsxe:auth <KEY>` and stop.
3. `curl -sS "https://api.carsxe.com/v2/marketvalue?key=$CARSXE_API_KEY&vin=<VIN>&source=claude_plugin"` + `&state=`, `&mileage=`, `&condition=` only if given. `error` field → report it and stop.
4. Output: estimate; low / average / high if returned; `Adjusted for`: the optionals sent, or `none`; date. All Reference.
5. Service-history PDF: pair the value with AutoSentry maintenance records labeled by evidence (receipt, photo, none). Never claim records raise value by an amount; no data supports a number.
