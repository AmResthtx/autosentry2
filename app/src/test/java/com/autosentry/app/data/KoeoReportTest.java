package com.autosentry.app.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.autosentry.app.obd.DTCReader;
import com.autosentry.app.obd.DTCReader.Mode;
import com.autosentry.app.obd.DTCReader.Outcome;

import org.junit.Test;

import java.util.Arrays;

public class KoeoReportTest {

    private static DTCReader.Result result(Mode mode, Outcome outcome, String problem, int attempts, String... codes) {
        return new DTCReader.Result(mode, outcome, Arrays.asList(codes), problem, attempts);
    }

    @Test
    public void failedReadIsRecordedAsFailed() {
        KoeoReport r = new KoeoReport();
        r.setCodes(result(Mode.STORED, Outcome.FAILED, "the truck didn't answer", 3));
        r.setCodes(result(Mode.PENDING, Outcome.NONE, null, 3));
        assertNull(r.storedCodes);
        assertEquals("couldn't read after 3 tries: the truck didn't answer", r.storedCodesProblem);
        assertEquals("", r.pendingCodes);
        assertNull(r.pendingCodesProblem);
        String summary = r.summary();
        assertTrue(summary, summary.contains("Stored codes: COULDN'T READ after 3 tries: the truck didn't answer\n"));
        assertTrue(summary, summary.contains("Pending codes: none\n"));
    }

    @Test
    public void engineStartedReadSaysSo() {
        KoeoReport r = new KoeoReport();
        r.setCodes(result(Mode.STORED, Outcome.FAILED, DTCReader.ENGINE_STARTED, 1));
        assertTrue(r.summary().contains("Stored codes: COULDN'T READ: the engine started before codes could be read"));
    }

    @Test
    public void incompleteReadKeepsWhatCameThrough() {
        KoeoReport r = new KoeoReport();
        r.setCodes(result(Mode.STORED, Outcome.FAILED,
                "the truck says 2 codes are stored but only 1 came through", 3, "P1211"));
        assertEquals("P1211", r.storedCodes);
        String summary = r.summary();
        assertTrue(summary, summary.contains("Stored codes: COULDN'T READ all of them after 3 tries: "
                + "the truck says 2 codes are stored but only 1 came through"));
        assertTrue(summary, summary.contains("\n    P1211"));
    }

    @Test
    public void cleanReadsReadAsBefore() {
        KoeoReport r = new KoeoReport();
        r.setCodes(result(Mode.STORED, Outcome.CODES, null, 1, "P1211", "P0340"));
        r.setCodes(result(Mode.PENDING, Outcome.NONE, null, 1));
        assertEquals("P1211,P0340", r.storedCodes);
        assertNull(r.storedCodesProblem);
        assertTrue(r.summary().contains("Pending codes: none\n"));
    }

    @Test
    public void oldRowsKeepTheirMeaning() {
        assertEquals("no answer", KoeoReport.codes(null, null));
        assertEquals("none", KoeoReport.codes("", null));
        // What MIGRATION_3_4 writes on an old "none": shown as unconfirmed, not as a clean result.
        assertEquals(AppDatabase.UNCONFIRMED_NONE, KoeoReport.codes("", AppDatabase.UNCONFIRMED_NONE));
    }
}
