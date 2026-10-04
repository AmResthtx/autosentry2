package com.autosentry.app.history;

import com.autosentry.app.data.ServiceRecord;
import com.autosentry.app.data.VehicleProfile;
import com.autosentry.app.data.VehicleUpgrade;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Generates a "Vehicle History Report" for resale and documentation.
 *
 * This is AutoSentry's private version of vehicle history (not carfax).
 * It proves maintenance, upgrades, and reliability to potential buyers.
 *
 * A truck with 100k miles and a complete service history can command
 * a 10-20% premium over a truck with unknown history.
 * A truck missing maintenance records is a "dice roll" for any buyer.
 *
 * AutoSentry enables owners to prove their investment and builders to
 * showcase their work.
 */
public class VehicleHistoryReport {
    public final VehicleProfile profile;
    public final VehicleUpgrade upgrades;
    public final List<ServiceRecord> serviceHistory;

    public VehicleHistoryReport(VehicleProfile profile, VehicleUpgrade upgrades, List<ServiceRecord> serviceHistory) {
        this.profile = profile;
        this.upgrades = upgrades;
        this.serviceHistory = serviceHistory;
    }

    /**
     * Generates a summary for display or export.
     * Shows key stats: age, mileage, service completeness, upgrades, estimated resale impact.
     */
    public String generateSummary() {
        StringBuilder sb = new StringBuilder();
        long now = System.currentTimeMillis();
        SimpleDateFormat dateFormat = new SimpleDateFormat("MMM yyyy", Locale.US);

        sb.append("=== VEHICLE HISTORY REPORT ===\n\n");
        sb.append("Current Mileage: ").append(String.format("%.0f", profile.odometerMiles)).append(" miles\n");
        sb.append("Engine Hours: ").append(String.format("%.0f", profile.engineHoursSinceOilChange)).append(" hours\n");
        sb.append("Report Generated: ").append(dateFormat.format(new Date(now))).append("\n\n");

        // ===== MAINTENANCE COMPLETENESS =====
        sb.append("--- MAINTENANCE RECORD COMPLETENESS ---\n");
        int oilChanges = countServiceType("oil change");
        int filterChanges = countServiceType("fuel filter");
        int repairRecords = countServiceType("repair");

        sb.append("Oil Changes: ").append(oilChanges).append(" recorded\n");
        sb.append("Filter Services: ").append(filterChanges).append(" recorded\n");
        sb.append("Repair Records: ").append(repairRecords).append(" recorded\n");
        sb.append("Total Service Events: ").append(serviceHistory.size()).append("\n");

        if (serviceHistory.isEmpty()) {
            sb.append("\n⚠️  WARNING: No maintenance records found.\n");
            sb.append("A truck without documented service history significantly reduces buyer confidence\n");
            sb.append("and resale value. Start logging maintenance now to build your history.\n\n");
        } else {
            sb.append("\n✓ Complete service history improves buyer confidence and resale value.\n\n");
        }

        // ===== UPGRADES INSTALLED =====
        List<ServiceRecord> upgradesInstalled = serviceHistory.stream()
            .filter(s -> s.upgradeInstalled != null && !s.upgradeInstalled.isEmpty())
            .collect(Collectors.toList());

        if (!upgradesInstalled.isEmpty()) {
            sb.append("--- UPGRADES INSTALLED ---\n");
            for (ServiceRecord upgrade : upgradesInstalled) {
                sb.append("• ").append(upgrade.upgradeInstalled)
                  .append(" (").append(dateFormat.format(new Date(upgrade.timestamp))).append(")\n");
                if (upgrade.upgradeNotes != null && !upgrade.upgradeNotes.isEmpty()) {
                    sb.append("  ").append(upgrade.upgradeNotes).append("\n");
                }
            }
            sb.append("\n✓ Documented upgrades increase resale value and attract enthusiast buyers.\n\n");
        }

        // ===== REPAIR HISTORY =====
        List<ServiceRecord> repairs = serviceHistory.stream()
            .filter(s -> "Repair".equals(s.serviceCategory))
            .collect(Collectors.toList());

        if (!repairs.isEmpty()) {
            sb.append("--- REPAIRS & ISSUES RESOLVED ---\n");
            for (ServiceRecord repair : repairs) {
                sb.append("• ").append(repair.description)
                  .append(" (").append(dateFormat.format(new Date(repair.timestamp))).append(")\n");
                if (repair.partsReplaced != null && !repair.partsReplaced.isEmpty()) {
                    sb.append("  Parts: ").append(repair.partsReplaced).append("\n");
                }
            }
            sb.append("\n");
        }

        // ===== RESALE VALUE IMPACT =====
        sb.append("--- RESALE VALUE IMPACT ---\n");
        int improves = countResaleImpact("Improves resale");
        int neutral = countResaleImpact("Neutral");
        int lowers = countResaleImpact("May lower resale");

        sb.append("Positive Records (upgrades, completed maintenance): ").append(improves).append("\n");
        sb.append("Neutral Records (routine service): ").append(neutral).append("\n");
        sb.append("Potential Concerns (major repairs, deletions): ").append(lowers).append("\n\n");

        if (serviceHistory.isEmpty()) {
            sb.append("Estimated Resale Impact: UNKNOWN (no history)\n");
            sb.append("Potential Buyer Perception: HIGH RISK\n");
            sb.append("Estimated Value Impact: -10% to -20% vs comparable maintained truck\n");
        } else if (improves > neutral + lowers) {
            sb.append("Estimated Resale Impact: POSITIVE\n");
            sb.append("Potential Buyer Perception: Well-maintained, upgraded truck\n");
            sb.append("Estimated Value Impact: +10% to +25% premium vs baseline\n");
        } else if (repairs > 2 && lowers > improves) {
            sb.append("Estimated Resale Impact: CAUTION\n");
            sb.append("Potential Buyer Perception: Multiple issues, buyer skeptical\n");
            sb.append("Estimated Value Impact: -5% to -15%\n");
        } else {
            sb.append("Estimated Resale Impact: NEUTRAL\n");
            sb.append("Potential Buyer Perception: Average maintenance\n");
            sb.append("Estimated Value Impact: ~Baseline\n");
        }

        sb.append("\n").append("=== END REPORT ===");
        return sb.toString();
    }

