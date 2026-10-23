package com.autosentry.app.business;

import com.autosentry.app.maintenance.ServiceItemType;

/** One link on the Deals tab: a part for a job that's coming due, or a supported adapter. */
public final class PartOffer {

    public enum Kind {
        /** A partner paid to be here. Always labeled. */
        SPONSORED,
        /** Earns a commission, with no say over placement. Always labeled. */
        AFFILIATE,
        /** Earns nothing. */
        PLAIN
    }

    /** Null for offers not tied to a service (adapters). */
    public final ServiceItemType item;
    public final String title;
    public final String vendor;
    public final String url;
    /** Discount code, fitment note, etc. Empty when there's nothing to add. */
    public final String note;
    public final Kind kind;

    PartOffer(ServiceItemType item, String title, String vendor, String url, String note, Kind kind) {
        this.item = item;
        this.title = title;
        this.vendor = vendor;
        this.url = url;
        this.note = note == null ? "" : note;
        this.kind = kind;
    }

    public static PartOffer sponsored(ServiceItemType item, String vendor, String title, String url, String note) {
        return new PartOffer(item, title, vendor, url, note, Kind.SPONSORED);
    }

    /** "Sponsored", "Affiliate link", or "" for a plain link. */
    public String label() {
        switch (kind) {
            case SPONSORED: return "Sponsored";
            case AFFILIATE: return "Affiliate link";
            default: return "";
        }
    }
}
