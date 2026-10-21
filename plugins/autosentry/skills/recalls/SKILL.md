---
name: recalls
description: Check safety recalls with NHTSA's free recalls API for AutoSentry. Use when recalls, safety campaigns, or recall content for the service-history PDF report are needed, by VIN or by year/make/model.
argument-hint: "[VIN | YEAR MAKE MODEL]"
allowed-tools: Bash(curl:*)
---

1. Resolve year, make, model:
   - VIN given → run the `specs` skill; use its ModelYear, Make, Model verbatim.
   - Year + make + model given → use them.
   - Nothing given → `2000 Ford F-250` (AutoSentry default); say so.
   - Partial → reply with the missing field and stop.
2. `curl -sS "https://api.nhtsa.gov/recalls/recallsByVehicle?make=<MAKE>&model=<MODEL>&modelYear=<YEAR>"`. URL-encode spaces.
3. Output the recall count, then per recall: campaign number, component, summary, consequence, remedy, report date. Recalls with park-it or park-outside flags true go first, marked `PARK IT` / `PARK OUTSIDE`.
4. Zero results → `0 recalls returned for <YEAR> <MAKE> <MODEL>.` This means no match for that exact model string, not proof that none exist.
5. Last line: `Model-line result from NHTSA; does not show whether this VIN's recalls were repaired.`
6. For the service-history PDF, list campaign number and component only.