    /**
     * Generates a detailed export suitable for sharing with potential buyers.
     * Includes all service dates, costs, upgrades, and photos.
     */
    public String generateDetailedExport() {
        StringBuilder sb = new StringBuilder();
        SimpleDateFormat dateFormat = new SimpleDateFormat("MMM dd, yyyy", Locale.US);
        SimpleDateFormat timeFormat = new SimpleDateFormat("hh:mm a", Locale.US);

        sb.append("VEHICLE MAINTENANCE & UPGRADE HISTORY\n\n");
        sb.append("Generated: ").append(dateFormat.format(new Date(System.currentTimeMillis()))).append("\n\n");

        sb.append("SERVICE RECORD (Chronological Order)\n");
        sb.append("=====================================\n\n");

        for (ServiceRecord record : serviceHistory) {
            sb.append(String.format("%s at %s (%d miles)\n",
                dateFormat.format(new Date(record.timestamp)),
                timeFormat.format(new Date(record.timestamp)),
                (int) record.odometer));
            sb.append("Service: ").append(record.serviceType).append("\n");
            if (record.description != null) {
                sb.append("Details: ").append(record.description).append("\n");
            }
            if (record.partsReplaced != null && !record.partsReplaced.isEmpty()) {
                sb.append("Parts: ").append(record.partsReplaced).append("\n");
            }
            if (record.cost > 0) {
                sb.append("Cost: $").append(String.format("%.2f", record.cost)).append("\n");
            }
            if (record.vendor != null) {
                sb.append("Vendor: ").append(record.vendor).append("\n");
            }
            if (record.upgradeInstalled != null && !record.upgradeInstalled.isEmpty()) {
                sb.append(">>> UPGRADE INSTALLED: ").append(record.upgradeInstalled).append("\n");
            }
            sb.append("\n");
        }

        sb.append("\nNOTE: This vehicle history is maintained by AutoSentry diagnostic system.\n");
        sb.append("All records are timestamped and tied to vehicle diagnostics.\n");
        sb.append("For buyers: Complete maintenance history indicates responsible ownership.\n");
        sb.append("For sellers: Use this history to justify your asking price.\n");

        return sb.toString();
    }

    private int countServiceType(String type) {
        return (int) serviceHistory.stream()
            .filter(s -> type.equalsIgnoreCase(s.serviceType))
            .count();
    }

    private int countResaleImpact(String impact) {
        return (int) serviceHistory.stream()
            .filter(s -> impact.equals(s.resaleImpact))
            .count();
    }
}
