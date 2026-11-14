#!/usr/bin/env python3
"""
OBD-II / enhanced-PID probe for the 2000 F-250 7.3L Power Stroke.

Purpose: find out, empirically, what this specific truck's PCM actually
answers, instead of guessing from forum posts written for other trucks.
Talks straight to the ELM327/OBDLink adapter over its Bluetooth SPP COM
port (no phone/app involved) using the same AT init AutoSentry uses.

Usage:
    Discovery options (find PIDs the app can't read yet):
       python tools/obd_probe.py --pids pids.txt      # try a list of 4-hex-digit Mode 22 PIDs
       python tools/obd_probe.py --sweep 1000 1500    # brute-force Mode 22 range (hex)
       python tools/obd_probe.py --catalog            # print ready-to-paste catalog lines

    Clean-room rule: the PID list must come from public sources (forum posts, open-source
    projects with a licence that allows it, SAE documents) or from this truck's own
    answers. Never feed it another scan tool's PID, module or configuration files.
    Comparing a reading with the number another tool shows on its screen, to check our
    own formula, is fine.

    Adding a reading to the app from a capture:
      1. Run with --catalog (plus --pids or --sweep). Every PID the PCM answers prints
         a `ford(...)` line for PidCatalog.java and a table row for docs/PROVENANCE.md,
         both with the raw bytes captured.
      2. Work out the formula (key on, engine off, a cold-soaked truck gives known
         values: oil temp = outside temp, MAP = BARO = local barometer).
      3. Paste the line, fill in the name, unit and formula, set the trust level, and
         paste the row into docs/PROVENANCE.md with where the formula came from.

    1. Pair the OBD adapter to this Windows machine (Settings > Bluetooth),
       key the truck on (accessory or running).
    2. python tools/obd_probe.py            # auto-detects the COM port
       python tools/obd_probe.py COM7       # or name it explicitly

Output: printed to the console AND written to obd_probe_log.txt (in this
folder) with timestamps, so nothing has to be re-run to review results.
"""
import sys
import time
import re
import datetime
from pathlib import Path

try:
    import serial
    from serial.tools import list_ports
except ImportError:
    print("Missing dependency. Run: python -m pip install pyserial")
    sys.exit(1)

LOG_PATH = Path(__file__).parent / "obd_probe_log.txt"
_log_lines = []


def log(line=""):
    ts = datetime.datetime.now().strftime("%H:%M:%S")
    text = f"[{ts}] {line}" if line else ""
    print(text)
    _log_lines.append(text)
    # Written as it goes: a dropped Bluetooth link or Ctrl-C loses nothing.
    with LOG_PATH.open("a", encoding="utf-8") as f:
        f.write(text + "\n")


def flush_log():
    pass  # kept for callers; log() already writes each line


PCM_HEADER = "ATSHC410F1"   # Ford's physical address for the PCM on J1850 PWM
OBD_HEADER = "ATSH616AF1"   # the standard OBD-II functional header


def classify_mode22(resp, pid_hex):
    """What the PCM said to 22 <pid>: ('data', bytes), ('refused', nrc), or ('silent', raw lines).

    A refusal on this PCM is 7F 22 <pid> 00 <reason>: the reason is the last byte.
    Reason 12 means the PCM doesn't have that PID. Silence and a refusal are different
    findings and are reported separately.
    """
    pid_hex = pid_hex.upper()
    for raw in clean_lines(resp):
        line = re.sub(r"\s", "", raw).upper()
        if line.startswith("62" + pid_hex):
            payload = line[6:]
            if payload and len(payload) % 2 == 0:
                try:
                    return "data", [int(payload[i:i+2], 16) for i in range(0, len(payload), 2)]
                except ValueError:
                    pass
        if line.startswith("7F22") and len(line) >= 6:
            return "refused", line[-2:]
    return "silent", clean_lines(resp)


