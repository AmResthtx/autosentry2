package com.autosentry.app.obd;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Trouble codes the 1999-2003 7.3L Power Stroke PCM can set, in plain English.
 *
 * Titles follow Ford's 7.3L code chart as reproduced on powerstroke.org's
 * "7.3 Trouble Codes List" (1998-2001 wording where 1997 differs), cross-checked
 * against Thoroughbred Diesel's Powerstroke list. 6.0L-only codes (FICM, EGR,
 * glow plug module P0670-P0683) are deliberately left out.
 *
 * Severity and hints are AutoSentry's judgment for an owner deciding whether
 * to keep driving. Hints only name causes that are well known for this engine.
 */
public final class DtcDictionary {

    public static final class Entry {
        public final String title;
        public final DtcSeverity severity;
        /** Likely causes or what it means for the owner; null when there's nothing useful to add. */
        public final String hint;

        Entry(String title, DtcSeverity severity, String hint) {
            this.title = title;
            this.severity = severity;
            this.hint = hint;
        }
    }

    private static final String UVCH = "Check the injector, its connector, and the under-valve-cover harness.";
    private static final String UVCH_BANK = "Powers a whole bank of injectors (4 cylinders). "
            + "The under-valve-cover harness or the injector driver module are the usual suspects.";
    private static final String GLOW = "Glow plug relay, glow plugs, or the under-valve-cover harness. Expect hard cold starts.";
    private static final String CMP = "Camshaft position sensor. A failing one can stall the truck or keep it from "
            + "starting. A common 7.3L failure.";
    private static final String ICP = "Injection control pressure (high-pressure oil). Usually a high-pressure oil leak, "
            + "a sticking IPR valve, a bad ICP sensor, or low engine oil. Can cause stalling, no start, or low power.";
    private static final String ICP_SENSOR = "ICP sensor or its connector (oil in the connector is common). "
            + "The computer falls back to a default pressure, so it may idle and run poorly.";
    private static final String IDM = "Injector driver module or its wiring. Can cause rough running, stalling, or no start.";
    private static final String EOT = "Engine oil temperature sensor. AutoSentry's oil-life estimate runs on this "
            + "reading, so treat oil life as unreliable until it's fixed.";
    private static final String EBP = "Exhaust back pressure sensor or its tube, which often plugs with soot.";
    private static final String BOOST = "Boost leak (intake boots and clamps), the MAP sensor hose, or the turbo. Low power until fixed.";
    private static final String CHARGING = "Check the batteries, cables, and alternator. The 7.3L needs strong batteries to start.";
    private static final String TRANS = "Transmission. Shifting may be harsh, late, or erratic.";
    private static final String SELF_TEST = "Set during a self-test, not while driving.";

    private static final Map<String, Entry> CODES;

