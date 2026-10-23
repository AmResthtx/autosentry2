package com.autosentry.app.business;

import com.autosentry.app.maintenance.ServiceInterval;
import com.autosentry.app.maintenance.ServiceItemType;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The parts behind each scheduled job, as links the owner opens in their browser.
 * The app itself never goes online (no INTERNET permission); Android hands the
 * link to the browser.
 *
 * Order per job: signed sponsors, then known-good part numbers, then a search
 * for each part ServiceInterval lists for the job.
 */
public final class DealsCatalog {
    private DealsCatalog() {}

    /** Part numbers we'd stand behind in a forum thread. Add one only when it's certain for 1999–2003 7.3L. */
    private static final Object[][] PICKS = {
            {ServiceItemType.OIL_FILTER, "Motorcraft FL-1995 oil filter", "Motorcraft FL1995"},
            {ServiceItemType.FUEL_FILTER, "Motorcraft FD-4596 fuel filter", "Motorcraft FD4596"},
    };

    /** Parts that are the same on any truck, where adding the truck to the search only hurts. */
    private static final String[] GENERIC = {
            "grease gun", "nlgi", "pressure gauge", "test strips", "terminal cleaner",
            "catch bottle", "dot 3", "hose clamps", "t-bolt clamps",
    };

    private static final String TRUCK = "1999-2003 7.3L Powerstroke";

    public static List<PartOffer> offersFor(ServiceItemType type) {
        String tag = BusinessConfig.AMAZON_ASSOCIATES_TAG;
        List<PartOffer> offers = new ArrayList<>();
        for (PartOffer sponsor : BusinessConfig.SPONSORED) {
            if (sponsor.item == type) offers.add(sponsor);
        }
        for (Object[] pick : PICKS) {
            if (pick[0] == type) {
                offers.add(amazon(type, (String) pick[1], (String) pick[2], "Fits 1999–2003 7.3L", tag));
            }
        }
        for (String part : ServiceInterval.of(type).parts) {
            offers.add(amazon(type, part, searchQuery(part), "", tag));
        }
        return offers;
    }

    /** The adapters the app is built and tested for. Every new owner needs one. */
    public static List<PartOffer> adapters() {
        String tag = BusinessConfig.AMAZON_ASSOCIATES_TAG;
        List<PartOffer> offers = new ArrayList<>();
        offers.add(amazon(null, "OBDLink MX+", "OBDLink MX+",
                "Recommended. Classic Bluetooth and handles the 7.3L's J1850 PWM.", tag));
        offers.add(amazon(null, "OBDLink LX", "OBDLink LX Bluetooth",
                "Supported. Get the Bluetooth model; the CX is Bluetooth LE only and won't connect.", tag));
        return offers;
    }

    /** Drops notes in parentheses and adds the truck unless the part already names it or fits any truck. */
    static String searchQuery(String part) {
        String q = part.replaceAll("\\s*\\([^)]*\\)", "").replace('–', '-').trim();
        String lower = q.toLowerCase(Locale.US);
        if (lower.contains("7.3")) return q;
        for (String generic : GENERIC) {
            if (lower.contains(generic)) return q;
        }
        return q + " " + TRUCK;
    }

    static String amazonSearchUrl(String query, String tag) {
        String url = "https://www.amazon.com/s?k=" + encode(query);
        return tag.isEmpty() ? url : url + "&tag=" + encode(tag);
    }

    private static PartOffer amazon(ServiceItemType item, String title, String query, String note, String tag) {
        return new PartOffer(item, title, "Amazon", amazonSearchUrl(query, tag), note,
                tag.isEmpty() ? PartOffer.Kind.PLAIN : PartOffer.Kind.AFFILIATE);
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e); // UTF-8 is always present
        }
    }
}