def read_mode22(adapter, pids, catalog=False):
    """Asks the PCM for each (label, pid) under the PCM header, then restores the OBD header."""
    answered = []
    ok = "OK" in adapter.command(PCM_HEADER).upper()
    if not ok:
        log("  The adapter refused the PCM header; nothing was sent to the truck.")
        return answered
    try:
        for label, pid_hex in pids:
            resp = adapter.command(f"22{pid_hex}", timeout=2)
            kind, detail = classify_mode22(resp, pid_hex)
            if kind == "data":
                raw = " ".join(f"{b:02X}" for b in detail)
                word = detail[0] * 256 + detail[1] if len(detail) >= 2 else detail[0]
                log(f"  {label:40s} [22{pid_hex}] ANSWERS: {raw}  (as a number: {word})")
                answered.append((label, pid_hex, detail))
            elif kind == "refused":
                log(f"  {label:40s} [22{pid_hex}] refused, reason {detail}"
                    + (" (the PCM doesn't have this PID)" if detail == "12" else ""))
            else:
                log(f"  {label:40s} [22{pid_hex}] no answer {detail}")
    finally:
        if "OK" not in adapter.command(OBD_HEADER).upper():
            log("  WARNING: the adapter did not restore the OBD header. Unplug it to reset.")
    if catalog and answered:
        today = datetime.date.today().isoformat()
        log("\n--- Paste into PidCatalog.java (fill in NAME, UNIT and the formula) ---")
        for label, pid_hex, data in answered:
            n = min(len(data), 2)
            decode = "b -> word(b) * 1.0" if n == 2 else "b -> b[0] * 1.0"
            log(f'        ford(FORD_NEW_{pid_hex}, "{pid_hex}", {n}, "NAME (unverified)", "UNIT", "%.1f", {decode})')
            log(f'                .formula("FORMULA").from(Trust.UNVERIFIED, "SOURCE");')
        log("\n--- Paste into docs/PROVENANCE.md (captures table) ---")
        for label, pid_hex, data in answered:
            raw = " ".join(f"{b:02X}" for b in data)
            log(f"| 22 {pid_hex[:2]} {pid_hex[2:]} | {raw} | {today} | CONDITIONS (key on engine off? idle? temp?) | KNOWN-GOOD VALUE AND WHERE IT CAME FROM |")
    return answered


def find_adapter_port():
    """Looks for a Bluetooth SPP COM port that looks like an OBD adapter."""
    candidates = []
    for p in list_ports.comports():
        desc = f"{p.description} {p.hwid}".lower()
        if "bluetooth" in desc or "spp" in desc or "rfcomm" in desc:
            candidates.append(p)
    if len(candidates) == 1:
        return candidates[0].device
    if candidates:
        log("Multiple Bluetooth COM ports found:")
        for i, p in enumerate(candidates):
            log(f"  [{i}] {p.device}  {p.description}")
        choice = input("Pick one by number: ").strip()
        return candidates[int(choice)].device
    return None


class Adapter:
    def __init__(self, port, baud=38400, timeout=3):
        self.ser = serial.Serial(port, baudrate=baud, timeout=timeout)

    def close(self):
        self.ser.close()

    def send(self, cmd):
        self.ser.reset_input_buffer()
        self.ser.write((cmd + "\r").encode())

    def read_response(self, timeout=3.0):
        deadline = time.time() + timeout
        buf = b""
        while time.time() < deadline:
            chunk = self.ser.read(256)
            if chunk:
                buf += chunk
                if b">" in buf:
                    break
            else:
                time.sleep(0.02)
        return buf.decode(errors="replace")

    def command(self, cmd, timeout=3.0):
        self.send(cmd)
        return self.read_response(timeout)


def clean_lines(resp):
    return [l.strip() for l in re.split(r"[\r\n]+", resp) if l.strip() and l.strip() != ">"]


def parse_mode01(resp, pid_hex):
    header = f"41{pid_hex.upper()}"
    for raw in clean_lines(resp):
        line = re.sub(r"\s", "", raw).upper()
        if line.startswith(header):
            payload = line[len(header):]
            if len(payload) >= 2 and len(payload) % 2 == 0:
                try:
                    return [int(payload[i:i+2], 16) for i in range(0, len(payload), 2)]
                except ValueError:
                    continue
    return None


