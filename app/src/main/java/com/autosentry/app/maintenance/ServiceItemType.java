package com.autosentry.app.maintenance;

/**
 * Serviceable items tracked against Ford's published 7.3L Power Stroke
 * F-250–F-550 maintenance intervals (Normal vs Special/Severe service).
 * String value doubles as the MaintenanceEvent.type stored in the DB.
 */
public enum ServiceItemType {
    OIL_FILTER,
    FUEL_FILTER,
    AIR_FILTER,
    TRANSMISSION_FLUID,
    TRANSFER_CASE_FLUID,
    FRONT_DIFFERENTIAL,
    REAR_DIFFERENTIAL,
    COOLANT
}
