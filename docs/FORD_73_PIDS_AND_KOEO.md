# 7.3L Power Stroke: Ford PIDs and KOEO tests (research, red-teamed)

Research from 2026-10-11 on a 1999-2003 7.3L PCM (EEC-V, SAE J1850 PWM) reached through an ELM327. Each claim was researched by one agent and then checked by a second agent that tried to disprove it. Forum and ScanGauge pages could only be read through search-engine extracts, because the session's network blocked them. GitHub sources were cloned and read in full.

## Bottom line

- **PIDs:** EOT 1310, IPR 1434 and ICP 1446 are solid; the app already reads them. MAP, BARO, EBP, FPW and MFDES have one independent source each, so they can ship as experimental readings. Nobody has published a FORScan PID-number table: FORScan shows names only.
- **KOEO tests:** no public source gives the request bytes for the 7.3L injector buzz test, glow plug test or cylinder contribution test. The KOEO and KOER on-demand bytes that do exist come from a 1995 Mustang (gasoline) shop manual. On another Ford PCM, those same test IDs actuate relays, raise RPM and cut cylinders.
- **Plan:** do not send `31 xx` to the truck until the bytes are captured from this truck while FORScan runs each test (procedure below).

## How replies look on this truck

These details come from 44 raw frames captured from a 2001 F-250 7.3 (PCM type 0x116, ELM327 v1.4b, `ATH1 ATSP1 ATSH C410F1`). The source is github.com/erichexter/Carplay, `obd2/probe-2.txt` and `obd2/probe-tft.txt`.

- **Request:** header `C4 10 F1` (`ATSHC410F1`), then `22 PP QQ`. Mode 01 must go back to `61 6A F1`, because Mode 01 on `C4 10 F1` returns `7F 01 .. 11`.
- **Positive reply:** `64 F1 10 62 PP QQ <data> <crc>`.
- **Negative reply:** `64 F1 10 7F 22 PP QQ 00 <NRC> <crc>`. NRC `12` means not supported.
- **CRC:** the last byte of every frame is the J1850 CRC-8 (poly 0x1D, init 0xFF, final XOR 0xFF). It matched in 44 of 44 frames. With `ATH0`, as the app uses, the ELM drops the header and the CRC. With `ATH1`, drop the last byte before decoding.
- **The Carplay author's decodes are unreliable.** They read the CRC as data. Their "TFT 1128", "ICP from 1434", "MAP 1102" and FPW values were all CRC bytes or zero payloads.
- **Mode 01 bitmap:** `41 00 80 38 00 00` means only PIDs 01, 0B (MAP), 0C (RPM) and 0D (speed) are supported. Mode 01 05 (ECT) returns 00, which reads as -40 °C.

## PIDs

Raw = A*256+B unless noted. The request is `22` plus the PID.

### Ship now

| PID | Reading | Formula | Sources |
|---|---|---|---|
| 1310 | Engine oil temp (EOT) | °C = raw/100 - 40 | ScanGauge X-Gauge (forum, "works on my 2000 7.3"); shulltronics/OBD-trucker and obd_trucker (1996 7.3); capture confirms 2 data bytes |
| 1434 | IPR duty | % = A*100/255 (1 byte) | ScanGauge X-Gauge `RXD 3008 MTH 006400FF0000`; obd_trucker (requires 1 byte); capture `0F` gives 5.9 % at idle, against FORScan's 6.3-6.6 % |
| 1446 | Injection control pressure | psi = raw * 0.57 (±2 %) | ScanGauge forum `MTH 003800640000` (0.56); obd_trucker 0.57; an "official" 0.568 was reported but not seen. The app uses 0.56 |
| Mode 01 0B / 0C / 0D | MAP kPa, RPM, speed | standard J1979 | 2001 capture bitmap |

EOT has one contrary data point. The Carplay author says FORScan showed 36 °C when the raw value was 0x24D1. That equals raw/256, while the formula above gives 54 °C. Their other "matches" were CRC artifacts, so the formula above stands. A single cold-soak reading settles it: overnight cold, key on, engine off, EOT should equal the outside temperature. At 15 °C the raw value is about 0x157C with this formula, or about 0x0Fxx with raw/256.

### Experimental: ship labeled "unverified" and check on the truck first

