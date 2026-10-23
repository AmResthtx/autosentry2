package com.autosentry.app.business;

/**
 * Every money setting in one place. An empty value turns that feature off
 * cleanly: links still work, they just earn nothing and carry no label.
 *
 * Trust rules (docs/business/BUSINESS_PLAN.md):
 *  - Offers attach to scheduled maintenance only, never to trouble codes.
 *  - Every paid placement is labeled "Sponsored"; every commission link "Affiliate link".
 *  - A sponsor never changes which jobs come due or when.
 */
public final class BusinessConfig {
    private BusinessConfig() {}

    /**
     * Amazon Associates tracking ID, e.g. "autosentry-20" (Associates Central > Account).
     * Empty = plain Amazon links with no commission and no "Affiliate link" label.
     */
    public static final String AMAZON_ASSOCIATES_TAG = "";

    /**
     * Signed launch partners, shown first under the job they paid for and labeled
     * "Sponsored". One line per signed deal, for example:
     * <pre>
     *   PartOffer.sponsored(ServiceItemType.FUEL_FILTER, "Example Diesel",
     *       "Fuel filter + bowl O-ring kit", "https://example.com/kit", "Code SENTRY10: 10% off"),
     * </pre>
     */
    static final PartOffer[] SPONSORED = {
    };

    /** True when at least one link could earn money, so the disclosure must show. */
    public static boolean anyPaidLinks() {
        return !AMAZON_ASSOCIATES_TAG.isEmpty() || SPONSORED.length > 0;
    }

    /** Shown at the top of the Deals tab whenever a link can earn money. Amazon requires its sentence as written. */
    public static String disclosure() {
        StringBuilder sb = new StringBuilder(
                "Some links earn AutoSentry a commission or are paid placements, and each one is labeled. "
                        + "That never changes which services come due or when.");
        if (!AMAZON_ASSOCIATES_TAG.isEmpty()) {
            sb.append(" As an Amazon Associate AutoSentry earns from qualifying purchases.");
        }
        return sb.toString();
    }
}
