package com.autosentry.app.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Service/maintenance record tied to a specific session or KOEO test.
 * This is the core of AutoSentry's vehicle history ledger.
 *
 * Unlike generic maintenance logs, this record captures:
 * - What was done and when
 * - Which upgrades were installed (if any)
 * - PID readings at time of service (for baseline comparison)
 * - Photos/receipts as proof
 * - Cost and parts used
 * - Next service projection
 *
 * Over time, this creates an immutable, timestamped history of every service,
 * upgrade, and repair. When a user sells the truck, they can export this as
 * a "Vehicle History Report" showing:
 * - Complete service record (oil changes, filters, major repairs)
 * - All upgrades installed (injectors, turbo, etc.) with dates
 * - Electrical/corrosion issues detected and resolved
 * - Resale value impact (maintained vs neglected)
 */
@Entity(tableName = "service_records")
public class ServiceRecord {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public long timestamp;              // when service was performed
    public long sessionId;              // which trip/KOEO this came from (0 if manual entry)
    public String serviceType;          // "oil change", "fuel filter", "turbo service", "electrical repair", "upgrade install", etc.
    public String serviceCategory;      // bin for reporting: "Preventive", "Repair", "Upgrade", "Diagnostic"

    // ===== SERVICE DETAILS =====
    public String description;          // "Oil and filter change; 5000 mile service"
    public String partsReplaced;        // "Motorcraft 15W-40 synthetic, OEM oil filter" (comma-separated)
    public double odometer;             // odometer reading at time of service
    public double cost;                 // dollars spent (0 if DIY)
    public String vendor;               // "Jiffy Lube", "Ford dealership", "DIY at home", etc.

    // ===== UPGRADE TRACKING =====
    // If this record is logging an upgrade installation, store the details
    public String upgradeInstalled;     // "160cc injectors", "GT4088 turbo", "taller tires", etc.
    public String upgradeCategory;      // "Fuel System", "Turbocharger", "Tires", etc.
    public String upgradeNotes;         // "Upgraded from stock 140cc to 160cc Bosch injectors"

    // ===== PID BASELINE AT TIME OF SERVICE =====
    // Capture engine health snapshot at time of service
    // Useful for "before/after" comparison and diagnosing chronic issues
    public double pidRpmAtService;      // RPM reading when recorded
    public double pidFuelPressureAtService;
    public double pidBatteryVoltageAtService;
    public double pidOilTempAtService;
    public String pidNotesAtService;    // any anomalies observed at service time

    // ===== PHOTOS & RECEIPTS =====
    public String photoPath;            // path to receipt photo or service photo
    public String receiptPath;          // path to receipt/invoice document
    public String caption;              // e.g., "Receipt from dealership", "Oil change at home"

    // ===== NEXT SERVICE =====
    public long nextServiceDueByDate;   // projected next service date (0 = not yet calculated)
    public double nextServiceDueByMiles; // projected next service mileage

    // ===== RESALE HISTORY =====
    public String resaleImpact;         // "Improves resale", "Neutral", "May lower resale" (based on service type)
    public String notes;                // free-form notes
}