| PID | Reading | Formula | Check before trusting |
|---|---|---|---|
| 1440 | MAP (absolute) | psia = raw * 0.03625 | With key on and engine off, MAP ≈ BARO ≈ local barometer (about 14.5 psia); cross-check against Mode 01 0B |
| 1442 | BARO | psia = raw * 0.03625 | About 405 counts at sea level |
| (calc) | Boost | psi = MAP - BARO | Near 0 at idle |
| 1445 | Exhaust back pressure (absolute) | psia = raw * 0.03625; gauge EBP = EBP - BARO | ≈ BARO with key on, engine off |
| 1410 | Fuel pulse width | ms = raw * 0.008 | A x0.004 claim also exists; compare against FORScan FUELPW |
| 1412 | Mass fuel desired | mg/stroke = raw / 16 | Compare against FORScan MFDES |
| 11BD | Trans fluid temp (1994.5-97 E4OD fit) | °F = raw * -0.0036 + 212.98 | Only if the PCM answers; cold soak should read ambient. Fitted on a 1996 truck, so it may not hold for the 4R100 |

The 0.03625 factor equals 0.25 kPa per count. It is used for MAP, BARO and EBP in shulltronics' code, which is one author.

### Do not ship

| PID | Why |
|---|---|
| 1139 ECT | The 7.3 PCM has no real ECT. The forum value wraps below 127 °F, and Mode 01 05 reads -40 |
| 1430 Glow plug % | Name only; no formula anywhere |
| 1674 TFT | The raw/8 formula has no source; never probed |
| 1E1C TFT | Returns NRC 12 on a 2001 |
| 1100, 1102, 1103, 1105, 1107, 1128, 0010-001A | Zero payloads or CRC artifacts in the capture |

Searched for but not found with a PID number: ICP desired, EBP desired, IAT, accelerator pedal, TPS, VPWR, VREF, CMP/CKP, gear, TCC slip, and EPC. The 1995 Mustang manual numbers (for example VPWR 1172 and IAT 1123) are for a different PCM, and several nearby 11xx PIDs returned NRC 12 on the 2001.

## KOEO and commanded tests

### What is published

github.com/twhitehead/notes-obd2elm327edb transcribes a **1995 Mustang** shop manual's EDP strings. The header is `C4 10 F1` in every case.