# name, mode01 pid hex, byte count, decode(bytes)->(value, unit)
STANDARD_PIDS = [
    ("Engine RPM", "0C", 2, lambda b: ((b[0]*256+b[1])/4, "rpm")),
    ("Vehicle Speed", "0D", 1, lambda b: (b[0]*0.621371, "mph")),
    ("Coolant Temp", "05", 1, lambda b: (b[0]-40, "C")),
    ("Engine Load", "04", 1, lambda b: (b[0]*100/255, "%")),
    ("Throttle Position", "11", 1, lambda b: (b[0]*100/255, "%")),
    ("Accelerator Pedal D", "49", 1, lambda b: (b[0]*100/255, "%")),
    ("Intake Manifold Pressure", "0B", 1, lambda b: (b[0], "kPa")),
    ("Intake Air Temp", "0F", 1, lambda b: (b[0]-40, "C")),
    ("Mass Air Flow", "10", 2, lambda b: ((b[0]*256+b[1])/100, "g/s")),
    ("Fuel Rate", "5E", 2, lambda b: ((b[0]*256+b[1])/20, "L/h")),
    ("Fuel Pressure", "0A", 1, lambda b: (b[0]*3, "kPa")),
    ("Fuel Rail Pressure", "23", 2, lambda b: ((b[0]*256+b[1])*10, "kPa")),
    ("Fuel Level", "2F", 1, lambda b: (b[0]*100/255, "%")),
    ("Engine Oil Temp", "5C", 1, lambda b: (b[0]-40, "C")),
    ("Barometric Pressure", "33", 1, lambda b: (b[0], "kPa")),
    ("Ambient Air Temp", "46", 1, lambda b: (b[0]-40, "C")),
    ("Module Voltage", "42", 2, lambda b: ((b[0]*256+b[1])/1000, "V")),
    ("Engine Run Time", "1F", 2, lambda b: (b[0]*256+b[1], "s")),
    ("Distance w/ MIL on", "21", 2, lambda b: (b[0]*256+b[1], "km")),
    ("Time since codes cleared", "4E", 2, lambda b: (b[0]*256+b[1], "min")),
    ("Absolute Load", "43", 2, lambda b: ((b[0]*256+b[1])*100/255, "%")),
    ("Control module voltage", "42", 2, lambda b: ((b[0]*256+b[1])/1000, "V")),
]

# Ford enhanced (Mode 22) codes worth trying. Verified ones are from the
# documented 6.7L Power Stroke list (torque-bhp.com); the 7.3L uses an
# older PCM on J1850 PWM instead of CAN, so these are NOT expected to
# work as-is -- they're included because trying costs nothing and a
# shared PID number across Ford generations does happen occasionally.
APP_MODE22_PIDS = [
    ("Engine oil temp", "1310"), ("IPR duty", "1434"), ("Injection control pressure", "1446"),
    ("MAP (unverified)", "1440"), ("BARO (unverified)", "1442"), ("Exhaust back pressure (unverified)", "1445"),
    ("Fuel pulse width (unverified)", "1410"), ("Fuel desired (unverified)", "1412"),
    ("Trans fluid temp (unverified)", "11BD"), ("Code count (unconfirmed)", "0200"), ("Code count (unconfirmed)", "0202"),
]

CANDIDATE_MODE22_PIDS = [
    ("Ambient Air Temp (enhanced)", "F446"),
    ("DPF Regen Status", "F48B"),
    ("DPF Distance Since Regen", "0434"),
    ("DPF Pressure", "116C"),
    ("DPF Soot Mass", "042C"),
    ("EGT bank (11-14)", "F478"),
    ("Engine Oil Temp (enhanced)", "F45C"),
    ("Fuel Tank Level (enhanced)", "F42F"),
    ("Transmission Fluid Temp", "1E1C"),
    ("IPR Duty Cycle (forum guess)", "1130"),
]


def pid_list_candidates(path):
    """Mode 22 PIDs from a text file you wrote from public sources.

    Any 4-hex-digit token is a candidate (e.g. 'ICP 1446' or 'PID 0x1310'). Only whether
    the PCM answers is trusted, never a scaling from the file. The raw bytes are printed
    so the formula can be worked out on the truck. Do not use another scan tool's PID or
    configuration files here (clean-room rule, see the top of this file).
    """
    found = []
    for line in Path(path).read_text(errors="replace").splitlines():
        for m in re.finditer(r"(?<![0-9A-Fa-f])(?:0x)?([0-9A-Fa-f]{4})(?![0-9A-Fa-f])", line):
            pid = m.group(1).upper()
            label = re.sub(r"\s+", " ", line.strip())[:40]
            if pid not in [p for _, p in found]:
                found.append((label, pid))
    return found


def sweep_mode22(adapter, start, end, catalog=False):
    """Asks the PCM for every Mode 22 PID in [start, end]; reports answers and refusals apart."""
    log(f"\n=== Mode 22 sweep {start:04X}-{end:04X} (to the PCM) ===")
    hits = read_mode22(adapter, [("", f"{pid:04X}") for pid in range(start, end + 1)], catalog)
    log(f"Sweep done: {len(hits)} PID(s) answered: {' '.join(p for _, p, _ in hits)}")
    return hits


