package com.autosentry.app.maintenance;

/**
 * Every serviceable item AutoSentry tracks for the 7.3L Power Stroke Super
 * Duty. Details (intervals, category, parts, related jobs) live in
 * ServiceInterval. String value doubles as the MaintenanceEvent.type stored
 * in the DB, so existing names must never be renamed.
 */
public enum ServiceItemType {
    // Engine
    OIL_FILTER,
    COOLANT,
    COOLANT_SCA,
    SERPENTINE_BELT,
    BATTERIES,
    GLOW_PLUGS,
    // Fuel & air
    FUEL_FILTER,
    AIR_FILTER,
    // Drivetrain
    TRANSMISSION_FLUID,
    TRANSFER_CASE_FLUID,
    FRONT_DIFFERENTIAL,
    REAR_DIFFERENTIAL,
    DRIVESHAFT_GREASE,
    // Steering & suspension
    STEERING_GREASE,
    FRONT_END_INSPECTION,
    SHOCKS,
    // Brakes & tires
    BRAKES,
    BRAKE_FLUID,
    TIRE_ROTATION,
    TIRE_AGE,
    // 7.3L age/wear items
    CAC_BOOTS,
    UVCH,
    COOLANT_HOSES
}
