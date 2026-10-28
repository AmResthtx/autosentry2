package com.autosentry.app.obd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runs the systems scan end to end against a simulated adapter and 7.3L. */
public class TruckProbeTest {

    private static TruckProbe.Options options(boolean active) {
        TruckProbe.Options o = new TruckProbe.Options();
        o.active = active;
        o.listenMs = 1000;
        o.minIntervalMs = 0;
        o.backoffMs = 0;
        return o;
    }

    private static TruckProbe.Result run(FakeElm elm, TruckProbe.Options options) {
        ELM327Adapter adapter = new ELM327Adapter(elm.in, elm.out);
        return TruckProbe.run(adapter, "test tablet", options, step -> { }, () -> false);
    }

    private static int requestsReported(String report) {
        Matcher m = Pattern.compile("Requests sent to the truck: (\\w+)").matcher(report);
        assertTrue("report states how many requests went out", m.find());
        return m.group(1).equals("none") ? 0 : Integer.parseInt(m.group(1));
    }

    // ---------------------------------------------------------------- listen-only

    @Test
    public void listenOnlySendsNothingOnTheBus() {
        FakeElm elm = new FakeElm();
        TruckProbe.Result result = run(elm, options(false));
        assertTrue("no bus requests: " + elm.busRequests, elm.busRequests.isEmpty());
        assertTrue(elm.received.contains("ATMA"));
        assertEquals(1, elm.stopKeys);
        assertEquals(0, requestsReported(result.report));
        assertTrue(result.report.contains("Requests sent to the truck: none"));
        assertTrue(result.json.contains("\"mode\": \"listen-only\""));
    }

    @Test
    public void listenOnlyReportsWhoBroadcastsWhatAndHowOften() {
        TruckProbe.Result result = run(new FakeElm(), options(false));
        String r = result.report;
        assertTrue(r, r.contains("DATA MOVEMENT"));
        assertTrue(r, r.contains("A82910  from 10 (Engine computer (PCM)) to 29"));
        assertTrue(r, r.contains("A84910"));
        assertTrue(r, r.matches("(?s).*A84910 .*periodic every ~\\d+ ms.*"));
        assertTrue(r, r.matches("(?s).*E8FF40 .*sporadic.*"));
        // The 300-650 ms dropout in A8 29 10 shows up as a gap.
        assertTrue(r, r.matches("(?s).*GAPS AND ERRORS.*A82910 skipped.*"));
        assertTrue(r, r.contains("MODULES HEARD"));
        assertTrue(r, r.contains("Needs an active scan"));
    }

    // ---------------------------------------------------------------- active

    @Test
    public void activeScanSendsOnlyReadsAndListsEveryOne() {
        FakeElm elm = new FakeElm();
        TruckProbe.Result result = run(elm, options(true));
        assertFalse(result.linkLost);
        assertTrue(result.report, result.report.contains("Outcome: complete"));
        assertFalse(elm.busRequests.isEmpty());
        for (String request : elm.busRequests) {
            String cmd = request.substring(request.indexOf("] ") + 2);
            int service = Integer.parseInt(cmd.substring(0, 2), 16);
            assertTrue("not a read: " + request, ReadOnlyGuard.READ_SERVICES.contains(service));
        }
        // Every frame the adapter received is in Commands Sent, with its header, in order.
        assertEquals(elm.busRequests.size(), requestsReported(result.report));
        int at = result.report.indexOf("Every request, in order");
        for (String request : elm.busRequests) {
            int next = result.report.indexOf("  " + request + "\n", at);
            assertTrue("missing from Commands Sent: " + request, next > 0);
            at = next;
        }
        for (String cmd : elm.received) {
            assertFalse("adapter memory write sent: " + cmd, cmd.startsWith("ATSP") || cmd.startsWith("ATPP"));
        }
    }

    @Test
    public void activeScanFindsReadingsCodesAndFordPids() {
        TruckProbe.Result result = run(new FakeElm(), options(true));
        String r = result.report;
        assertTrue(r, r.contains("0C  Engine RPM"));
        assertTrue(r, r.contains("Stored codes (Mode 03): P0470"));
        assertTrue(r, r.contains("saved for P0470"));
        assertTrue(r, r.contains("1310  Engine oil temp (AutoSentry reads this): answers only when sent straight to the PCM"));
        assertTrue(r, r.contains("1127 = 01 02"));
        assertTrue(r, r.contains("10  Engine computer (PCM)"));
        // In-app data flow: 13 is answered but unused; 1446/1434 are read by the app and answer.
        assertTrue(r, r.matches("(?s).*Truck answers, AutoSentry ignores: 13 .*"));
        assertTrue(r, r.matches("(?s).*Ford PIDs AutoSentry reads that answer: 1310.*1446.*1434.*"));
        assertTrue(r, r.contains("Trouble codes: P0470 (stored)"));
    }

