package com.autosentry.app.maintenance;

import java.util.EnumMap;
import java.util.Map;

/**
 * Manufacturer-published service intervals for the 2000 F-250 7.3L Power
 * Stroke, sourced from Ford/Motorcraft's official F-250–F-550 Power Stroke
 * Diesel Maintenance Intervals flyer (oil + fuel filter) and the Diesel
 * Owner's Supplement interval tables (everything else — Ford's flyer
 * defers to the Supplement for non-filter items).
 *
 * Every value here is "whichever occurs first" against odometer miles OR
 * the stated month count since the item was last serviced.
 *
 * Special/Severe service applies to most work-truck usage: towing,
 * extended idling (>10 min/hr), sustained sub-25mph traffic, dust/off-road,
 * temperature extremes, or any biodiesel use. Default this schedule to
 * severe duty so we alert early rather than late on a high-mile truck.
 */
public final class ServiceInterval {
    public final ServiceItemType type;
    public final String displayName;
    public final int normalMiles;
    public final int normalMonths; // 0 = no month cap published
    public final int severeMiles;
    public final int severeMonths;

    private ServiceInterval(ServiceItemType type, String displayName,
                             int normalMiles, int normalMonths,
                             int severeMiles, int severeMonths) {
        this.type = type;
        this.displayName = displayName;
        this.normalMiles = normalMiles;
        this.normalMonths = normalMonths;
        this.severeMiles = severeMiles;
        this.severeMonths = severeMonths;
    }

    private static final Map<ServiceItemType, ServiceInterval> ALL = new EnumMap<>(ServiceItemType.class);
    static {
        put(new ServiceInterval(ServiceItemType.OIL_FILTER, "Engine Oil & Filter",
                5000, 6, 3000, 3));
        put(new ServiceInterval(ServiceItemType.FUEL_FILTER, "Fuel Filter",
                15000, 0, 15000, 0));
        put(new ServiceInterval(ServiceItemType.AIR_FILTER, "Engine Air Filter",
                30000, 30, 15000, 12));
        put(new ServiceInterval(ServiceItemType.TRANSMISSION_FLUID, "Auto Transmission Fluid & Filter",
                30000, 0, 30000, 0));
        put(new ServiceInterval(ServiceItemType.TRANSFER_CASE_FLUID, "Transfer Case Fluid",
                60000, 0, 60000, 0));
        put(new ServiceInterval(ServiceItemType.FRONT_DIFFERENTIAL, "Front Differential Fluid",
                100000, 0, 30000, 0));
        put(new ServiceInterval(ServiceItemType.REAR_DIFFERENTIAL, "Rear Differential Fluid",
                100000, 0, 30000, 0));
        put(new ServiceInterval(ServiceItemType.COOLANT, "Coolant Flush",
                50000, 48, 50000, 48)); // then every 30k/36mo after the initial service
    }

    private static void put(ServiceInterval interval) {
        ALL.put(interval.type, interval);
    }

    public static ServiceInterval of(ServiceItemType type) {
        return ALL.get(type);
    }

    public static ServiceItemType[] allTypes() {
        return ServiceItemType.values();
    }

    public int miles(boolean severeDuty) {
        return severeDuty ? severeMiles : normalMiles;
    }

    public int months(boolean severeDuty) {
        return severeDuty ? severeMonths : normalMonths;
    }
}
