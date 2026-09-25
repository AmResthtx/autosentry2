#!/usr/bin/env python3
"""
OBD-II / enhanced-PID probe for the 2000 F-250 7.3L Power Stroke.

Purpose: find out, empirically, what this specific truck's PCM actually
answers, instead of guessing from forum posts written for other trucks.
Talks straight to the ELM327/OBDLink adapter over its Bluetooth SPP COM
port (no phone/app involved) using the same AT init AutoSentry uses.

Usage:
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


def flush_log():
    LOG_PATH.write_text("\n".join(_log_lines) + "\n", encoding="utf-8")


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
    # 7.3L-specific guesses seen referenced (unverified) in Diesel Stop /
    # PowerStrokeNation threads discussing ICP/IPR:
    ("ICP (guess)", "1440"),
    ("ICP (guess 2)", "1442"),
    ("IPR Duty Cycle (guess)", "1130"),
]


def main():
    port = sys.argv[1] if len(sys.argv) > 1 else find_adapter_port()
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

        # --- try the unverified Ford enhanced codes ---
        log("\n=== Trying Ford enhanced (Mode 22) candidate codes ===")
        log("(unverified for the 7.3L -- most will fail, that's expected and fine)")
        for name, pid_hex in CANDIDATE_MODE22_PIDS:
            resp = adapter.command(f"22{pid_hex}")
            lines = clean_lines(resp)
            failed_markers = ("NO DATA", "?", "UNABLE TO CONNECT", "ERROR")
            if any(m in resp.upper() for m in failed_markers) or not lines:
                log(f"  {name:30s} [22{pid_hex}] no answer")
            else:
                log(f"  {name:30s} [22{pid_hex}] RAW RESPONSE: {lines}  <-- looks live, investigate")

        # --- stored diagnostic codes, since we're already connected ---
        log("\n=== Stored DTCs (Mode 03) ===")
        log(clean_lines(adapter.command("03")))

    finally:
        adapter.close()
        flush_log()
        log(f"\nFull log written to {LOG_PATH}")


if __name__ == "__main__":
    main()
