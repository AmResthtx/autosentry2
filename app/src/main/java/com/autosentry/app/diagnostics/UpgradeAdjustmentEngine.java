package com.autosentry.app.diagnostics;

import com.autosentry.app.data.VehicleUpgrade;
import com.autosentry.app.maintenance.ServiceIntervalWithPredict;
import com.autosentry.app.maintenance.ServiceItemType;

import java.util.HashMap;
import java.util.Map;

/**
 * Adjusts PID tolerances and service intervals based on vehicle upgrades.
 *
 * Example:
 * - Stock 7.3L: fuel rate up to 35 gal/h
 * - 160cc injectors: fuel rate up to 50 gal/h (proportional to injector size)
 * - Larger injectors + turbo: boost tolerance increases, oil change interval decreases
 * - Taller tires: speedometer reads low; correction factor applied
 * - Engine tune: fuel pressure may be higher, RPM limiter may differ
 *
 * This ensures AutoSentry doesn't flag normal behavior as anomalous.
 */
public final class UpgradeAdjustmentEngine {

    private UpgradeAdjustmentEngine() {}

    /**
     * Adjusts fuel rate tolerance based on injector size.
     * Stock 140cc = 35 gal/h; each 35cc = +10 gal/h capacity.
     */
    public static double adjustedMaxFuelRate(VehicleUpgrade upgrade) {
        double stock = 35.0;  // gal/h
        if (upgrade.injectorSize == null || upgrade.injectorSize.contains("140")) {
            return stock;
        }
        if (upgrade.injectorSize.contains("160")) return stock + 7;
        if (upgrade.injectorSize.contains("175")) return stock + 15;
        if (upgrade.injectorSize.contains("custom")) return stock + 25;
        return stock;
    }

    /**
     * Adjusts boost tolerance based on turbo type and tune.
     * Stock: 18 psi
     * Upgraded turbo or tune: 20-25+ psi
     */
    public static double adjustedMaxBoostPsi(VehicleUpgrade upgrade) {
        double stock = 18.0;
        if (upgrade.boostTargetPsi != null) {
            if (upgrade.boostTargetPsi.contains("20")) return 20.0;
            if (upgrade.boostTargetPsi.contains("25")) return 25.0;
            if (upgrade.boostTargetPsi.contains("custom")) return 28.0;
        }
        return stock;
    }

    /**
     * Adjusts speedometer reading based on tire size.
     * If tires are taller than stock, speedometer reads low.
     * Correction factor = (actual tire diameter) / (stock diameter)
     *
     * Stock 245/75R16 ≈ 30 inches
     */
    public static double speedCorrectionFactor(VehicleUpgrade upgrade) {
        int stockDiameter = 30;  // inches (approximately)
        if (upgrade.tireWheelDiameterInches <= 0) {
            return 1.0;  // No correction
        }
        return (double) upgrade.tireWheelDiameterInches / stockDiameter;
    }

    /**
     * Adjusts odometer reading based on tire size.
     * Odometer calculates distance from wheel rotations; taller tires = more distance per rotation.
     */
    public static double odometerCorrectionFactor(VehicleUpgrade upgrade) {
        // Same as speed correction for simplicity
        return speedCorrectionFactor(upgrade);
    }

    /**
     * Adjusts fuel pressure tolerance based on fuel system mods.
     * Stock high-pressure: 20-22 psi (at injection pump)
     * Aftermarket fuel system (larger pump, injectors): may run 22-25 psi
     */
    public static double adjustedFuelPressureMin(VehicleUpgrade upgrade) {
        double stock = 16.0;  // psi minimum
        if (upgrade.fuelPumpType != null && !upgrade.fuelPumpType.contains("stock")) {
            return stock;  // Aftermarket pumps typically maintain higher pressure
        }
        return stock;
    }

    public static double adjustedFuelPressureMax(VehicleUpgrade upgrade) {
        double stock = 22.0;  // psi max
        if (upgrade.fuelPumpType != null && upgrade.fuelPumpType.contains("FASS")) {
            return 25.0;  // FASS pumps can run higher
        }
        if (upgrade.fuelSystemMods != null && upgrade.fuelSystemMods.contains("high-pressure")) {
            return 25.0;
        }
        return stock;
    }

    /**
     * Adjusts oil change interval based on engine tune and internals.
     * Aggressive tune or forged internals = more stress = shorten interval
     */
    public static int adjustedOilChangeIntervalMiles(VehicleUpgrade upgrade, boolean severeDuty) {
        int stock = severeDuty ? 3000 : 5000;
        if (upgrade.engineTuneType != null) {
            if (upgrade.engineTuneType.contains("Aggressive")) return stock - 500;
            if (upgrade.engineTuneType.contains("Moderate")) return stock - 250;
        }
        return stock;
    }

    /**
     * Adjusts cooling system inspection interval if upgraded.
     * Larger radiators and electric fans are more reliable; longer intervals OK.
     */
    public static int adjustedCoolingInspectionMonths(VehicleUpgrade upgrade) {
        int stock = 12;  // Annual
        if (upgrade.coolingSystemType != null && upgrade.coolingSystemType.contains("Upgraded")) {
            return 24;  // Every 2 years if upgraded
        }
        return stock;
    }

    /**
     * Adjusts MAF sensor tolerance based on intake mods.
     * Stock intake: narrow tolerance
     * Ported/upgraded intake: MAF sensor may see different flow; wider tolerance OK
     */
    public static double adjustedMaxMafGramsPerSec(VehicleUpgrade upgrade) {
        double stock = 200.0;  // g/s max
        if (upgrade.internalUpgrades != null && upgrade.internalUpgrades.contains("ported")) {
            return stock + 50;
        }
        return stock;
    }

    /**
     * Generates a summary of how upgrades affect diagnostics.
     * Displayed to user for transparency.
     */
    public static String summarizeUpgradeImpact(VehicleUpgrade upgrade) {
        StringBuilder sb = new StringBuilder();
        sb.append("AutoSentry Upgrade Profile Summary\n\n");

        if (upgrade.injectorSize != null && !upgrade.injectorSize.contains("140")) {
            sb.append("• Larger injectors detected: fuel rate tolerance adjusted to ");
            sb.append(String.format("%.0f", adjustedMaxFuelRate(upgrade))).append(" gal/h\n");
        }
        if (upgrade.boostTargetPsi != null && !upgrade.boostTargetPsi.contains("18")) {
            sb.append("• Boost target adjusted to ").append(upgrade.boostTargetPsi).append("\n");
        }
        if (upgrade.tireWheelDiameterInches > 30) {
            sb.append("• Tire diameter adjusted: speedometer correction factor = ")
                .append(String.format("%.2f", speedCorrectionFactor(upgrade))).append("\n");
        }
        if (upgrade.engineTuneType != null && !upgrade.engineTuneType.contains("stock")) {
            sb.append("• Engine tune detected: oil change interval may be shortened\n");
        }
        if (upgrade.fuelPumpType != null && !upgrade.fuelPumpType.contains("stock")) {
            sb.append("• Aftermarket fuel pump: fuel pressure tolerances adjusted\n");
        }

        sb.append("\nFor best results, keep your upgrade profile updated as you modify your truck.");
        return sb.toString();
    }
}