| Test | Sequence (order taken from the strings' next-string pointers) |
|---|---|
| KOEO on-demand | `31 81` start → `32 81 00` wait → `22 02 02` count → `33 81` results |
| KOER on-demand | `31 82` → `32 82 00` → `22 02 02` → `33 82` |
| Output test mode | `25`, `31 84`, `B1 00 25 0x` (01 off, 02 all on, 03 low fan, 04 high fan), `32 84` to exit |
| Continuous-memory DTCs | `13` |

Corrections from the red team:
- The README's "Transmitting the codes" block lists these steps in reverse order.
- The README is not byte-exact to the manual. Its checksums are wrong on the START and DTC strings.
- The meaning of `A181 61 03` ("repeat until the reply byte is 61") is unverified.

An Australian BA/BF Falcon PCM (CAN, not PWM) answers the same IDs: 81 = KOEO, 82 = KOER, 84 = output test. Its replies were `00 00 00` started, `00 00 22` unavailable and `00 00 12` invalid ID. On the Falcon, `31 81` with the engine off clicked relays and flashed the MIL, and `31 82` with the engine running raised RPM and cut cylinders. None of this proves the IDs on the 7.3L.

### What is not published

- **Not found anywhere public:**
  - Injector buzz test (KOEO Injector Electrical Self Test)
  - Glow plug test (KOER; only California trucks with a glow plug control module)
  - Cylinder contribution test (KOER; IDS requires EOT of at least 170 °F)
  - Ford-specific code clear
- **Where the bytes exist:** inside FORScan, IDS/NGS, AutoEnginuity and the CarGaugePro APK. CarGaugePro ran the buzz test on 7.3Ls through a BAFX ELM327, and it was removed from Google Play on 2022-10-31.

### Safety

- **Buzz test:** all 8 injectors fire for about 2 s, then each fires for about 1 s in order 1-8. The IDM drives them at roughly 115 V.
  - Run it only with RPM 0, the harness installed, and nobody touching it.
  - Don't repeat it back to back, because it drains the battery.
  - Ford advises unplugging the glow plug relay during repeated tests.
- **KOER and contribution tests:** the PCM raises RPM and the engine runs rough. Require park, the parking brake, VSS 0 and an explicit confirmation.
- **Output test "all on":** can hold relays on, including the glow plug relay (60-150 A).
- **Never send an unverified `31 xx`.** An unknown routine can actuate outputs. The PCM can also stay in test mode until the key is cycled: a CarGaugePro user reported the truck "sounded terrible while driving" afterwards.
- **Never send** `14` (clear), `11`, `27`, `2F`, `30` or `34`-`3B`.

### Safe to add now (read-only)

- `13` (continuous-memory DTCs) and `22 0200` / `22 0202` (DTC counts) on `C4 10 F1`. The worst case is a `7F` reply.
- Parsing `7F` negative replies (see the app issues below).

### Getting the missing bytes from this truck

1. **Easiest:** run FORScan for Windows through a PWM-capable USB or Bluetooth adapter.
   - Log the COM port with a serial port monitor.
   - Run each PCM test once: KOEO On-Demand, KOEO Injector Electrical, KOER On-Demand, KOER Glow Plug (if offered) and Cylinder Contribution.
   - Record every `ATSH`/`ST` command, request, reply, poll loop and `3F` keep-alive.
2. **Passive sniff:** connect a second PWM adapter through an OBD Y-splitter. An OBDLink SX/LX over USB is best.
   - Send `ATZ ATE0 ATL1 ATS1 ATH1 ATSP1 ATAL ATMA`. Force `ATSP1`, because auto-search transmits.
   - Raise the baud rate so the adapter doesn't fill its buffer, and timestamp lines on the PC.
3. **For every test:**
   - Park with the parking brake on, and run each test twice to confirm the bytes are the same.
   - Keep the PCM replies (`71` / `73` / `7F`) so the app can tell started, busy and done apart.

## App issues the research turned up

- **Weak engine-off check.** `TrackingService.runKoeoCheck` treats a missing RPM (NaN) as engine off (`rpm > 0` is false for NaN). That's fine for today's read-only check, but any commanded test needs all of these before sending:
  - RPM read successfully and equal to 0, plus speed 0 and `EngineWatch` showing KEY_ON
  - No open trip or crank log
  - A typed confirmation
  - The test not reachable through the report dialog's "Run Again" button
- **Silent failures.** There is no `7F` handling in `ELM327Adapter.parseReply`: NRCs, `NO DATA` and timeouts all become NaN. The ATSH replies are not checked for `OK`.
- **ICP and IPR are advertised without a probe.** They are added to the supported set whenever the bus is PWM (`TrackingService.probeFordOilTemp`).
- **Link drops while parked.** A key-on, engine-off period longer than 120 s disconnects and resets the adapter (`parkedLongEnough` → `waitForKeyOn`). That would break a multi-step test.
- **`tools/obd_probe.py` reports false results:**
  - It sends `CANDIDATE_MODE22_PIDS` without the PCM header, and reports any reply that isn't `NO DATA` as "looks live", including `7F` replies.
  - `--forscan` and `--sweep` do use `ATSHC410F1`, but can't tell a `7F` reply from no answer.
  - The log is only written at exit.
- **Auto-detect may hang.** Carplay found that `ATSP0` auto-detect hangs on a 2001 7.3. The app uses `ATSP0` with a 15 s bus-init timeout; `ATSP1` would skip the search.

## Sources

- github.com/erichexter/Carplay: `obd2/PIDS.md`, `obd2/probe-2.txt`, `obd2/probe-tft.txt` (2001 F-250 7.3 raw captures)
- github.com/shulltronics/OBD-trucker and github.com/shulltronics/obd_trucker `PIDs.py` (1996 F-250 7.3)
- github.com/twhitehead/notes-obd2elm327edb (1995 Mustang EDP strings)
- ScanGauge 7.3L X-Gauge codes from scangauge.com and from forum posts on powerstroke.org, ford-trucks.com and expeditionportal.com. These were seen through search extracts only.
- fordmods.com "BA-BF-SX-SY Scantool Fun" (Falcon Mode 31 IDs, search extracts only)
- Ford 7.3L PC/ED pages mirrored on diesel-dave.com; fcarusa.com KOEO injector electrical self-test FAQ (buzz timing)
- github.com/JOHLC/Home-Assistant-Torque-OBDII `PIDS.md` (Torque Ford PID names and units)