def main():
    # Optional flags: --pids FILE (try a public-source PID list), --sweep START END (hex), --catalog
    args = sys.argv[1:]
    pid_file = None
    sweep = None
    catalog = "--catalog" in args
    if catalog:
        args.remove("--catalog")
    if "--pids" in args:
        i = args.index("--pids")
        pid_file = args[i + 1]
        del args[i:i + 2]
    if "--sweep" in args:
        i = args.index("--sweep")
        sweep = (int(args[i + 1], 16), int(args[i + 2], 16))
        del args[i:i + 3]
    port = args[0] if args else find_adapter_port()
    if not port:
        log("No Bluetooth COM port found/paired. Pair the adapter in Windows "
            "Settings > Bluetooth first, then re-run this script (or pass the "
            "COM port name directly, e.g. `python obd_probe.py COM7`).")
        flush_log()
        return

    log(f"Connecting to {port} ...")
    try:
        adapter = Adapter(port)
    except Exception as e:
        log(f"Could not open {port}: {e}")
        flush_log()
        return

    try:
        log("Resetting adapter...")
        log(adapter.command("ATZ", timeout=5))
        time.sleep(0.3)
        for cmd in ["ATE0", "ATL0", "ATS0", "ATH0", "ATSP0"]:
            adapter.command(cmd)

        log("Waking up the truck's bus (first query can take a while)...")
        proto_resp = adapter.command("0100", timeout=15)
        log(f"First query raw response: {proto_resp!r}")
        proto = adapter.command("ATDP")
        log(f"Protocol in use: {clean_lines(proto)}")

        vin_resp = adapter.command("0902", timeout=5)
        log(f"VIN raw response: {clean_lines(vin_resp)}")

        # --- supported PID bitmap scan (what your truck says it can do) ---
        log("\n=== Standard Mode 01 'supported PIDs' bitmap scan ===")
        supported = set()
        for base in (0x00, 0x20, 0x40, 0x60, 0x80):
            resp = adapter.command(f"01{base:02X}", timeout=10 if base == 0 else 3)
            data = parse_mode01(resp, f"{base:02X}")
            if not data or len(data) < 4:
                log(f"  PID {base:02X}: no answer, stopping scan")
                break
            mask = (data[0] << 24) | (data[1] << 16) | (data[2] << 8) | data[3]
            page = [base + i + 1 for i in range(32) if mask & (1 << (31 - i))]
            supported.update(page)
            log(f"  PIDs {base:02X}-{base+0x20:02X}: {' '.join(f'{p:02X}' for p in page)}")
            if (base + 0x20) not in supported:
                break

        log(f"\nTotal standard PIDs supported: {len(supported)}")
        log(" ".join(f"{p:02X}" for p in sorted(supported)))

        # --- read every supported standard PID we know how to decode ---
        log("\n=== Reading every supported standard PID ===")
        for name, pid_hex, nbytes, decode in STANDARD_PIDS:
            pid_int = int(pid_hex, 16)
            if pid_int not in supported:
                log(f"  {name:30s} [{pid_hex}] NOT SUPPORTED (truck didn't advertise it)")
                continue
            resp = adapter.command(f"01{pid_hex}")
            data = parse_mode01(resp, pid_hex)
            if data and len(data) >= nbytes:
                value, unit = decode(data)
                log(f"  {name:30s} [{pid_hex}] = {value:.2f} {unit}   (raw {data})")
            else:
                log(f"  {name:30s} [{pid_hex}] advertised as supported but NO DATA back "
                    f"(raw: {clean_lines(resp)})")

        # --- the Ford readings the app uses, then the unverified candidates ---
        log("\n=== Ford (Mode 22) PIDs AutoSentry reads, asked of the PCM ===")
        read_mode22(adapter, APP_MODE22_PIDS, catalog)
        log("\n=== Other Ford (Mode 22) candidates ===")
        log("(unverified for the 7.3L -- most will be refused, that's expected and fine)")
        read_mode22(adapter, CANDIDATE_MODE22_PIDS, catalog)

        if pid_file:
            log(f"\n=== Trying PIDs listed in {pid_file} ===")
            read_mode22(adapter, pid_list_candidates(pid_file), catalog)

        if sweep:
            sweep_mode22(adapter, *sweep, catalog=catalog)

        # --- Ford continuous-memory codes (service 13 to the PCM), raw ---
        log("\n=== Ford memory codes (13 to the PCM) ===")
        if "OK" in adapter.command(PCM_HEADER).upper():
            try:
                log(f"  raw reply: {clean_lines(adapter.command('13', timeout=6))}")
                log("  Expected if it works: lines starting 53, then two bytes per code, padded with 00 00.")
                log("  A line starting 7F 13 is a refusal. Send this log either way.")
            finally:
                adapter.command(OBD_HEADER)

        # --- stored diagnostic codes, since we're already connected ---
        log("\n=== Stored DTCs (Mode 03) ===")
        log(clean_lines(adapter.command("03")))

    finally:
        adapter.close()
        flush_log()
        log(f"\nFull log written to {LOG_PATH}")


if __name__ == "__main__":
    main()
