# Where every request comes from (provenance)

AutoSentry is built clean-room. This file lists every request the app can put on the
truck's data line, where the bytes and the formula came from, and how far each is proven.
A request with no row here must not be added to the app.

## Rules

- Sources allowed: public standards (SAE J1979, J2012, J2190, J1850; ISO 14229, ISO 15765),
  the ELM327 data sheet, openly licensed projects (licence checked and named), public forum
  posts, and this truck's own answers to requests we wrote.
- Never used: any other scan tool's code, binaries, screens, icons, text, or its module,
  PID, trouble-code or configuration files, and never bytes recorded from another tool's
  traffic. Looking at a value or a code list on another tool's screen to check our own
  result is allowed.
- AutoSentry sends reads only, plus one write: Mode 04 (clear codes), and only after a
  full read succeeded and was saved.
- "Confirmed" means: raw replies captured on this truck, and the decoded value matched a
  known-good value under known conditions. Nothing is confirmed yet. The app and this file
  say so wherever it matters.

Licence note: the open-source projects named below were used as sources of facts (PID
numbers and scaling). No code was copied from them. **Their licences have not been
checked**: this session could not open GitHub outside this repository. Open each
repository's LICENSE file and record it here before treating this as settled:

| Project | Used for | Licence |
|---|---|---|
| github.com/shulltronics/obd_trucker, github.com/shulltronics/OBD-trucker | Ford PID numbers and scaling | Not checked |
| github.com/twhitehead/notes-obd2elm327edb | The `13` request and the code-count PIDs | Not checked |
| github.com/erichexter/Carplay (`obd2/` captures) | Reply layout on a 2001 7.3L, supported-PID bitmap | Not checked |

## Adapter commands (ELM327 data sheet)

| Command | Purpose | Notes |
|---|---|---|
| `ATZ`, `ATE0`, `ATL0`, `ATS0`, `ATH0`, `ATSP0` | Reset; echo, linefeeds, spaces, headers off; protocol auto | Sent at every connect. |
| `ATTP1` | Try J1850 PWM directly | Only when the protocol search found nothing. Not saved in the adapter. Added because one public 2001 7.3L capture reports the search hanging. Unconfirmed on this truck. |
| `ATSH C4 10 F1` / `ATSH 61 6A F1` | Address the PCM directly / back to the standard OBD address | Both replies are checked for `OK`. If the second fails twice the link is reset. |
| `ATH1`, `ATAT0`, `ATST FF`, then `ATH0`, `ATAT1`, `ATST 32` | The one "slower try" at Ford memory codes: whole frames, longest wait | Adapter settings only. Put back afterwards; `ATST 32` is the data-sheet default. |
| `ATDP`, `ATDPN`, `ATI`, `ATRV` | Protocol name and number, adapter ID, port voltage | Read-only. |
| `ATMA` | Listen-only monitoring (Systems Scan) | Sends nothing on the bus. |

## Trouble codes

| Request | Header | What it is | Source | Status |
|---|---|---|---|---|
| `01 01` | `61 6A F1` | Check-engine light and stored-code count | SAE J1979 | Standard. Supported per the public 2001 7.3L capture (bitmap `80 38 00 00`). |
| `03` | `61 6A F1` | Stored codes | SAE J1979 | Standard. |
| `07` | `61 6A F1` | Pending codes | SAE J1979 | Standard. |
| `04` | `61 6A F1` | Clear codes | SAE J1979 | Standard. Only after a read that succeeded; the codes are saved first. |
| `13` | `C4 10 F1` | Ford continuous-memory codes | SAE J2190 service $13; the request string is in github.com/twhitehead/notes-obd2elm327edb, a transcription of a 1995 Mustang (EEC-V) shop manual | **Unconfirmed on a 7.3L.** Expected reply `53` + two bytes per code. Also unknown: whether an empty memory answers `53 00 00...`, refuses, or stays silent. A silent truck is shown as "couldn't read", never as "no codes". |
| `22 02 00`, `22 02 02` | `C4 10 F1` | Ford code-count PIDs | Same 1995 Mustang notes (used there in the self-test sequence) | **Unconfirmed.** Read after a manual scan and written to the debug log only. No decision uses them. |

Code meanings: SAE J2012 for the code format; titles follow Ford's 7.3L code chart as
reproduced on public forum pages (see `DtcDictionary.java`). Severity and hints are
AutoSentry's own wording.

Reply handling that is our own design, tested in `FordMemoryTest` and `DTCReaderTest`:
three codes per line padded with `00 00`; refusals as `7F 13 <reason>` or Ford's six-byte
`7F 13 00 00 00 <reason>` (layout seen for Mode 22 in the public 2001 capture); whole frames
with headers on accepted only when addressed to `F1` with a correct J1850 checksum.

