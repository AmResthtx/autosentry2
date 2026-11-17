package com.autosentry.app.obd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.autosentry.app.obd.PollPlan.State;

import org.junit.Test;

import java.io.StringWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The catalog, the poll plan, the graph history and the CSV recorder. */
public class LiveDataTest {

    // ---- catalog ----

    @Test
    public void everyReadingSaysWhereItComesFromAndHowFarToTrustIt() {
        Set<String> fordCodes = new HashSet<>();
        for (PidCatalog.Pid pid : PidCatalog.all()) {
            assertNotNull(pid.name, pid.trust);
            assertFalse(pid.name + " has no source", pid.source.isEmpty());
            if (pid.computed) {
                assertEquals(pid.name, PidCatalog.Trust.CALCULATED, pid.trust);
                assertNull(pid.request());
            } else if (pid.fordCode != null) {
                assertTrue(pid.name + ": a Ford reading is never 'standard'",
                        pid.trust == PidCatalog.Trust.PUBLISHED || pid.trust == PidCatalog.Trust.UNVERIFIED
                                || pid.trust == PidCatalog.Trust.CONFIRMED);
                assertFalse(pid.name + " has no formula", pid.formula.isEmpty());
                assertTrue(pid.fordCode, pid.fordCode.matches("[0-9A-F]{4}"));
                assertTrue("duplicate Ford code " + pid.fordCode, fordCodes.add(pid.fordCode));
                assertTrue(pid.request(), pid.request().startsWith("22 "));
            } else {
                assertTrue(pid.request(), pid.request().matches("01 [0-9A-F]{2}"));
            }
            // A single-source reading must say so in the name people see on the dashboard.
            if (pid.trust == PidCatalog.Trust.UNVERIFIED) assertTrue(pid.name, pid.name.contains("unverified"));
        }
    }

    @Test
    public void nothingIsMarkedConfirmedWithoutACaptureFromTheTruck() {
        // Flip a reading to CONFIRMED only together with its capture in docs/PROVENANCE.md.
        for (PidCatalog.Pid pid : PidCatalog.all()) assertFalse(pid.name, pid.trust == PidCatalog.Trust.CONFIRMED);
    }

    @Test
    public void fordReadingsDecodeFromTheirDataBytes() {
        assertEquals(893 * 0.56, PidCatalog.get(PidCatalog.FORD_ICP).decode(new int[]{0x03, 0x7D}), 1e-9);
        assertEquals(100.0, PidCatalog.get(PidCatalog.FORD_IPR).decode(new int[]{0xFF}), 1e-9);
        assertEquals(14.5, PidCatalog.get(PidCatalog.FORD_BARO).decode(new int[]{0x01, 0x90}), 1e-9);
        assertEquals(212.98 - 0.0036 * 0x8000, PidCatalog.get(PidCatalog.FORD_TFT).decode(new int[]{0x80, 0x00}), 1e-9);
        assertTrue(Double.isNaN(PidCatalog.get(PidCatalog.FORD_ICP).decode(new int[]{0x03})));
        assertTrue(Double.isNaN(PidCatalog.get(PidCatalog.FORD_ICP).decode(null)));
        assertEquals("22 14 46 to the PCM (header C4 10 F1)", PidCatalog.get(PidCatalog.FORD_ICP).request());
    }

    // ---- poll plan ----

    @Test
    public void fastReadingsAreAskedEveryPassAndSlowOnesEveryTwoSeconds() {
        PollPlan plan = new PollPlan();
        List<Integer> wanted = Arrays.asList(PidCatalog.RPM, 0x0F);
        assertEquals(wanted, plan.due(wanted, 1000));
        plan.answered(PidCatalog.RPM, 1000);
        plan.answered(0x0F, 1000);
        assertEquals(Collections.singletonList(PidCatalog.RPM), plan.due(wanted, 1250));
        assertEquals(Collections.singletonList(PidCatalog.RPM), plan.due(wanted, 2999));
        assertEquals(wanted, plan.due(wanted, 3000));
    }

    @Test
    public void aSilentReadingIsDroppedAfterThreeMissesAndStopsSlowingTheRest() {
        PollPlan plan = new PollPlan();
        List<Integer> wanted = Arrays.asList(PidCatalog.RPM, 0x0B);
        long now = 0;
        for (int i = 0; i < PollPlan.MISSES_BEFORE_DROP; i++) {
            assertTrue(plan.due(wanted, now).contains(0x0B));
            plan.answered(PidCatalog.RPM, now);
            plan.missed(0x0B, now);
            now += 250;
        }
        assertEquals(State.NO_ANSWER, plan.state(0x0B));
        assertTrue(plan.state(0x0B).unavailable());
        assertEquals("truck didn't answer", plan.state(0x0B).label);
        // Not asked again for 30 s, while RPM carries on every pass.
        assertEquals(Collections.singletonList(PidCatalog.RPM), plan.due(wanted, now));
        assertEquals(Collections.singletonList(PidCatalog.RPM), plan.due(wanted, now + PollPlan.NO_ANSWER_RETRY_MS - 600));
        assertEquals(wanted, plan.due(wanted, now + PollPlan.NO_ANSWER_RETRY_MS));
    }

