package com.autosentry.app.maintenance;

import java.util.EnumMap;
import java.util.Map;

/**
 * The service catalog for the 1999–2003 7.3L Power Stroke Super Duty
 * (built first for a 2000 F-250).
 *
 * Intervals are "whichever occurs first": odometer miles OR months since the
 * item was last logged. 0 means "no limit on that axis".
 *
 * Sources, in order of authority:
 *  - FORD: Ford/Motorcraft F-250–F-550 Power Stroke maintenance flyer and
 *    the 7.3L Diesel Owner's Supplement (oil, fuel, air, fluids, coolant),
 *    plus Ford's Super Duty guidance for tires, lubrication and brake fluid.
 *  - COMMON: widely followed practice where Ford publishes no interval
 *    (inspections, condition-based wear parts).
 *  - SUGGESTED: AutoSentry's own age-based nudge for known 7.3L weak spots.
 *    These are labeled as such in the UI — never presented as a Ford spec.
 *
 * Severe duty (towing, idling, short trips, dust, temperature extremes)
 * covers most work-truck use and is the default in VehicleProfile.
 *
 * "Always replace": one-time-use parts (seals, gaskets, stretch bolts) that
 * must be new every time the job is done — a reused seal is a comeback.
 *
 * "While you're in there": jobs that share the same teardown. Once a truck
 * is apart, doing the neighbor job now saves paying for the labor twice —
 * the record screen offers these so they can be done and logged together.
 */
public final class ServiceInterval {

    public enum Category {
        ENGINE("Engine"),
        FUEL_AIR("Fuel & air"),
        DRIVETRAIN("Drivetrain"),
        STEERING("Steering & suspension"),
        BRAKES_TIRES("Brakes & tires"),
        WEAR_73("7.3L wear items");

        public final String label;

        Category(String label) {
            this.label = label;
        }
    }

    public enum Action {
        REPLACE("Replace"),
        SERVICE("Service"),
        INSPECT("Check");

        public final String verb;

        Action(String verb) {
            this.verb = verb;
        }
    }

    /**
     * How hard AutoSentry pushes when an item comes due. Keeps the app
     * proactive without nagging:
     *  SAFETY   — notify at 80% (engine- or safety-critical)
     *  STANDARD — notify only once overdue
     *  QUIET    — never notify; shown on the History tab only
     */
    public enum AlertLevel { SAFETY, STANDARD, QUIET }

    public static final String FORD = "Ford 7.3L schedule";
    public static final String COMMON = "Common practice — Ford lists no interval";
    public static final String SUGGESTED = "AutoSentry suggestion for aging 7.3Ls — not a Ford interval";

    public final ServiceItemType type;
    public final String displayName;
    public final Category category;
    public final Action action;
    public final AlertLevel alertLevel;
    public int normalMiles, normalMonths, severeMiles, severeMonths;
    // Some items repeat on a different interval once one service is on record (Ford's coolant: the
    // first flush at 50,000 mi / 4 years, then every 30,000 mi / 3 years).
    private boolean repeats = false;
    private int repeatMiles, repeatMonths;
    public String source = COMMON;
    public String why = "";
    public String[] parts = new String[0];
    public ServiceItemType[] whileInThere = new ServiceItemType[0];
    public String[] addOnParts = new String[0];
    public String[] alwaysReplace = new String[0];

    private ServiceInterval(ServiceItemType type, String displayName, Category category,
                            Action action, AlertLevel alertLevel) {
        this.type = type;
        this.displayName = displayName;
        this.category = category;
        this.action = action;
        this.alertLevel = alertLevel;
    }

    private ServiceInterval every(int miles, int months) {
        return normal(miles, months).severe(miles, months);
    }

    /** The interval after the first logged service, for items whose first interval is longer. */
    private ServiceInterval thenEvery(int miles, int months) {
        repeats = true;
        repeatMiles = miles;
        repeatMonths = months;
        return this;
    }

    private ServiceInterval normal(int miles, int months) {
        normalMiles = miles;
        normalMonths = months;
        return this;
    }

    private ServiceInterval severe(int miles, int months) {
        severeMiles = miles;
        severeMonths = months;
        return this;
    }

    private ServiceInterval source(String source) {
        this.source = source;
        return this;
    }

    private ServiceInterval why(String why) {
        this.why = why;
        return this;
    }

    private ServiceInterval parts(String... parts) {
        this.parts = parts;
        return this;
    }