## Live readings

Standard readings are Mode 01 PIDs from SAE J1979 with the standard's formulas. The app asks
only for the ones the truck lists as supported. The public 2001 7.3L capture lists just
`01`, `0B` (manifold pressure), `0C` (RPM) and `0D` (speed); this truck may differ.

Ford readings are Mode 22 to the PCM (`C4 10 F1`):

| Request | Reading | Formula (A, B = data bytes) | Source | Trust level in app |
|---|---|---|---|---|
| `22 13 10` | Engine oil temp | (A*256+B)/100 - 40 °C | ScanGauge X-Gauge code (public forum posts); shulltronics OBD-trucker / obd_trucker | Published |
| `22 14 46` | Injection control pressure | (A*256+B) x 0.56 psi (sources: 0.56 to 0.57) | ScanGauge X-Gauge; obd_trucker | Published |
| `22 14 34` | IPR duty | A x 100/255 % | ScanGauge X-Gauge; obd_trucker | Published |
| `22 14 40` | MAP, absolute | (A*256+B) x 0.03625 psi | obd_trucker (one author) | Unverified |
| `22 14 42` | BARO | (A*256+B) x 0.03625 psi | obd_trucker | Unverified |
| `22 14 45` | Exhaust back pressure, absolute | (A*256+B) x 0.03625 psi | obd_trucker | Unverified |
| `22 14 10` | Fuel pulse width | (A*256+B) x 0.008 ms (a x0.004 claim exists) | obd_trucker | Unverified |
| `22 14 12` | Mass fuel desired | (A*256+B)/16 mg/stroke | obd_trucker | Unverified |
| `22 11 BD` | Trans fluid temp | (A*256+B) x -0.0036 + 212.98 °F | OBD-trucker, curve fitted on a 1996 E4OD | Unverified; may not hold for the 4R100 |

Calculated in the app: Boost = manifold pressure (`01 0B`) minus outside air pressure
(`22 14 42` when it answers, otherwise the manifold pressure seen with the engine stopped
this key cycle). No value is shown when neither is available.

### Asked for, but not available

| Reading | Why it isn't in the catalog |
|---|---|
| Injection timing | The standard PID (`01 5D`) is in the catalog and is shown if the truck lists it; the public 7.3L capture doesn't. No public source gives a Ford PID number for it on this PCM. |
| "FICM voltage" | The 7.3L has no FICM. That module is on the 6.0L. The 7.3L's injector driver module (IDM) has no publicly documented PID for its supply voltage. |
| Boost as a direct reading | No direct PID; it is calculated (above). |
| Glow plug duty, coolant temp, trans temp `1674` / `1E1C`, ICP desired, pedal position | Listed under "Do not ship" or "not found" in `docs/FORD_73_PIDS_AND_KOEO.md`: no formula, no source, or refused on the public capture. |

The Systems Scan's Mode 22 sweep and `tools/obd_probe.py --sweep` show which other PIDs
this truck's PCM answers. An answering PID with no public formula can still be added as
"unverified" once we work the formula out on the truck ourselves.

## Captures from this truck

None yet. Add a row per capture; a reading moves to "Confirmed" (in `PidCatalog.java`,
`Trust.CONFIRMED`, and its "(unverified)" name suffix dropped) only with a row here.

| Request | Raw reply | Date | Conditions | Known-good value it was compared with |
|---|---|---|---|---|
| | | | | |

How to capture:

1. In the app: Account > View Debug Log > Share, after a code scan. The log has every
   request and raw reply of the scan, and one line for each reading that was dropped
   ("truck's reply: ...").
2. Checks > Scan Truck Systems (active mode) saves a full report with raw replies to
   `Downloads/AutoSentry`.
3. From a PC: `python tools/obd_probe.py --catalog`, which prints rows for this table.

Good conditions for checking formulas: truck parked overnight, key on, engine off. Oil temp
should equal the outside temperature, and MAP, BARO and exhaust back pressure should all
equal the local barometer (about 14.7 psi at sea level).

## Not sent, on purpose

`14`, `11`, `27`, `2E`, `2F`, `30`, `31`, `32`, `33`, `34`-`3B`, `B1`. That covers clears
other than Mode 04, resets, security access, writes, output control and every commanded
test. The Systems Scan's `ReadOnlyGuard` refuses them before a byte is written. The
commanded tests and module configuration stay out until their bytes exist in a public
source for this PCM and the safety rules in `docs/FORD_73_PIDS_AND_KOEO.md` are built.
