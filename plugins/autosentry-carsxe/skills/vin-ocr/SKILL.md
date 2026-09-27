---
name: vin-ocr
description: Extract a VIN from a public image URL with the CarsXE VIN OCR API for AutoSentry. Use when VINDetector returns no VIN, or a door-jamb, windshield, or title photo URL is shared for the vehicle profile or the Tools tab VIN scanner.
argument-hint: "<PUBLIC_IMAGE_URL>"
allowed-tools: Bash(curl:*)
---

1. Image URL from $ARGUMENTS or context. Must be a public `http(s)` URL; local file or none → reply `Need a public image URL` and stop.
2. `$CARSXE_API_KEY` empty → reply `Run /autosentry-carsxe:auth <KEY>` and stop.
3. `curl -sS -X POST "https://api.carsxe.com/v1/vinocr?key=$CARSXE_API_KEY&source=claude_plugin" -H "Content-Type: application/json" -d '{"image":"<URL>"}'`. `error` field → report it and stop.
4. Output: VIN and confidence if returned. VIN failing `^[A-HJ-NPR-Z0-9]{17}$` → `Unreadable — retake photo with flat light, VIN plate filling the frame` and stop.
5. Valid VIN → run the `specs` skill on it.