    private ServiceInterval whileInThere(ServiceItemType... related) {
        this.whileInThere = related;
        return this;
    }

    private ServiceInterval addOns(String... addOnParts) {
        this.addOnParts = addOnParts;
        return this;
    }

    /** One-time-use parts: gaskets, seals, O-rings, stretch (torque-to-yield) bolts, cotter pins. Never reuse. */
    private ServiceInterval alwaysReplace(String... parts) {
        this.alwaysReplace = parts;
        return this;
    }

    private static final Map<ServiceItemType, ServiceInterval> ALL = new EnumMap<>(ServiceItemType.class);

    private static ServiceInterval item(ServiceItemType type, String name, Category category,
                                        Action action, AlertLevel alert) {
        ServiceInterval interval = new ServiceInterval(type, name, category, action, alert);
        ALL.put(type, interval);
        return interval;
    }

    static {
        // ---------------- Engine ----------------
        item(ServiceItemType.OIL_FILTER, "Engine oil & filter", Category.ENGINE, Action.REPLACE, AlertLevel.SAFETY)
                .normal(5000, 6).severe(3000, 3).source(FORD)
                .why("On the 7.3L the engine oil is also the hydraulic fluid that fires the injectors. "
                        + "Worn-out oil means hard starts, rough running and injector wear.")
                .parts("15W-40 heavy-duty diesel oil (about 15 qt with filter)",
                        "Oil filter for 1999–2003 7.3L")
                .alwaysReplace("Drain plug gasket / O-ring")
                .whileInThere(ServiceItemType.FUEL_FILTER, ServiceItemType.STEERING_GREASE,
                        ServiceItemType.DRIVESHAFT_GREASE, ServiceItemType.TIRE_ROTATION)
                .addOns("Drain the fuel bowl water separator");

        item(ServiceItemType.COOLANT, "Coolant flush", Category.ENGINE, Action.SERVICE, AlertLevel.STANDARD)
                .every(50000, 48).thenEvery(30000, 36).source(FORD)
                .why("Ford: first flush at 50,000 mi / 4 years, then every 30,000 mi / 3 years. "
                        + "Old coolant stops protecting the cylinder liners and water pump.")
                .parts("Diesel-rated coolant for the 7.3L (full-system amount)",
                        "Supplemental coolant additive (SCA)", "Coolant test strips")
                .alwaysReplace("Thermostat gasket / O-ring (if opened)", "Radiator drain petcock O-ring")
                .whileInThere(ServiceItemType.COOLANT_HOSES, ServiceItemType.COOLANT_SCA,
                        ServiceItemType.SERPENTINE_BELT)
                .addOns("Thermostat & gasket", "Water pump (if weeping or noisy)", "Radiator cap");

        item(ServiceItemType.COOLANT_SCA, "Coolant additive (SCA) test", Category.ENGINE, Action.INSPECT, AlertLevel.QUIET)
                .every(15000, 12).source(FORD)
                .why("Diesel liners vibrate hard enough to make bubbles in the coolant that eat pinholes through them "
                        + "(cavitation). Test strips tell you if the additive that prevents it is used up.")
                .parts("Coolant test strips", "SCA additive (Ford: 8–10 oz every 15,000 mi)")
                .whileInThere(ServiceItemType.COOLANT);

        item(ServiceItemType.SERPENTINE_BELT, "Serpentine belt & tensioner", Category.ENGINE, Action.INSPECT, AlertLevel.QUIET)
                .every(15000, 12).source(COMMON)
                .why("Look for cracks, glazing and a tensioner that bounces. When this belt snaps you lose "
                        + "the water pump and alternator at once.")
                .parts("Serpentine belt", "Belt tensioner", "Idler pulley")
                .whileInThere(ServiceItemType.COOLANT_HOSES)
                .addOns("Fan clutch check", "Water pump (belt is off anyway)");

        item(ServiceItemType.BATTERIES, "Batteries (both) — load test", Category.ENGINE, Action.INSPECT, AlertLevel.QUIET)
                .every(0, 12).source(COMMON)
                .why("The 7.3L runs two batteries. One weak battery drags the other down and starves "
                        + "the glow plugs on cold mornings. Replace them as a matched pair.")
                .parts("Two matched batteries", "Terminal cleaner & protector")
                .whileInThere(ServiceItemType.GLOW_PLUGS)
                .addOns("Battery cables & ground straps (check for corrosion)");

        item(ServiceItemType.GLOW_PLUGS, "Glow plug system check", Category.ENGINE, Action.INSPECT, AlertLevel.QUIET)
                .every(0, 12).source(COMMON)
                .why("Best done before winter. White smoke and hard cold starts point to bad plugs, "
                        + "the glow plug relay, or burnt connectors under the valve covers.")
                .parts("Glow plugs (set of 8)", "Glow plug relay")
                .alwaysReplace("Valve cover gaskets (whenever the covers come off)")
                .whileInThere(ServiceItemType.UVCH, ServiceItemType.BATTERIES)
                .addOns("Valve cover gaskets (if the covers come off)");

        // ---------------- Fuel & air ----------------
        item(ServiceItemType.FUEL_FILTER, "Fuel filter", Category.FUEL_AIR, Action.REPLACE, AlertLevel.SAFETY)
                .every(15000, 0).source(FORD)
                .why("Clean fuel protects the injectors, the most expensive parts on the engine. "
                        + "Drain the water separator whenever you change the oil.")
                .parts("Fuel filter for 1999–2003 7.3L", "Fuel bowl drain valve O-rings")
                .alwaysReplace("Fuel bowl lid O-ring (a new one comes with the filter, so use it)")
                .whileInThere(ServiceItemType.OIL_FILTER, ServiceItemType.AIR_FILTER)
                .addOns("Fuel bowl heater (if it has failed)", "Fuel bowl seals (if weeping)");

        item(ServiceItemType.AIR_FILTER, "Engine air filter", Category.FUEL_AIR, Action.REPLACE, AlertLevel.STANDARD)
                .normal(30000, 30).severe(15000, 12).source(FORD)
                .why("A clogged filter chokes the turbo, raising exhaust temps and smoke.")
                .parts("Engine air filter")
                .whileInThere(ServiceItemType.CAC_BOOTS, ServiceItemType.FUEL_FILTER);

        // ---------------- Drivetrain ----------------
        item(ServiceItemType.TRANSMISSION_FLUID, "Transmission fluid & filter", Category.DRIVETRAIN, Action.SERVICE, AlertLevel.STANDARD)
                .every(30000, 0).source(FORD)
                .why("Heat kills automatic transmissions, and towing makes heat. Fresh fluid is the cheapest "
                        + "insurance on the drivetrain.")
                .parts("Automatic transmission fluid (to your transmission's spec)", "Transmission filter")
                .alwaysReplace("Pan gasket", "Filter seal / O-ring")
                .whileInThere(ServiceItemType.TRANSFER_CASE_FLUID)
                .addOns("Transmission cooler lines (check for seepage)");

        item(ServiceItemType.TRANSFER_CASE_FLUID, "Transfer case fluid", Category.DRIVETRAIN, Action.SERVICE, AlertLevel.STANDARD)
                .every(60000, 0).source(FORD)
                .why("4x4 only. Old fluid wears the chain and bearings. Long-press this line to hide it on a 2WD truck.")
                .parts("Transfer case fluid (to spec)")
                .alwaysReplace("Drain & fill plug washers / O-rings")
                .whileInThere(ServiceItemType.TRANSMISSION_FLUID, ServiceItemType.FRONT_DIFFERENTIAL);

        item(ServiceItemType.FRONT_DIFFERENTIAL, "Front differential fluid", Category.DRIVETRAIN, Action.SERVICE, AlertLevel.STANDARD)
                .normal(100000, 0).severe(30000, 0).source(FORD)
                .why("4x4 only. Change it sooner after deep water, since milky gear oil means water got in.")
                .parts("Gear oil (to axle spec)")
                .alwaysReplace("Cover gasket (or fresh RTV)", "Fill plug O-ring")
                .whileInThere(ServiceItemType.REAR_DIFFERENTIAL, ServiceItemType.TRANSFER_CASE_FLUID);

        item(ServiceItemType.REAR_DIFFERENTIAL, "Rear differential fluid", Category.DRIVETRAIN, Action.SERVICE, AlertLevel.STANDARD)
                .normal(100000, 0).severe(30000, 0).source(FORD)
                .why("Towing heats the rear gear oil. Limited-slip axles also need friction modifier.")
                .parts("Gear oil (to axle spec)", "Friction modifier (limited-slip)")
                .alwaysReplace("Cover gasket (or fresh RTV)", "Fill plug O-ring")
                .whileInThere(ServiceItemType.FRONT_DIFFERENTIAL, ServiceItemType.DRIVESHAFT_GREASE);

        item(ServiceItemType.DRIVESHAFT_GREASE, "Grease driveshaft U-joints & slip yoke", Category.DRIVETRAIN, Action.SERVICE, AlertLevel.STANDARD)
                .normal(30000, 0).severe(5000, 6).source(FORD)
                .why("A dry U-joint starts as a clunk or vibration and can end with a dropped driveshaft. "
                        + "Hard-worked trucks get greased with every oil change. Sealed joints have no fitting.")
                .parts("NLGI #2 chassis grease", "Grease gun")
                .alwaysReplace("U-joint strap bolts, if the shaft comes off (single-use, pre-applied threadlocker)")
                .whileInThere(ServiceItemType.STEERING_GREASE, ServiceItemType.OIL_FILTER)
                .addOns("U-joints (if there's play or rust dust)", "Carrier bearing (2-piece shafts)");

        // ---------------- Steering & suspension ----------------
        item(ServiceItemType.STEERING_GREASE, "Grease steering & front end", Category.STEERING, Action.SERVICE, AlertLevel.STANDARD)
                .normal(30000, 0).severe(5000, 6).source(FORD)
                .why("Tie rods, drag link and ball joints with grease fittings need fresh grease to push out "
                        + "water and grit. Dry joints wear loose and cause wander and front-end shake.")
                .parts("NLGI #2 chassis grease", "Grease gun")
                .whileInThere(ServiceItemType.DRIVESHAFT_GREASE, ServiceItemType.FRONT_END_INSPECTION)
                .addOns("Replacement grease fittings (for any that won't take grease)");

        item(ServiceItemType.FRONT_END_INSPECTION, "Front end check", Category.STEERING, Action.INSPECT, AlertLevel.QUIET)
                .every(15000, 12).source(COMMON)
                .why("Check ball joints, tie rod ends, track bar, steering box play and wheel bearings. "
                        + "Worn parts here are behind most Super Duty wander and shake complaints.")
                .parts("Tie rod ends", "Ball joints", "Track bar & bushings", "Steering stabilizer")
                .alwaysReplace("Cotter pins", "Lock nuts on any joint you take apart (prevailing-torque, single-use)")
                .whileInThere(ServiceItemType.STEERING_GREASE, ServiceItemType.SHOCKS)
                .addOns("Alignment after any steering part is replaced");

        item(ServiceItemType.SHOCKS, "Shocks", Category.STEERING, Action.INSPECT, AlertLevel.QUIET)
                .every(50000, 0).source(COMMON)
                .why("Look for leaks, bounce after bumps, and cupped tires. Worn shocks also make "
                        + "front-end shake worse.")
                .parts("Shocks (set of 4)")
                .alwaysReplace("Shock mounting bushings & nuts (use the new ones in the box)")
                .whileInThere(ServiceItemType.FRONT_END_INSPECTION);

        // ---------------- Brakes & tires ----------------
        item(ServiceItemType.BRAKES, "Brake pads & rotors", Category.BRAKES_TIRES, Action.INSPECT, AlertLevel.SAFETY)
                .every(15000, 12).source(COMMON)
                .why("Pad wear depends on load and towing, not miles alone. Check pad thickness, rotor wear, "
                        + "calipers that stick, and brake hoses.")
                .parts("Brake pads", "Rotors", "Caliper hardware / slide pins")
                .alwaysReplace("Caliper bolts with pre-applied threadlocker (replace, or clean and re-apply)", "Pad clips / abutment hardware", "Copper crush washers (if a brake hose is opened)")
                .whileInThere(ServiceItemType.BRAKE_FLUID, ServiceItemType.TIRE_ROTATION)
                .addOns("Brake hoses (if cracked)", "Wheel bearing check (hub is exposed)");

        item(ServiceItemType.BRAKE_FLUID, "Brake fluid flush", Category.BRAKES_TIRES, Action.SERVICE, AlertLevel.STANDARD)
                .every(0, 36).source("Ford Super Duty guidance: every 3 years")
                .why("Brake fluid soaks up water over time. The pedal goes soft under heat, and the calipers "
                        + "and ABS unit corrode from the inside.")
                .parts("DOT 3 brake fluid", "Bleeder hose & catch bottle")
                .whileInThere(ServiceItemType.BRAKES);

        item(ServiceItemType.TIRE_ROTATION, "Tire rotation", Category.BRAKES_TIRES, Action.SERVICE, AlertLevel.STANDARD)
                .every(5000, 6).source(FORD)
                .why("Heavy diesel front ends wear tires fast and unevenly. Rotating with every oil change "
                        + "evens the wear. Check pressures for the load while you're at it.")
                .parts("Tire pressure gauge")
                .whileInThere(ServiceItemType.BRAKES, ServiceItemType.FRONT_END_INSPECTION)
                .addOns("Wheel balance (if there's vibration)");

        item(ServiceItemType.TIRE_AGE, "Tires — age replacement", Category.BRAKES_TIRES, Action.REPLACE, AlertLevel.STANDARD)
                .every(0, 72).source("Ford: replace tires 6 years after manufacture")
                .why("Rubber dries out and cracks even with good tread left. Check the DOT date code on the sidewall. "
                        + "Log this when you put on new tires.")
                .parts("Tires (Load Range E for a Super Duty)")
                .alwaysReplace("Valve stems")
                .whileInThere(ServiceItemType.TIRE_ROTATION, ServiceItemType.FRONT_END_INSPECTION, ServiceItemType.BRAKES)
                .addOns("Alignment");

        // ---------------- 7.3L wear items ----------------
        item(ServiceItemType.CAC_BOOTS, "Intercooler boots & charge pipes", Category.WEAR_73, Action.INSPECT, AlertLevel.QUIET)
                .every(15000, 12).source(COMMON)
                .why("Oil mist from the crankcase vent soaks the silicone boots until they swell, split or blow off. "
                        + "The symptoms are black smoke and a sudden loss of power. Clean the oil film off and "
                        + "torque the T-bolt clamps (about 60 in-lb).")
                .parts("Intercooler boot kit (6 boots on 1999½–2003)", "T-bolt clamps")
                .alwaysReplace("Oil-soaked boots (never reuse one)")
                .whileInThere(ServiceItemType.AIR_FILTER)
                .addOns("Charge pipes (if cracked)", "Crankcase vent reroute kit (stops the oil mist that kills boots)");

        item(ServiceItemType.UVCH, "Under-valve-cover harness (UVCH)", Category.WEAR_73, Action.REPLACE, AlertLevel.QUIET)
                .every(100000, 120).source(SUGGESTED)
                .why("This harness under the valve covers feeds the injectors and glow plugs. On older trucks the "
                        + "connectors loosen or burn, causing misfires, rough starts and a loping idle. It's usually "
                        + "replaced along with the valve cover gaskets.")
                .parts("UVCH harness set (1999–2003)")
                .alwaysReplace("Valve cover gaskets", "Injector O-rings & copper washers (if injectors come out)")
                .whileInThere(ServiceItemType.GLOW_PLUGS)
                .addOns("Glow plugs (connectors are right there)", "Injectors (if balance rates are off)");

        item(ServiceItemType.COOLANT_HOSES, "Coolant hoses & clamps", Category.WEAR_73, Action.INSPECT, AlertLevel.QUIET)
                .every(30000, 24).source(COMMON)
                .why("Engine heat ages rubber hoses even on low miles. Squeeze them and look for swelling, cracks and soft spots. "
                        + "Plan on replacing radiator and heater hoses every 5–7 years.")
                .parts("Upper & lower radiator hoses", "Heater hoses", "Hose clamps")
                .alwaysReplace("Spring hose clamps that have lost tension", "Thermostat gasket (if opened)")
                .whileInThere(ServiceItemType.COOLANT, ServiceItemType.SERPENTINE_BELT)
                .addOns("Thermostat & gasket (coolant is drained anyway)", "Water pump (if weeping)");
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

    /** Miles until the next service: the repeat interval once this item has been logged, if it has one. */
    public int miles(boolean severeDuty, boolean serviced) {
        return serviced && repeats ? repeatMiles : miles(severeDuty);
    }

    public int months(boolean severeDuty, boolean serviced) {
        return serviced && repeats ? repeatMonths : months(severeDuty);
    }

    /** Human label for any stored MaintenanceEvent.type, including UPGRADE/OTHER. */
    public static String labelFor(String storedType) {
        if (storedType == null) return "Unknown";
        if ("UPGRADE".equals(storedType)) return "Upgrade / Modification";
        if ("OTHER".equals(storedType)) return "Other work";
        try {
            ServiceInterval interval = of(ServiceItemType.valueOf(storedType));
            if (interval != null) return interval.displayName;
        } catch (IllegalArgumentException ignored) {
        }
        return storedType;
    }
}