    @Test
    public void responseTimesAreReported() {
        TruckProbe.Result result = run(new FakeElm(), options(true));
        assertTrue(result.report, result.report.matches("(?s).*0D  Vehicle speed: .*\\(\\d+ ms\\).*"));
        assertTrue(result.json, result.json.contains("\"responseMs\": "));
    }

    @Test
    public void vinIsHiddenUnlessAskedFor() {
        TruckProbe.Result hidden = run(new FakeElm(), options(true));
        assertFalse(hidden.report.contains(FakeElm.VIN));
        assertFalse(hidden.json.contains(FakeElm.VIN));
        assertTrue(hidden.report.contains("VIN: reported, hidden"));

        TruckProbe.Options withVin = options(true);
        withVin.includeVin = true;
        TruckProbe.Result shown = run(new FakeElm(), withVin);
        assertTrue(shown.report.contains("VIN: " + FakeElm.VIN));
    }

    @Test
    public void pendingModuleIsAskedAgain() {
        FakeElm elm = new FakeElm();
        elm.override("221446", "7F2278"); // still working on it
        TruckProbe.Result result = run(elm, options(true));
        assertTrue(elm.count("221446") >= 3);
        assertTrue(result.report, result.report.contains("1446  Injection control pressure (AutoSentry reads this): answers"));
    }

    @Test
    public void unexpectedReplyStopsCleanlyAndStillReports() {
        FakeElm elm = new FakeElm();
        elm.override("010D", "5003");
        TruckProbe.Result result = run(elm, options(true));
        assertFalse(result.linkLost);
        assertTrue(result.report, result.report.contains("Outcome: stopped safely: unexpected reply to 010D"));
        assertFalse("nothing after the stop", elm.received.contains("0113"));
        assertTrue(result.report.contains("COMMANDS SENT"));
        assertTrue(result.json.startsWith("{"));
    }

    // ---------------------------------------------------------------- the lock on the adapter itself

    @Test
    public void lockedAdapterNeverWritesARefusedCommand() throws IOException {
        FakeElm elm = new FakeElm();
        ELM327Adapter adapter = new ELM327Adapter(elm.in, elm.out);
        adapter.setGuard(new ReadOnlyGuard(false));
        try {
            adapter.clearTroubleCodes();
            fail("Mode 04 must be refused");
        } catch (ReadOnlyGuard.Blocked expected) {
            // refused
        }
        try {
            adapter.sendCommand("ATSP0");
            fail("ATSP must be refused");
        } catch (ReadOnlyGuard.Blocked expected) {
            // refused
        }
        assertTrue(elm.received.isEmpty());
    }

    @Test
    public void listenOnlyLockRefusesEvenAReading() throws IOException {
        FakeElm elm = new FakeElm();
        ELM327Adapter adapter = new ELM327Adapter(elm.in, elm.out);
        adapter.setGuard(new ReadOnlyGuard(true));
        try {
            adapter.readPid(0x0C);
            fail("listen-only must refuse 010C");
        } catch (ReadOnlyGuard.Blocked expected) {
            // refused
        }
        assertTrue(elm.received.isEmpty());
        adapter.setGuard(null);
        assertEquals(1, adapter.readPid(0x0C).length - 1);
    }

    // ---------------------------------------------------------------- report format

    @Test
    public void jsonHasEverySectionAndEscapes() {
        TruckProbe.Result result = run(new FakeElm(), options(true));
        for (String key : new String[]{"summary", "adapter", "bus", "standardReadings", "otherServices",
                "fordEnhanced", "modules", "pcmSweep", "appDataFlow", "problems", "commandsSent"}) {
            assertTrue("missing section " + key, result.json.contains("\"" + key + "\": {"));
        }
        assertTrue(result.json.indexOf("\"summary\"") < result.json.indexOf("\"adapter\""));
        StringBuilder sb = new StringBuilder();
        ScanReport.writeJson(sb, ScanReport.fields("a", "q\"b\\c\nd", "n", Double.NaN, "i", 3), "");
        assertEquals("{\n  \"a\": \"q\\\"b\\\\c\\nd\",\n  \"n\": null,\n  \"i\": 3\n}", sb.toString());
    }

    @Test
    public void summaryComesFirst() {
        String r = run(new FakeElm(), options(true)).report;
        assertTrue(r.indexOf("SUMMARY") < r.indexOf("ADAPTER"));
        assertTrue(r.indexOf("SUMMARY") > r.indexOf("AUTOSENTRY TRUCK SYSTEMS SCAN"));
    }
}