    static {
        Map<String, Entry> m = new HashMap<>();

        // ---- Sensors and engine inputs ----
        put(m, "P0107", DtcSeverity.MEDIUM, "Barometric pressure sensor circuit low", "Affects fueling and boost calculations.");
        put(m, "P0108", DtcSeverity.MEDIUM, "Barometric pressure sensor circuit high", "Affects fueling and boost calculations.");
        put(m, "P0112", DtcSeverity.MEDIUM, "Intake air temperature sensor circuit low", null);
        put(m, "P0113", DtcSeverity.MEDIUM, "Intake air temperature sensor circuit high", null);
        put(m, "P0122", DtcSeverity.HIGH, "Accelerator pedal sensor circuit low", "Pedal sensor or wiring. Can cause poor or no throttle response.");
        put(m, "P0123", DtcSeverity.HIGH, "Accelerator pedal sensor circuit high", "Pedal sensor or wiring. Can cause poor or no throttle response.");
        put(m, "P0195", DtcSeverity.MEDIUM, "Engine oil temperature outside self-test limits", EOT);
        put(m, "P0196", DtcSeverity.MEDIUM, "Engine oil temperature sensor performance", EOT);
        put(m, "P0197", DtcSeverity.MEDIUM, "Engine oil temperature sensor circuit low", EOT);
        put(m, "P0198", DtcSeverity.MEDIUM, "Engine oil temperature sensor circuit high", EOT);
        put(m, "P1184", DtcSeverity.MEDIUM, "Engine oil temperature sensor performance", EOT);
        put(m, "P0219", DtcSeverity.MEDIUM, "Engine over-revved", "Usually a missed shift. Repeated over-revs risk engine damage.");
        put(m, "P0220", DtcSeverity.MEDIUM, "Idle validation switch fault", "Part of the accelerator pedal assembly.");
        put(m, "P0221", DtcSeverity.MEDIUM, "Idle validation switch fault", "Part of the accelerator pedal assembly.");
        put(m, "P0230", DtcSeverity.HIGH, "Fuel pump monitor circuit", "Fuel pump relay or circuit. A weak or dead fuel pump can cause stalling or no start.");
        put(m, "P0231", DtcSeverity.HIGH, "Fuel pump monitor circuit low", "Fuel pump relay or circuit. A weak or dead fuel pump can cause stalling or no start.");
        put(m, "P0232", DtcSeverity.HIGH, "Fuel pump monitor circuit high", "Fuel pump relay or circuit. A weak or dead fuel pump can cause stalling or no start.");
        put(m, "P0235", DtcSeverity.MEDIUM, "Boost (MAP) sensor signal inactive", BOOST);
        put(m, "P0236", DtcSeverity.MEDIUM, "Boost (MAP) sensor performance",
                "Often the MAP sensor hose is cracked, plugged, or full of oil. Low power until fixed.");
        put(m, "P0237", DtcSeverity.MEDIUM, "Boost (MAP) sensor circuit low", null);
        put(m, "P0238", DtcSeverity.MEDIUM, "Boost (MAP) sensor circuit high", null);
        put(m, "P0460", DtcSeverity.LOW, "Fuel level gauge circuit", null);
        put(m, "P0500", DtcSeverity.MEDIUM, "Vehicle speed sensor fault",
                "The speedometer and shifting may act up, and AutoSentry's mileage can undercount while the truck reports a wrong speed.");
        put(m, "P1118", DtcSeverity.MEDIUM, "Manifold air temperature sensor fault", null);
        put(m, "P1119", DtcSeverity.MEDIUM, "Manifold air temperature sensor fault", null);

        // ---- Injection control pressure (high-pressure oil) ----
        put(m, "P1209", DtcSeverity.HIGH, "Injection control pressure peak fault", ICP);
        put(m, "P1210", DtcSeverity.HIGH, "Injection control pressure above expected", ICP);
        put(m, "P1211", DtcSeverity.HIGH, "Injection control pressure not controllable (above or below desired)", ICP);
        put(m, "P1212", DtcSeverity.HIGH, "ICP sensor voltage not at expected level", ICP_SENSOR);
        put(m, "P1280", DtcSeverity.HIGH, "ICP sensor circuit low", ICP_SENSOR);
        put(m, "P1281", DtcSeverity.HIGH, "ICP sensor circuit high", ICP_SENSOR);
        put(m, "P1282", DtcSeverity.HIGH, "Excessive injection control pressure", "IPR valve sticking, or the ICP sensor reading wrong.");
        put(m, "P1283", DtcSeverity.HIGH, "IPR valve circuit failure", "Injection pressure regulator valve or its wiring. Can cause no start.");
        put(m, "P1284", DtcSeverity.MEDIUM, "Self-test aborted: ICP fault", ICP);

        // ---- Injectors, one entry per cylinder ----
        for (int cyl = 1; cyl <= 8; cyl++) {
            int base = 261 + (cyl - 1) * 3;
            put(m, "P0" + base, DtcSeverity.MEDIUM, "Cylinder " + cyl + " injector circuit low", UVCH);
            put(m, "P0" + (base + 1), DtcSeverity.MEDIUM, "Cylinder " + cyl + " injector circuit high", UVCH);
            put(m, "P0" + (base + 2), DtcSeverity.MEDIUM, "Cylinder " + cyl + " contribution fault",
                    "Found during a cylinder contribution test. That injector may be weak.");
            put(m, "P030" + cyl, DtcSeverity.MEDIUM, "Cylinder " + cyl + " injector driver circuit",
                    "On 2001 and earlier 7.3Ls this is an injector driver code, not a misfire. " + UVCH);
            put(m, "P126" + cyl, DtcSeverity.MEDIUM, "Cylinder " + cyl + " injector high side shorted to low side", UVCH);
            put(m, "P127" + cyl, DtcSeverity.MEDIUM, "Cylinder " + cyl + " injector high side open to low side",
                    "Open circuit, often a loose under-valve-cover harness connector.");
        }
        put(m, "P1291", DtcSeverity.HIGH, "Right bank injector power shorted to ground or battery", UVCH_BANK);
        put(m, "P1292", DtcSeverity.HIGH, "Left bank injector power shorted to ground or battery", UVCH_BANK);
        put(m, "P1293", DtcSeverity.HIGH, "Right bank injector power circuit open", UVCH_BANK);
        put(m, "P1294", DtcSeverity.HIGH, "Left bank injector power circuit open", UVCH_BANK);
        put(m, "P1295", DtcSeverity.HIGH, "Multiple injector faults, right bank", UVCH_BANK);
        put(m, "P1296", DtcSeverity.HIGH, "Multiple injector faults, left bank", UVCH_BANK);
        put(m, "P1297", DtcSeverity.HIGH, "Injector bank power circuits shorted together", UVCH_BANK);
        put(m, "P1316", DtcSeverity.MEDIUM, "Injector circuit or injector driver module codes detected",
                "The injector driver module has its own fault codes; a full self-test (FORScan or a Ford scan tool) reads them. "
                        + "A common cause is the under-valve-cover harness coming unplugged.");

        // ---- Injector driver module (IDM) and cam signal ----
        put(m, "P1218", DtcSeverity.HIGH, "Cylinder ID signal stuck high", "Camshaft signal to the injector driver module. " + CMP);
        put(m, "P1219", DtcSeverity.HIGH, "Cylinder ID signal stuck low", "Camshaft signal to the injector driver module. " + CMP);
        put(m, "P1298", DtcSeverity.HIGH, "Injector driver module failure", IDM);
        put(m, "P1660", DtcSeverity.HIGH, "Injector driver module output check signal high", IDM);
        put(m, "P1661", DtcSeverity.HIGH, "Injector driver module output check signal low", IDM);
        put(m, "P1662", DtcSeverity.HIGH, "Injector driver module enable circuit", IDM);
        put(m, "P1663", DtcSeverity.HIGH, "Fuel delivery command signal circuit", IDM);
        put(m, "P1667", DtcSeverity.HIGH, "Cylinder ID circuit fault", IDM);
        put(m, "P1668", DtcSeverity.MEDIUM, "Engine computer to injector driver module communication error", IDM);
        put(m, "P1670", DtcSeverity.MEDIUM, "Injector driver module feedback signal not detected", IDM);
        put(m, "P0340", DtcSeverity.HIGH, "Camshaft position sensor circuit", CMP);
        put(m, "P0341", DtcSeverity.HIGH, "Camshaft position sensor performance", CMP);
        put(m, "P0344", DtcSeverity.HIGH, "Camshaft position sensor intermittent", CMP);

        // ---- Glow plugs and intake air heater ----
        put(m, "P0380", DtcSeverity.MEDIUM, "Glow plug circuit malfunction", GLOW);
        put(m, "P0381", DtcSeverity.LOW, "Wait-to-start lamp circuit", "The glow plug indicator light circuit, not the glow plugs themselves.");
        put(m, "P1391", DtcSeverity.MEDIUM, "Glow plug circuit low, right bank", GLOW);
        put(m, "P1392", DtcSeverity.MEDIUM, "Glow plug circuit high, right bank", GLOW);
        put(m, "P1393", DtcSeverity.MEDIUM, "Glow plug circuit low, left bank", GLOW);
        put(m, "P1394", DtcSeverity.MEDIUM, "Glow plug circuit high, left bank", GLOW);
        put(m, "P1395", DtcSeverity.MEDIUM, "Glow plug monitor fault, right bank", GLOW);
        put(m, "P1396", DtcSeverity.MEDIUM, "Glow plug monitor fault, left bank", GLOW);
        put(m, "P0541", DtcSeverity.MEDIUM, "Intake air heater circuit low", "Cold-start aid. Expect harder cold starts.");
        put(m, "P0542", DtcSeverity.MEDIUM, "Intake air heater circuit high", "Cold-start aid. Expect harder cold starts.");
        put(m, "P0640", DtcSeverity.MEDIUM, "Intake air heater control circuit", "Cold-start aid. Expect harder cold starts.");

        // ---- Turbo and exhaust back pressure ----
        put(m, "P1247", DtcSeverity.MEDIUM, "Turbo boost pressure low", BOOST);
        put(m, "P1248", DtcSeverity.MEDIUM, "Turbo boost pressure not detected", BOOST);
        put(m, "P1249", DtcSeverity.MEDIUM, "Wastegate control solenoid", null);
        put(m, "P1250", DtcSeverity.MEDIUM, "Wastegate control solenoid", null);
        put(m, "P1690", DtcSeverity.MEDIUM, "Wastegate control valve", null);
        put(m, "P0470", DtcSeverity.MEDIUM, "Exhaust back pressure sensor circuit", EBP);
        put(m, "P0471", DtcSeverity.MEDIUM, "Exhaust back pressure sensor performance", EBP);
        put(m, "P0472", DtcSeverity.MEDIUM, "Exhaust back pressure sensor circuit low", EBP);
        put(m, "P0473", DtcSeverity.MEDIUM, "Exhaust back pressure sensor circuit high", EBP);
        put(m, "P0475", DtcSeverity.MEDIUM, "Exhaust back pressure valve malfunction", "The warm-up valve on the turbo outlet.");
        put(m, "P0476", DtcSeverity.MEDIUM, "Exhaust back pressure valve performance", "The warm-up valve on the turbo outlet.");
        put(m, "P0478", DtcSeverity.MEDIUM, "Exhaust back pressure too high", EBP);

        // ---- Fuel ----
        put(m, "P1139", DtcSeverity.LOW, "Water-in-fuel sensor circuit", "The sensor circuit, not water itself. Drain the fuel bowl to be safe.");
        put(m, "P1140", DtcSeverity.HIGH, "Water in fuel detected",
                "Drain the water separator on the fuel filter housing soon. Water damages injectors.");

        // ---- Charging and electrical ----
        put(m, "P0560", DtcSeverity.MEDIUM, "System voltage malfunction", CHARGING);
        put(m, "P0562", DtcSeverity.MEDIUM, "System voltage low", CHARGING);
        put(m, "P0563", DtcSeverity.MEDIUM, "System voltage high", "Alternator overcharging can damage electronics.");
        put(m, "P1105", DtcSeverity.MEDIUM, "Charging system concern", CHARGING);
        put(m, "P1106", DtcSeverity.MEDIUM, "Charging system concern", CHARGING);
        put(m, "P1107", DtcSeverity.MEDIUM, "Charging system concern", CHARGING);
        put(m, "P1108", DtcSeverity.MEDIUM, "Charging system concern (battery lamp circuit)", CHARGING);
        put(m, "P0603", DtcSeverity.LOW, "Keep-alive memory lost",
                "Usually set after the batteries were disconnected. Clear it and see if it comes back.");
        put(m, "P0605", DtcSeverity.HIGH, "Engine computer memory (ROM) fault", "Engine computer (PCM) fault.");
        put(m, "P0606", DtcSeverity.HIGH, "Engine computer processor fault", "Engine computer (PCM) fault.");

        // ---- Cruise, brake, and clutch switches ----
        put(m, "P0565", DtcSeverity.LOW, "Cruise control ON switch signal", null);
        put(m, "P0566", DtcSeverity.LOW, "Cruise control OFF switch signal", null);
        put(m, "P0567", DtcSeverity.LOW, "Cruise control RESUME switch signal", null);
        put(m, "P0568", DtcSeverity.LOW, "Cruise control SET switch signal", null);
        put(m, "P0569", DtcSeverity.LOW, "Cruise control COAST switch signal", null);
        put(m, "P0571", DtcSeverity.LOW, "Brake switch A circuit", "Affects cruise control and torque converter lockup.");
        put(m, "P0703", DtcSeverity.LOW, "Brake switch B circuit", "Affects cruise control and torque converter lockup.");
        put(m, "P0704", DtcSeverity.LOW, "Clutch pedal switch circuit", null);

        // ---- 4R100 automatic transmission ----
        put(m, "P0705", DtcSeverity.MEDIUM, "Transmission range sensor circuit", "Can show the wrong gear or keep the truck from starting in Park.");
        put(m, "P0707", DtcSeverity.MEDIUM, "Transmission range sensor circuit low", "Can show the wrong gear or keep the truck from starting in Park.");
        put(m, "P0708", DtcSeverity.MEDIUM, "Transmission range sensor circuit high", "Can show the wrong gear or keep the truck from starting in Park.");
        put(m, "P0712", DtcSeverity.MEDIUM, "Transmission fluid temperature sensor circuit low", null);
        put(m, "P0713", DtcSeverity.MEDIUM, "Transmission fluid temperature sensor circuit high", null);
        put(m, "P0715", DtcSeverity.MEDIUM, "Turbine speed sensor circuit", TRANS);
        put(m, "P0717", DtcSeverity.MEDIUM, "Turbine speed sensor intermittent", TRANS);
        put(m, "P0718", DtcSeverity.MEDIUM, "Turbine speed sensor noisy", TRANS);
        put(m, "P0720", DtcSeverity.MEDIUM, "Output shaft speed sensor circuit", TRANS);
        put(m, "P0721", DtcSeverity.MEDIUM, "Output shaft speed sensor noisy", TRANS);
        put(m, "P0722", DtcSeverity.MEDIUM, "Output shaft speed sensor intermittent", TRANS);
        put(m, "P0732", DtcSeverity.MEDIUM, "Second gear ratio error", "The transmission may be slipping.");
        put(m, "P0733", DtcSeverity.MEDIUM, "Third gear ratio error", "The transmission may be slipping.");
        put(m, "P0741", DtcSeverity.MEDIUM, "Torque converter clutch circuit performance", TRANS);
        put(m, "P0743", DtcSeverity.MEDIUM, "Torque converter clutch electrical failure", TRANS);
        put(m, "P0750", DtcSeverity.MEDIUM, "Shift solenoid A fault", TRANS);
        put(m, "P0751", DtcSeverity.MEDIUM, "Shift solenoid A performance", TRANS);
        put(m, "P0755", DtcSeverity.MEDIUM, "Shift solenoid B fault", TRANS);
        put(m, "P0756", DtcSeverity.MEDIUM, "Shift solenoid B performance", TRANS);
        put(m, "P0760", DtcSeverity.MEDIUM, "Shift solenoid C malfunction", TRANS);
        put(m, "P0781", DtcSeverity.MEDIUM, "1-2 shift malfunction", TRANS);
        put(m, "P0782", DtcSeverity.MEDIUM, "2-3 shift malfunction", TRANS);
        put(m, "P0783", DtcSeverity.MEDIUM, "3-4 shift malfunction", TRANS);
        put(m, "P1702", DtcSeverity.MEDIUM, "Transmission range sensor intermittent", TRANS);
        put(m, "P1704", DtcSeverity.MEDIUM, "Transmission range sensor failed to change state", TRANS);
        put(m, "P1705", DtcSeverity.LOW, "Transmission range sensor out of self-test range",
                SELF_TEST + " Often the shifter wasn't in Park or Neutral.");
        put(m, "P1706", DtcSeverity.MEDIUM, "High vehicle speed seen in Park", TRANS);
        put(m, "P1711", DtcSeverity.LOW, "Transmission fluid temperature out of self-test range",
                SELF_TEST + " Often the transmission wasn't warmed up.");
        put(m, "P1713", DtcSeverity.MEDIUM, "Transmission fluid temperature sensor stuck low", null);
        put(m, "P1718", DtcSeverity.MEDIUM, "Transmission fluid temperature sensor stuck high", null);
        put(m, "P1714", DtcSeverity.MEDIUM, "Shift solenoid A electrical signature fault", TRANS);
        put(m, "P1715", DtcSeverity.MEDIUM, "Shift solenoid B electrical signature fault", TRANS);
        put(m, "P1727", DtcSeverity.MEDIUM, "Coast clutch solenoid electrical signature fault", TRANS);
        put(m, "P1728", DtcSeverity.HIGH, "Transmission slip detected", "Driving hard or towing on a slipping transmission makes it worse.");
        put(m, "P1729", DtcSeverity.LOW, "4x4 Low switch error", null);
        put(m, "P1740", DtcSeverity.MEDIUM, "Torque converter clutch electrical signature fault", TRANS);
        put(m, "P1742", DtcSeverity.MEDIUM, "Torque converter clutch stuck on", TRANS);
        put(m, "P1744", DtcSeverity.MEDIUM, "Torque converter clutch performance", TRANS);
        put(m, "P1746", DtcSeverity.MEDIUM, "Line pressure (EPC) solenoid open circuit", "Expect harsh or erratic shifts.");
        put(m, "P1747", DtcSeverity.MEDIUM, "Line pressure (EPC) solenoid short circuit", "Expect harsh or erratic shifts.");
        put(m, "P1748", DtcSeverity.MEDIUM, "Line pressure (EPC) malfunction", "Expect harsh or erratic shifts.");
        put(m, "P1751", DtcSeverity.MEDIUM, "Shift solenoid A performance", TRANS);
        put(m, "P1754", DtcSeverity.MEDIUM, "Coast clutch solenoid circuit", TRANS);
        put(m, "P1756", DtcSeverity.MEDIUM, "Shift solenoid B performance", TRANS);
        put(m, "P1760", DtcSeverity.MEDIUM, "Line pressure (EPC) solenoid short, intermittent", "Expect harsh or erratic shifts.");
        put(m, "P1779", DtcSeverity.LOW, "Overdrive-off lamp circuit", null);
        put(m, "P1780", DtcSeverity.LOW, "Overdrive cancel button circuit out of self-test range",
                SELF_TEST + " Often the overdrive button wasn't pressed during the test.");
        put(m, "P1781", DtcSeverity.LOW, "4x4 Low circuit out of self-test range", SELF_TEST);
        put(m, "P1783", DtcSeverity.HIGH, "Transmission overheated",
                "Ease off, let it cool, and check the fluid level and condition. Heat kills transmissions.");
        put(m, "P1902", DtcSeverity.MEDIUM, "Kickdown solenoid relay", TRANS);
        put(m, "P1903", DtcSeverity.MEDIUM, "Kickdown solenoid relay", TRANS);
        put(m, "P1904", DtcSeverity.MEDIUM, "Kickdown solenoid relay", TRANS);

        // ---- Status and self-test messages ----
        put(m, "P1000", DtcSeverity.INFO, "Readiness monitors not complete yet",
                "Not a fault. It shows after codes are cleared or the batteries are disconnected, until the truck has been driven enough.");
        put(m, "P1111", DtcSeverity.INFO, "System pass", "No faults found.");
        put(m, "P1397", DtcSeverity.INFO, "Battery voltage out of range during self-test", SELF_TEST);
        put(m, "P1464", DtcSeverity.INFO, "A/C was on during self-test", SELF_TEST);
        put(m, "P1501", DtcSeverity.INFO, "Truck moved during self-test", SELF_TEST);
        put(m, "P1502", DtcSeverity.INFO, "Invalid self-test", SELF_TEST);
        put(m, "P1531", DtcSeverity.INFO, "Accelerator pedal moved during self-test", SELF_TEST);
        put(m, "P1536", DtcSeverity.INFO, "Parking brake applied during self-test", SELF_TEST);

        CODES = Collections.unmodifiableMap(m);
    }

    private DtcDictionary() {}

    private static void put(Map<String, Entry> m, String code, DtcSeverity severity, String title, String hint) {
        m.put(code, new Entry(title, severity, hint));
    }

    /** The 7.3L entry for a code like "P1211", or null when it isn't in the list. */
    public static Entry lookup(String code) {
        return code == null ? null : CODES.get(code);
    }

    public static int size() {
        return CODES.size();
    }

    static Map<String, Entry> all() {
        return CODES;
    }
}
