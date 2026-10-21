---
name: vin-ocr
description: Read a VIN from a photo (door jamb, windshield, title) for AutoSentry. Use when VINDetector returns no VIN, or an image is shared for the vehicle profile or the Tools tab VIN scanner.
argument-hint: "<IMAGE_PATH | IMAGE_URL>"
allowed-tools: Bash(curl:*), Read
---

1. Image from $ARGUMENTS or the conversation. URL → `curl -sSLo "${TMPDIR:-/tmp}/vin-image" "<URL>"`, then Read it. Local path → Read it. None → reply `Need an image path or URL` and stop.
2. Transcribe the 17-character VIN. VINs never contain I, O, or Q. List any character you cannot read with certainty and its likely alternatives (e.g. `8/B`, `5/S`, `0/D`).
3. Result fails `^[A-HJ-NPR-Z0-9]{17}$` → `Unreadable — retake photo with flat light, VIN plate filling the frame` and stop.
4. Run the `specs` skill on the VIN. vPIC `ErrorCode` not `0` → report the VIN as possibly misread and show the uncertain characters from step 2.
