package com.autosentry.app.obd;

/**
 * How urgently a trouble code needs attention. This is AutoSentry's guidance
 * from the 7.3L code list, not a diagnosis, and the Codes tab says so.
 * Declared most urgent first, so sorting by ordinal puts the worst on top.
 */
public enum DtcSeverity {
    HIGH("Fix now",
            "Can stall the truck, keep it from starting, or cause damage. Limit driving until it's checked."),
    MEDIUM("Fix soon",
            "Something isn't working right. Get it checked before it gets worse."),
    LOW("Fix when convenient",
            "Minor. Safe to keep driving."),
    INFO("Not a fault",
            "A status or self-test message, not a problem with the truck."),
    UNKNOWN("Not in the 7.3L list",
            "AutoSentry doesn't have this code yet. Look it up before relying on the truck.");

    public final String label;
    public final String explanation;

    DtcSeverity(String label, String explanation) {
        this.label = label;
        this.explanation = explanation;
    }
}
