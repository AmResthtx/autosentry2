package com.autosentry.app.business;

/**
 * Every money setting in one place. An empty value turns that feature off
 * cleanly: links still work, they just earn nothing and carry no label.
 *
 * Where trust lives (docs/business/BUSINESS_PLAN.md): in what the app says
 * about the truck. AutoSentry is the observer, not the supplier, so money is
 * walled off from everything it observes:
 *  - Readings, codes, severity, intervals and due dates are never touched by an offer.
 *  - Offers attach to scheduled maintenance only, never to trouble codes.
 *  - Sellers stand behind their own parts. Every paid placement is labeled
 *    "Sponsored"; every commission link "Affiliate link".
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

    /** Always first on the Deals tab: keeps what the app observes apart from what sellers offer. */
    public static String dealsIntro() {
        return "Parts for the services your truck has coming due. These come from sellers, who stand behind "
                + "their own parts. AutoSentry's job is telling you what your truck needs and when, "
                + "and no seller can change that.";
    }

    /** Shown under the intro whenever a link can earn money. Amazon requires its sentence as written. */
    public static String disclosure() {
        StringBuilder sb = new StringBuilder(
                "Links labeled Sponsored or Affiliate link earn AutoSentry money.");
        if (!AMAZON_ASSOCIATES_TAG.isEmpty()) {
            sb.append(" As an Amazon Associate AutoSentry earns from qualifying purchases.");
        }
        return sb.toString();
    }
}
