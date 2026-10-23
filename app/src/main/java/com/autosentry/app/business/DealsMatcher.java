package com.autosentry.app.business;

import com.autosentry.app.maintenance.ServiceStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Picks which jobs get parts on the Deals tab: anything at 80% of its interval
 * or past it, most urgent first. 80% is the same "due soon" line the History
 * tab uses, so parts show up early enough to arrive before the job is due,
 * including STANDARD items that only notify once overdue.
 *
 * Input is the service schedule only. Trouble codes never reach this class,
 * so a parts link can never sit next to a diagnosis.
 */
public final class DealsMatcher {
    private DealsMatcher() {}

    public static final class DealGroup {
        public final ServiceStatus status;
        public final List<PartOffer> offers;

        DealGroup(ServiceStatus status, List<PartOffer> offers) {
            this.status = status;
            this.offers = offers;
        }
    }

    /** @param hidden ServiceItemType names the owner hid; they get no deals either. */
    public static List<DealGroup> match(List<ServiceStatus> statuses, Set<String> hidden) {
        List<ServiceStatus> due = new ArrayList<>();
        for (ServiceStatus s : statuses) {
            if (s.dueSoon && !hidden.contains(s.type.name())) due.add(s);
        }
        Collections.sort(due, (a, b) -> Double.compare(b.percentOfLifeUsed, a.percentOfLifeUsed));
        List<DealGroup> groups = new ArrayList<>();
        for (ServiceStatus s : due) {
            List<PartOffer> offers = DealsCatalog.offersFor(s.type);
            if (!offers.isEmpty()) groups.add(new DealGroup(s, offers));
        }
        return groups;
    }
}