    @Test
    public void oneOrTwoMissesDontDropAReading() {
        PollPlan plan = new PollPlan();
        plan.answered(0x0B, 0);
        plan.missed(0x0B, 250);
        plan.missed(0x0B, 500);
        assertEquals(State.LIVE, plan.state(0x0B));
        assertFalse(plan.hasValue(0x0B)); // but the stale value isn't shown as current
        plan.answered(0x0B, 750);
        assertTrue(plan.hasValue(0x0B));
    }

    @Test
    public void aDroppedReadingComesBackWhenItAnswers() {
        PollPlan plan = new PollPlan();
        for (int i = 0; i < 3; i++) plan.missed(0x0B, i);
        assertEquals(State.NO_ANSWER, plan.state(0x0B));
        plan.answered(0x0B, 40_000);
        assertEquals(State.LIVE, plan.state(0x0B));
        assertTrue(plan.due(Collections.singletonList(0x0B), 40_250).contains(0x0B));
    }

    @Test
    public void aRefusedReadingIsRetriedOnlyEveryFiveMinutes() {
        PollPlan plan = new PollPlan();
        List<Integer> wanted = Collections.singletonList(PidCatalog.FORD_TFT);
        plan.refused(PidCatalog.FORD_TFT, 0);
        assertEquals(State.REFUSED, plan.state(PidCatalog.FORD_TFT));
        assertTrue(plan.due(wanted, PollPlan.REFUSED_RETRY_MS - 1).isEmpty());
        assertEquals(wanted, plan.due(wanted, PollPlan.REFUSED_RETRY_MS));
        plan.missed(PidCatalog.FORD_TFT, PollPlan.REFUSED_RETRY_MS); // silence doesn't upgrade a refusal
        assertEquals(State.REFUSED, plan.state(PidCatalog.FORD_TFT));
    }

    @Test
    public void statesStartAsWaitingAndResetOnANewConnection() {
        PollPlan plan = new PollPlan();
        assertEquals(State.WAITING, plan.state(0x0C));
        assertFalse(State.WAITING.unavailable());
        plan.notOffered(0x5E);
        assertEquals(State.NOT_OFFERED, plan.state(0x5E));
        plan.reset();
        assertEquals(State.WAITING, plan.state(0x5E));
    }

    // ---- graph history ----

    @Test
    public void historyReturnsPointsInOrderWithinTheWindow() {
        ReadingHistory history = new ReadingHistory();
        for (int i = 0; i < 10; i++) history.add(0x0C, i * 1000L, 700 + i);
        ReadingHistory.Series all = history.since(0x0C, 0);
        assertEquals(10, all.size());
        assertEquals(700, all.values[0], 1e-6);
        assertEquals(709, all.values[9], 1e-6);
        ReadingHistory.Series recent = history.since(0x0C, 7000);
        assertEquals(3, recent.size());
        assertEquals(7000, recent.times[0]);
        assertEquals(707, recent.min(), 1e-6);
        assertEquals(709, recent.max(), 1e-6);
        assertEquals(0, history.since(0x0D, 0).size());
    }

    @Test
    public void historySkipsMissingValuesAndPointsTooCloseTogether() {
        ReadingHistory history = new ReadingHistory();
        history.add(1, 0, 5);
        history.add(1, 100, 6); // too soon
        history.add(1, 500, Double.NaN); // no reading: no point, the line just has a gap in time
        history.add(1, 1000, 7);
        ReadingHistory.Series s = history.since(1, 0);
        assertEquals(2, s.size());
        assertEquals(7, s.values[1], 1e-6);
    }

    @Test
    public void historyKeepsOnlyTheNewestPointsOnceFull() {
        ReadingHistory history = new ReadingHistory();
        int total = ReadingHistory.CAPACITY + 50;
        for (int i = 0; i < total; i++) history.add(1, i * 500L, i);
        ReadingHistory.Series s = history.since(1, 0);
        assertEquals(ReadingHistory.CAPACITY, s.size());
        assertEquals(50, s.values[0], 1e-6);
        assertEquals(total - 1, s.values[s.size() - 1], 1e-6);
    }

    // ---- CSV recorder ----

    @Test
    public void recorderWritesOneRowASecondAndLeavesMissingReadingsEmpty() throws Exception {
        StringWriter text = new StringWriter();
        LiveRecorder recorder = new LiveRecorder(text,
                Arrays.asList(PidCatalog.RPM, PidCatalog.FORD_ICP, PidCatalog.FORD_MAP, 0x7777), 10_000);
        Map<Integer, Double> values = new HashMap<>();
        values.put(PidCatalog.RPM, 1850.0);
        values.put(PidCatalog.FORD_ICP, 1234.5);
        assertTrue(recorder.tick(10_000, values));
        assertFalse(recorder.tick(10_500, values)); // not yet a second
        values.remove(PidCatalog.FORD_ICP);
        values.put(PidCatalog.FORD_MAP, Double.NaN);
        assertTrue(recorder.tick(11_000, values));
        recorder.close();
        String[] lines = text.toString().split("\n");
        assertEquals(3, lines.length);
        assertEquals("Time,Seconds,Engine RPM (rpm) [standard],Injection Control Pressure (psi) [published],"
                + "MAP (unverified) (psi) [unverified]", lines[0]);
        assertTrue(lines[1], lines[1].endsWith(",0.0,1850.000,1234.500,"));
        assertTrue(lines[2], lines[2].endsWith(",1.0,1850.000,,"));
        assertEquals(2, recorder.rows());
    }
}
