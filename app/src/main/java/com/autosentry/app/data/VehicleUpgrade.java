package com.autosentry.app.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Vehicle profile extended: tracks aftermarket upgrades that affect diagnostics,
 * maintenance intervals, and PID baseline expectations.
 *
 * The app starts assuming factory stock (2000 F-250 7.3L Power Stroke).
 * Over time, if user hasn't entered upgrade info, the app gently prompts them
 * to fill in known mods. This changes PID tolerances, maintenance intervals,
 * and diagnostic confidence.
 *
 * Example: larger injectors + turbo upgrade + taller tires = different
 * fuel rate, boost pressure, speed reading, and service intervals.
 */
@Entity(tableName = "vehicle_upgrades")
public class VehicleUpgrade {
    @PrimaryKey
    public long id = 1L;  // Single row, same pattern as VehicleProfile

    // ===== FUEL SYSTEM =====
    public String injectorSize;           // "stock 7.3L" (140cc), "160cc", "175cc", "custom"
    public long injectorUpgradeTimestamp; // when user entered this
    public boolean injectorUpgradeConfirmed; // user explicitly confirmed (vs assumed)

    public String fuelPumpType;           // "stock", "Airdog 150", "FASS", "other"
    public long fuelPumpUpgradeTimestamp;
    public boolean fuelPumpUpgradeConfirmed;

    public String fuelSystemMods;         // "none", "fuel return line deleted", "high-pressure line upgrade"
    public long fuelSystemModsTimestamp;

    // ===== TURBOCHARGER & BOOST =====
    public String turboType;              // "stock GT3782VA", "GT4088 single", "twins", "other"
    public long turboUpgradeTimestamp;
    public boolean turboUpgradeConfirmed;

    public String boostTargetPsi;         // "stock ~18 psi", "20 psi", "25 psi", "custom"
    public long boostTargetTimestamp;

    public String intercoolerType;        // "stock", "upgraded core", "front-mount", "dual-pass"
    public long intercoolerUpgradeTimestamp;

    // ===== ENGINE INTERNALS =====
    public String camshaftType;           // "stock", "Comp Cams XE262", "custom grind"
    public long camshaftUpgradeTimestamp;
    public boolean camshaftUpgradeConfirmed;

    public String headWork;               // "none", "ported and polished", "valve seat work", "custom"
    public long headWorkTimestamp;

    public String internalUpgrades;       // "none", "ARP bolts", "forged pistons", "stroker"
    public long internalUpgradesTimestamp;

    // ===== COOLING SYSTEM =====
    public String coolingSystemType;      // "stock", "upgraded radiator (rows)", "electric fan", "dual setup"
    public long coolingUpgradeTimestamp;

    public String thermostatTemp;         // "160F stock", "180F", "custom"
    public long thermostatTimestamp;

    // ===== IGNITION / GLOW PLUGS =====
    public String glowPlugType;           // "stock OEM", "premium glow plugs", "ceramic"
    public long glowPlugUpgradeTimestamp;

    public String glowPlugRelayType;      // "stock", "upgraded relay", "custom harness"
    public long glowPlugRelayTimestamp;

    // ===== ELECTRICAL & BATTERY =====
    public String batterySize;            // "standard 1000 CCA", "dual battery", "custom"
    public long batteryUpgradeTimestamp;

    public String alternatorType;         // "stock 130A", "upgraded 160A", "dual"
    public long alternatorUpgradeTimestamp;

    public String wiringSizeUpgrade;      // "none", "larger gauge ground cables", "complete harness"
    public long wiringUpgradeTimestamp;

    // ===== TIRES & WHEELS =====
    public String tireSize;               // "245/75R16 stock", "285/75R16", "custom"
    public double tireWidthInches;        // parsed from tire size; used for speed/odometer correction
    public double tireAspectRatio;        // used for tire height calculation
    public int tireWheelDiameterInches;   // wheel + tire diameter; affects speedometer accuracy
    public long tireUpgradeTimestamp;
    public boolean tireUpgradeConfirmed;

    // ===== TRANSMISSION / DRIVETRAIN =====
    public String transmissionType;       // "stock 4R100", "upgraded torque converter", "custom tune"
    public long transmissionUpgradeTimestamp;

    public String transferCaseType;       // "stock", "Atlas (or other) swap", "electronic shift upgrade"
    public long transferCaseUpgradeTimestamp;

    public String differentialGearing;    // "3.55 stock", "4.10", "4.56", "custom"
    public long differentialGearingTimestamp;

    public String differentialLockType;   // "none", "ARB AIR locker", "mechanical lock"
    public long differentialLockTimestamp;

    // ===== ENGINE TUNE / ECU =====
    public String engineTuneType;         // "stock", "mild tune (120hp+)", "moderate (180hp+)", "aggressive"
    public long engineTuneTimestamp;
    public boolean engineTuneConfirmed;

    public String tuneStrategy;           // "stock Ford", "economy", "power", "towing", "custom"
    public long tuneStrategyTimestamp;

    // ===== EXHAUST & EMISSIONS =====
    public String exhaustModifications;   // "stock", "turbo-back custom", "delete (EGR/DPF)", "headers"
    public long exhaustModsTimestamp;

    public String emissionsStatus;        // "stock", "EGR deleted", "DPF deleted", "tuned delete"
    public long emissionsStatusTimestamp;

    // ===== GENERAL INFO =====
    public long lastUpgradeEntryPrompt;   // timestamp of last time app asked user for upgrades
    public int upgradePromptCount;        // how many times user has been asked (to space out prompts)
    public String notes;                  // free-form user notes on other mods

    public static VehicleUpgrade newDefault() {
        VehicleUpgrade u = new VehicleUpgrade();
        u.id = 1L;
        // Factory stock 2000 F-250 7.3L Power Stroke
        u.injectorSize = "stock 140cc";
        u.fuelPumpType = "stock mechanical lift pump";
        u.turboType = "stock GT3782VA";
        u.boostTargetPsi = "stock ~18 psi";
        u.intercoolerType = "stock air-to-air";
        u.camshaftType = "stock Ford cam";
        u.coolingSystemType = "stock 4-row radiator";
        u.thermostatTemp = "160F stock";
        u.glowPlugType = "stock OEM";
        u.batterySize = "standard 1000 CCA";
        u.alternatorType = "stock 130A";
        u.tireSize = "245/75R16 stock";
        u.tireWheelDiameterInches = 30;  // approximately
        u.transmissionType = "stock 4R100";
        u.transferCaseType = "stock NP205";
        u.differentialGearing = "3.55 stock";
        u.engineTuneType = "stock";
        u.exhaustModifications = "stock";
        u.emissionsStatus = "stock";
        u.lastUpgradeEntryPrompt = 0;
        u.upgradePromptCount = 0;
        return u;
    }
}
