package com.autosentry.app.business;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.autosentry.app.maintenance.ServiceInterval;
import com.autosentry.app.maintenance.ServiceItemType;
import com.autosentry.app.maintenance.ServiceStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import org.junit.Test;

public class DealsTest {

    /** A status at the given share of its mileage interval. */
    private static ServiceStatus at(ServiceItemType type, double percent) {
        ServiceInterval interval = ServiceInterval.of(type);
        int miles = interval.miles(true, true);
        if (miles <= 0) miles = 10000;
        return new ServiceStatus(interval, miles * percent / 100.0, miles, 0, 0, true);
    }

    @Test
    public void onlyJobsAtEightyPercentOrMoreGetDeals() {
        List<ServiceStatus> statuses = new ArrayList<>();
        statuses.add(at(ServiceItemType.OIL_FILTER, 79));
        statuses.add(at(ServiceItemType.FUEL_FILTER, 80));
        statuses.add(at(ServiceItemType.AIR_FILTER, 130));

        List<DealsMatcher.DealGroup> groups = DealsMatcher.match(statuses, Collections.emptySet());

        assertEquals(2, groups.size());
        assertEquals("most overdue first", ServiceItemType.AIR_FILTER, groups.get(0).status.type);
        assertEquals(ServiceItemType.FUEL_FILTER, groups.get(1).status.type);
    }

    @Test
    public void hiddenJobsGetNoDeals() {
        List<ServiceStatus> statuses = Collections.singletonList(at(ServiceItemType.TRANSFER_CASE_FLUID, 120));
        HashSet<String> hidden = new HashSet<>();
        hidden.add(ServiceItemType.TRANSFER_CASE_FLUID.name());

        assertTrue(DealsMatcher.match(statuses, hidden).isEmpty());
    }

    @Test
    public void everyScheduledJobHasAtLeastOnePart() {
        for (ServiceItemType type : ServiceInterval.allTypes()) {
            assertFalse(type + " has no parts to offer", DealsCatalog.offersFor(type).isEmpty());
        }
    }

    @Test
    public void knownGoodPartNumberComesBeforeGenericSearch() {
        List<PartOffer> offers = DealsCatalog.offersFor(ServiceItemType.FUEL_FILTER);
        assertEquals("Motorcraft FD-4596 fuel filter", offers.get(0).title);
    }

    @Test
    public void labelsMatchHowTheLinkEarns() {
        // With no Associates tag set, links are plain and unlabeled.
        for (PartOffer offer : DealsCatalog.offersFor(ServiceItemType.OIL_FILTER)) {
            if (BusinessConfig.AMAZON_ASSOCIATES_TAG.isEmpty()) {
                assertEquals(PartOffer.Kind.PLAIN, offer.kind);
                assertEquals("", offer.label());
                assertFalse(offer.url.contains("tag="));
            } else {
                assertEquals("Affiliate link", offer.label());
            }
        }
        PartOffer sponsor = PartOffer.sponsored(ServiceItemType.OIL_FILTER, "Shop", "Kit", "https://x", "");
        assertEquals("Sponsored", sponsor.label());
    }

    @Test
    public void affiliateTagIsAppendedOnlyWhenSet() {
        assertEquals("https://www.amazon.com/s?k=OBDLink+MX%2B", DealsCatalog.amazonSearchUrl("OBDLink MX+", ""));
        assertEquals("https://www.amazon.com/s?k=OBDLink+MX%2B&tag=autosentry-20",
                DealsCatalog.amazonSearchUrl("OBDLink MX+", "autosentry-20"));
    }

    @Test
    public void searchQueriesNameTheTruckOnlyWhenItMatters() {
        assertEquals("Oil filter for 1999-2003 7.3L", DealsCatalog.searchQuery("Oil filter for 1999–2003 7.3L"));
        assertEquals("15W-40 heavy-duty diesel oil 1999-2003 7.3L Powerstroke",
                DealsCatalog.searchQuery("15W-40 heavy-duty diesel oil (about 15 qt with filter)"));
        assertEquals("Grease gun", DealsCatalog.searchQuery("Grease gun"));
    }

    @Test
    public void disclosureOnlyNamesAmazonWhenTagged() {
        assertEquals(!BusinessConfig.AMAZON_ASSOCIATES_TAG.isEmpty(),
                BusinessConfig.disclosure().contains("Amazon Associate"));
    }
}
