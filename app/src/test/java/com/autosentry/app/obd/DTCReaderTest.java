package com.autosentry.app.obd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.autosentry.app.obd.DTCReader.Attempt;
import com.autosentry.app.obd.DTCReader.CodeRead;
import com.autosentry.app.obd.DTCReader.MilStatus;
import com.autosentry.app.obd.DTCReader.Mode;
import com.autosentry.app.obd.DTCReader.Outcome;
import com.autosentry.app.obd.DTCReader.Reply;
import com.autosentry.app.obd.DTCReader.Result;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;

public class DTCReaderTest {

    private static final MilStatus CLEAN = new MilStatus(false, 0);

    // ---------------------------------------------------------------- classify

    @Test
    public void j1850ThreeCodesPerLine() {
        Attempt a = DTCReader.classify("43 01 23 04 56 07 89\r\n43 11 11 00 00 00 00\r\n\r\n>", Mode.STORED);
        assertEquals(Reply.CODES, a.reply);
        assertEquals(Arrays.asList("P0123", "P0456", "P0789", "P1111"), a.codes);
        assertNull(a.problem);
    }

    @Test
    public void j1850WithSpacesOff() {
        Attempt a = DTCReader.classify("SEARCHING...\r43121100000000\r\r>", Mode.STORED);
        assertEquals(Reply.CODES, a.reply);
        assertEquals(Collections.singletonList("P1211"), a.codes);
    }

    @Test
    public void canWithCountByte() {
        Attempt a = DTCReader.classify("430201230456\r\r>", Mode.STORED);
        assertEquals(Reply.CODES, a.reply);
        assertEquals(Arrays.asList("P0123", "P0456"), a.codes);
    }

    @Test
    public void canMultiFrameBlock() {
        Attempt a = DTCReader.classify("00A\r0:430401230456\r1:07891234000000\r\r>", Mode.STORED);
        assertEquals(Reply.CODES, a.reply);
        assertEquals(Arrays.asList("P0123", "P0456", "P0789", "P1234"), a.codes);
    }

    @Test
    public void allPaddingIsAnsweredNone() {
        assertEquals(Reply.NONE, DTCReader.classify("43 00 00 00 00 00 00\r\r>", Mode.STORED).reply);
        assertEquals(Reply.NONE, DTCReader.classify("43000000000000\r>", Mode.STORED).reply);
        assertEquals(Reply.NONE, DTCReader.classify("4300\r\r>", Mode.STORED).reply); // CAN, count 0
        assertEquals(Reply.NONE, DTCReader.classify("BUS INIT: ...OK\r47 00 00 00 00 00 00\r>", Mode.PENDING).reply);
    }

    @Test
    public void pendingRepliesUse47() {
        Attempt a = DTCReader.classify("47 03 40 00 00 00 00\r>", Mode.PENDING);
        assertEquals(Reply.CODES, a.reply);
        assertEquals(Collections.singletonList("P0340"), a.codes);
        // A stored-code reply to a pending request isn't an answer to it.
        assertEquals(Reply.FAILED, DTCReader.classify("43 03 40 00 00 00 00\r>", Mode.PENDING).reply);
    }

    @Test
    public void noDataIsNotNone() {
        Attempt a = DTCReader.classify("NO DATA\r\r>", Mode.STORED);
        assertEquals(Reply.NO_DATA, a.reply);
        assertTrue(a.codes.isEmpty());
        assertEquals(Reply.NO_DATA, DTCReader.classify("SEARCHING...\r\nNO DATA\r\n\r\n>", Mode.STORED).reply);
    }

    @Test
    public void timeoutOrEmptyFails() {
        assertEquals(Reply.FAILED, DTCReader.classify(null, Mode.STORED).reply);
        assertEquals(Reply.FAILED, DTCReader.classify("", Mode.STORED).reply);
        assertEquals(Reply.FAILED, DTCReader.classify("  \r\n", Mode.STORED).reply);
        assertEquals("the adapter didn't answer", DTCReader.classify("", Mode.STORED).problem);
        // Data but no prompt: the read timed out partway through.
        Attempt cut = DTCReader.classify("43 01 23 00 00 00 00\r", Mode.STORED);
        assertEquals(Reply.FAILED, cut.reply);
        assertTrue(cut.codes.isEmpty());
        assertEquals(Reply.FAILED, DTCReader.classify(">", Mode.STORED).reply);
    }

    @Test
    public void negativeReplyIsRefusedWithItsReason() {
        Attempt a = DTCReader.classify("7F 03 12\r\r>", Mode.STORED);
        assertEquals(Reply.REFUSED, a.reply);
        assertEquals(0x12, a.nrc);
        assertTrue(a.problem, a.problem.contains("not supported"));
        assertEquals(Reply.REFUSED, DTCReader.classify("7F0711\r>", Mode.PENDING).reply);
    }

    @Test
    public void busyReplyIsRetriedNotRefused() {
        assertEquals(Reply.FAILED, DTCReader.classify("7F 03 21\r>", Mode.STORED).reply);
        assertEquals(Reply.FAILED, DTCReader.classify("7F 03 78\r>", Mode.STORED).reply);
    }

    @Test
    public void realAnswerBeatsAnotherModulesRefusal() {
        Attempt a = DTCReader.classify("7F 03 11\r43 01 23 00 00 00 00\r>", Mode.STORED);
        assertEquals(Reply.CODES, a.reply);
    }

    @Test
    public void adapterErrorsFail() {
        assertEquals(Reply.FAILED, DTCReader.classify("?\r\r>", Mode.STORED).reply);
        assertEquals(Reply.FAILED, DTCReader.classify("BUS INIT: ...ERROR\r\r>", Mode.STORED).reply);
        assertEquals("the adapter couldn't start talking to the truck",
                DTCReader.classify("BUS INIT: ...ERROR\r\r>", Mode.STORED).problem);
        assertEquals(Reply.FAILED, DTCReader.classify("SEARCHING...\rUNABLE TO CONNECT\r\r>", Mode.STORED).reply);
        assertEquals(Reply.FAILED, DTCReader.classify("BUS ERROR\r>", Mode.STORED).reply);
        assertEquals(Reply.FAILED, DTCReader.classify("CAN ERROR\r>", Mode.STORED).reply);
        assertEquals(Reply.FAILED, DTCReader.classify("STOPPED\r>", Mode.STORED).reply);
        assertEquals(Reply.FAILED, DTCReader.classify("ERR94\r>", Mode.STORED).reply);
        assertEquals(Reply.FAILED, DTCReader.classify("ELM327 v1.5\r\r>", Mode.STORED).reply);
    }

    @Test
    public void errorNextToDataStillFails() {
        // Part of the answer was lost, so the codes that did arrive can't be the whole list.
        assertEquals(Reply.FAILED, DTCReader.classify("43 01 23 04 56 07 89\rBUFFER FULL\r>", Mode.STORED).reply);
        assertEquals(Reply.FAILED, DTCReader.classify("43 01 23 00 00 00 00\r<DATA ERROR\r>", Mode.STORED).reply);
    }

    @Test
    public void garbageFails() {
        assertEquals(Reply.FAILED, DTCReader.classify("41 00 BE 3F A8 13\r>", Mode.STORED).reply);
        assertEquals(Reply.FAILED, DTCReader.classify("HELLO\r>", Mode.STORED).reply);
        assertEquals(Reply.FAILED, DTCReader.classify("43\r>", Mode.STORED).reply); // header, no payload
        // CAN count says 3 codes, only 2 arrived.
        Attempt cut = DTCReader.classify("43 03 01 23 04 56\r>", Mode.STORED);
        assertEquals(Reply.FAILED, cut.reply);
        assertEquals("the answer was cut short", cut.problem);
    }

    @Test
    public void echoedCommandIsIgnored() {
        assertEquals(Reply.NONE, DTCReader.classify("03\r43 00 00 00 00 00 00\r\r>", Mode.STORED).reply);
    }

    // ---------------------------------------------------------------- decide

    private static Attempt noData() {
        return DTCReader.classify("NO DATA\r>", Mode.STORED);
    }

    private static Attempt codes(String reply) {
        return DTCReader.classify(reply + "\r>", Mode.STORED);
    }

    @Test
    public void noDataConfirmedByPid01IsNone() {
        Result r = DTCReader.decide(Mode.STORED, Arrays.asList(noData(), noData(), noData()), CLEAN, false);
        assertEquals(Outcome.NONE, r.outcome);
        assertTrue(r.answered());
        assertNull(r.problem);
        assertEquals(3, r.attempts);
    }

    @Test
    public void noDataWithoutPid01Fails() {
        Result r = DTCReader.decide(Mode.STORED, Arrays.asList(noData(), noData(), noData()), null, true);
        assertEquals(Outcome.FAILED, r.outcome);
        assertEquals("the truck didn't answer", r.problem);
        assertEquals("couldn't read after 3 tries: the truck didn't answer", r.describeProblem());
    }

    @Test
    public void noDataWithLightOnFails() {
        Result r = DTCReader.decide(Mode.STORED, Arrays.asList(noData(), noData(), noData()),
                new MilStatus(true, 0), false);
        assertEquals(Outcome.FAILED, r.outcome);
    }

    @Test
    public void noDataWhenPid01CountsCodesFails() {
        Result r = DTCReader.decide(Mode.STORED, Arrays.asList(noData(), noData(), noData()),
                new MilStatus(true, 2), false);
        assertEquals(Outcome.FAILED, r.outcome);
        assertEquals("the truck says 2 codes are stored but sent none", r.problem);
    }

    @Test
    public void fewerCodesThanPid01CountIsIncomplete() {
        Result r = DTCReader.decide(Mode.STORED,
                Collections.singletonList(codes("43 01 23 00 00 00 00")), new MilStatus(true, 2), false);
        assertEquals(Outcome.FAILED, r.outcome);
        assertFalse(r.answered());
        assertEquals(Collections.singletonList("P0123"), r.codes); // what came through is kept
        assertEquals("the truck says 2 codes are stored but only 1 came through", r.problem);
        assertTrue(r.describeProblem().startsWith("couldn't read all of them"));
    }

    @Test
    public void codesMatchingPid01CountAreClean() {
        Result r = DTCReader.decide(Mode.STORED,
                Collections.singletonList(codes("43 01 23 04 56 00 00")), new MilStatus(true, 2), false);
        assertEquals(Outcome.CODES, r.outcome);
        assertEquals(Arrays.asList("P0123", "P0456"), r.codes);
    }

    @Test
    public void moreCodesThanPid01CountIsFine() {
        // Ford can store codes that don't count toward the emissions total.
        Result r = DTCReader.decide(Mode.STORED,
                Collections.singletonList(codes("43 12 11 00 00 00 00")), CLEAN, false);
        assertEquals(Outcome.CODES, r.outcome);
    }

    @Test
    public void attemptsThatDisagreeKeepEveryCode() {
        Result late = DTCReader.decide(Mode.STORED,
                Arrays.asList(DTCReader.classify("", Mode.STORED), codes("43 01 23 00 00 00 00")), null, false);
        assertEquals(Outcome.CODES, late.outcome);
        assertEquals(2, late.attempts);

        Result thenNone = DTCReader.decide(Mode.STORED,
                Arrays.asList(codes("43 01 23 00 00 00 00"), codes("43 00 00 00 00 00 00")), null, false);
        assertEquals(Outcome.CODES, thenNone.outcome);
        assertEquals(Collections.singletonList("P0123"), thenNone.codes);

        Result both = DTCReader.decide(Mode.STORED,
                Arrays.asList(codes("43 01 23 00 00 00 00"), codes("43 04 56 00 00 00 00")), null, false);
        assertEquals(Arrays.asList("P0123", "P0456"), both.codes);
    }

    @Test
    public void pendingNoDataNeedsProofThePcmAnswered() {
        List<Attempt> tries = Arrays.asList(
                DTCReader.classify("NO DATA\r>", Mode.PENDING),
                DTCReader.classify("NO DATA\r>", Mode.PENDING),
                DTCReader.classify("NO DATA\r>", Mode.PENDING));
        assertEquals(Outcome.NONE, DTCReader.decide(Mode.PENDING, tries, null, true).outcome);
        assertEquals(Outcome.FAILED, DTCReader.decide(Mode.PENDING, tries, null, false).outcome);
    }

    @Test
    public void refusalIsItsOwnOutcome() {
        Result r = DTCReader.decide(Mode.STORED,
                Collections.singletonList(DTCReader.classify("7F 03 12\r>", Mode.STORED)), CLEAN, false);
        assertEquals(Outcome.REFUSED, r.outcome);
        assertFalse(r.answered());
        assertEquals("the truck's computer turned the request down (code 12: not supported)", r.describeProblem());
    }

    @Test
    public void failuresGiveTheLastReason() {
        Result r = DTCReader.decide(Mode.STORED, Arrays.asList(
                DTCReader.classify("", Mode.STORED),
                DTCReader.classify("?\r>", Mode.STORED),
                DTCReader.classify("BUS INIT: ...ERROR\r>", Mode.STORED)), CLEAN, false);
        assertEquals(Outcome.FAILED, r.outcome);
        assertEquals("the adapter couldn't start talking to the truck", r.problem);
    }

    @Test
    public void noDataMixedWithFailuresIsNotNone() {
        Result r = DTCReader.decide(Mode.STORED,
                Arrays.asList(DTCReader.classify("BUS ERROR\r>", Mode.STORED), noData(), noData()), CLEAN, false);
        assertEquals(Outcome.FAILED, r.outcome);
    }

    // ---------------------------------------------------------------- readAll (retries)

    /** Replays canned adapter replies and counts what was asked. */
    private static final class FakeLink implements DTCReader.Link {
        final Deque<String> stored = new ArrayDeque<>();
        final Deque<String> pending = new ArrayDeque<>();
        final Deque<int[]> pid01 = new LinkedList<>(); // allows null: PID 01 not answering
        final Deque<String> pauses = new ArrayDeque<>();
        int storedAsked, pendingAsked, pid01Asked, paused;

        @Override
        public String requestCodes(Mode mode) {
            if (mode == Mode.STORED) {
                storedAsked++;
                return stored.size() > 1 ? stored.poll() : stored.peek();
            }
            pendingAsked++;
            return pending.size() > 1 ? pending.poll() : pending.peek();
        }

        @Override
        public int[] readPid01() {
            pid01Asked++;
            return pid01.size() > 1 ? pid01.poll() : pid01.peek();
        }

        @Override
        public String pauseBeforeRetry() {
            paused++;
            return pauses.isEmpty() ? null : pauses.poll();
        }
    }

    private static int[] pid01(boolean milOn, int stored) {
        return new int[]{(milOn ? 0x80 : 0) | stored, 0x07, 0x65, 0x00};
    }

    @Test
    public void normalReadIsOneRequestPerMode() throws Exception {
        FakeLink link = new FakeLink();
        link.pid01.add(pid01(false, 0));
        link.stored.add("43 00 00 00 00 00 00\r>");
        link.pending.add("47 00 00 00 00 00 00\r>");
        CodeRead read = DTCReader.readAll(link, true);
        assertEquals(Outcome.NONE, read.stored.outcome);
        assertEquals(Outcome.NONE, read.pending.outcome);
        assertEquals(1, link.storedAsked);
        assertEquals(1, link.pendingAsked);
        assertEquals(0, link.paused);
        assertNull(read.stoppedBecause);
    }

    @Test
    public void codesEndTheReadAtOnce() throws Exception {
        FakeLink link = new FakeLink();
        link.pid01.add(pid01(true, 1));
        link.stored.add("43 12 11 00 00 00 00\r>");
        link.pending.add("47 03 40 00 00 00 00\r>");
        CodeRead read = DTCReader.readAll(link, true);
        assertEquals(Collections.singletonList("P1211"), read.stored.codes);
        assertEquals(Collections.singletonList("P0340"), read.pending.codes);
        assertEquals(1, link.storedAsked);
        assertTrue(read.mil.milOn);
    }

    @Test
    public void cleanTruckThatSaysNoDataIsNoneAfterThreeTries() throws Exception {
        FakeLink link = new FakeLink();
        link.pid01.add(pid01(false, 0));
        link.stored.add("NO DATA\r>");
        link.pending.add("NO DATA\r>");
        CodeRead read = DTCReader.readAll(link, true);
        assertEquals(Outcome.NONE, read.stored.outcome);
        assertEquals(Outcome.NONE, read.pending.outcome);
        assertEquals(3, link.storedAsked);
        assertEquals(3, link.pendingAsked);
        assertEquals(4, link.paused); // between tries, never after the last
    }

    @Test
    public void silentTruckWithoutPid01CouldntBeRead() throws Exception {
        FakeLink link = new FakeLink();
        link.stored.add("NO DATA\r>");
        link.pending.add("NO DATA\r>");
        CodeRead read = DTCReader.readAll(link, false);
        assertEquals(Outcome.FAILED, read.stored.outcome);
        assertEquals(Outcome.FAILED, read.pending.outcome);
        assertEquals(0, link.pid01Asked);
    }

    @Test
    public void pendingNoDataCountsAsNoneAfterARealStoredReply() throws Exception {
        FakeLink link = new FakeLink();
        link.stored.add("43 00 00 00 00 00 00\r>");
        link.pending.add("NO DATA\r>");
        CodeRead read = DTCReader.readAll(link, false);
        assertEquals(Outcome.NONE, read.stored.outcome);
        assertEquals(Outcome.NONE, read.pending.outcome);
    }

    @Test
    public void failedTryIsRetriedThenAnswers() throws Exception {
        FakeLink link = new FakeLink();
        link.pid01.add(pid01(false, 0));
        link.stored.add("");
        link.stored.add("43 01 23 00 00 00 00\r>");
        link.pending.add("47 00 00 00 00 00 00\r>");
        CodeRead read = DTCReader.readAll(link, true);
        assertEquals(Outcome.CODES, read.stored.outcome);
        assertEquals(2, read.stored.attempts);
        assertEquals(2, link.storedAsked);
    }

    @Test
    public void givesUpAfterThreeTries() throws Exception {
        FakeLink link = new FakeLink();
        link.pid01.add(pid01(false, 0));
        link.stored.add("?\r>");
        link.pending.add("47 00 00 00 00 00 00\r>");
        CodeRead read = DTCReader.readAll(link, true);
        assertEquals(Outcome.FAILED, read.stored.outcome);
        assertEquals(DTCReader.MAX_ATTEMPTS, link.storedAsked);
        assertEquals("couldn't read after 3 tries: the adapter didn't understand the request",
                read.stored.describeProblem());
    }

    @Test
    public void refusalIsNotRetried() throws Exception {
        FakeLink link = new FakeLink();
        link.pid01.add(pid01(false, 0));
        link.stored.add("43 00 00 00 00 00 00\r>");
        link.pending.add("7F 07 11\r>");
        CodeRead read = DTCReader.readAll(link, true);
        assertEquals(Outcome.REFUSED, read.pending.outcome);
        assertEquals(1, link.pendingAsked);
    }

    @Test
    public void shortCountIsAskedAgainAndEveryCodeKept() throws Exception {
        FakeLink link = new FakeLink();
        link.pid01.add(pid01(true, 2));
        link.stored.add("43 01 23 00 00 00 00\r>");
        link.stored.add("43 04 56 00 00 00 00\r>");
        link.pending.add("47 00 00 00 00 00 00\r>");
        CodeRead read = DTCReader.readAll(link, true);
        assertEquals(Outcome.CODES, read.stored.outcome);
        assertEquals(Arrays.asList("P0123", "P0456"), read.stored.codes);
        assertEquals(2, link.storedAsked);
    }

    @Test
    public void shortCountOnEveryTryIsIncomplete() throws Exception {
        FakeLink link = new FakeLink();
        link.pid01.add(pid01(true, 3));
        link.stored.add("43 01 23 00 00 00 00\r>");
        link.pending.add("47 00 00 00 00 00 00\r>");
        CodeRead read = DTCReader.readAll(link, true);
        assertEquals(Outcome.FAILED, read.stored.outcome);
        assertEquals(Collections.singletonList("P0123"), read.stored.codes);
        assertEquals(3, link.storedAsked);
    }

    @Test
    public void pid01IsRetriedBeforeGivingUpOnIt() throws Exception {
        FakeLink link = new FakeLink();
        link.pid01.add(null);
        link.pid01.add(pid01(false, 0));
        link.stored.add("NO DATA\r>");
        link.pending.add("NO DATA\r>");
        CodeRead read = DTCReader.readAll(link, true);
        assertNotNull(read.mil);
        assertEquals(2, link.pid01Asked);
        assertEquals(Outcome.NONE, read.stored.outcome);
    }

    @Test
    public void engineStartingStopsTheReadAndSaysWhy() throws Exception {
        FakeLink link = new FakeLink();
        link.pid01.add(pid01(false, 0));
        link.stored.add("NO DATA\r>");
        link.pending.add("NO DATA\r>");
        link.pauses.add(DTCReader.ENGINE_STARTED);
        CodeRead read = DTCReader.readAll(link, true);
        assertEquals(DTCReader.ENGINE_STARTED, read.stoppedBecause);
        assertEquals(Outcome.FAILED, read.stored.outcome);
        assertEquals(DTCReader.ENGINE_STARTED, read.stored.problem);
        assertEquals(Outcome.FAILED, read.pending.outcome);
        assertEquals(DTCReader.ENGINE_STARTED, read.pending.problem);
        assertEquals(1, link.storedAsked);
        assertEquals(0, link.pendingAsked); // nothing more is sent once the starter turns
        assertEquals("couldn't read: the engine started before codes could be read", read.stored.describeProblem());
    }

    @Test
    public void engineStartingDuringPid01RetriesStopsBeforeAnyCodeRequest() throws Exception {
        FakeLink link = new FakeLink();
        link.pid01.add(null);
        link.pauses.add(DTCReader.ENGINE_STARTED);
        CodeRead read = DTCReader.readAll(link, true);
        assertEquals(DTCReader.ENGINE_STARTED, read.stoppedBecause);
        assertEquals(0, link.storedAsked);
        assertEquals(0, read.stored.attempts);
    }

    // ---------------------------------------------------------------- saved scans

    private static Result result(Mode mode, Outcome outcome, String problem, int attempts, String... codes) {
        return new Result(mode, outcome, Arrays.asList(codes), problem, attempts);
    }

    @Test
    public void failedScanSurvivesSaving() {
        DiagnosticCodes.Scan scan = new DiagnosticCodes.Scan(1234L, false,
                result(Mode.STORED, Outcome.FAILED, "the truck didn't answer", 3),
                result(Mode.PENDING, Outcome.FAILED, "the truck didn't answer", 3));
        DiagnosticCodes.Scan back = DiagnosticCodes.Scan.deserialize(scan.serialize());
        assertNotNull(back);
        assertEquals(1234L, back.timestamp);
        assertEquals(Boolean.FALSE, back.milOn);
        assertEquals(Outcome.FAILED, back.stored.outcome);
        assertEquals("the truck didn't answer", back.stored.problem);
        assertEquals(3, back.stored.attempts);
        assertFalse(back.complete());
        assertFalse(back.succeeded());
        assertEquals("couldn't read codes", back.countText());
        assertEquals("Couldn't read trouble codes after 3 tries", back.problemHeadline());
        assertEquals("The truck didn't answer.", back.problemDetail());
    }

    @Test
    public void codesAndOutcomesRoundTrip() {
        DiagnosticCodes.Scan scan = new DiagnosticCodes.Scan(99L, true,
                result(Mode.STORED, Outcome.FAILED, "the truck says 2 codes are stored but only 1 came through", 3, "P1211"),
                result(Mode.PENDING, Outcome.CODES, null, 1, "P1211", "P0340"));
        DiagnosticCodes.Scan back = DiagnosticCodes.Scan.deserialize(scan.serialize());
        assertNotNull(back);
        assertEquals(scan.serialize(), back.serialize());
        assertEquals(Collections.singletonList("P1211"), back.stored.codes);
        assertEquals(Arrays.asList("P1211", "P0340"), back.pending.codes);
        assertEquals(2, back.codes.size()); // P1211 listed once, as stored
        assertEquals("2 codes, read incomplete", back.countText());
        assertEquals("Couldn't read all stored codes after 3 tries", back.problemHeadline());
    }

    @Test
    public void scansFromBeforeClassifyingAreDropped() {
        // Their "no codes" may have been a timeout or NO DATA.
        assertNull(DiagnosticCodes.Scan.deserialize("1;1700000000000;0;"));
        assertNull(DiagnosticCodes.Scan.deserialize("1;1700000000000;1;P1211:S"));
        assertNull(DiagnosticCodes.Scan.deserialize(null));
        assertNull(DiagnosticCodes.Scan.deserialize("2;1;0;;F|3|;N|1|")); // a failure must say why
    }

    @Test
    public void onlyACompleteScanSaysNoCodes() {
        DiagnosticCodes.Scan clean = new DiagnosticCodes.Scan(1L, false,
                result(Mode.STORED, Outcome.NONE, null, 3), result(Mode.PENDING, Outcome.NONE, null, 1));
        assertEquals("no codes", clean.countText());
        assertNull(clean.problemHeadline());
        assertTrue(clean.succeeded());

        DiagnosticCodes.Scan pendingLost = new DiagnosticCodes.Scan(1L, false,
                result(Mode.STORED, Outcome.NONE, null, 1),
                result(Mode.PENDING, Outcome.FAILED, "the adapter didn't answer", 3));
        assertEquals("read incomplete", pendingLost.countText());
        assertEquals("Couldn't read pending codes after 3 tries", pendingLost.problemHeadline());
        assertEquals("Pending codes: the adapter didn't answer.", pendingLost.problemDetail());
        assertFalse(pendingLost.succeeded());
    }

    @Test
    public void clearingNeedsStoredCodesReadButPendingMayBeRefused() {
        DiagnosticCodes.Scan pendingRefused = new DiagnosticCodes.Scan(1L, true,
                result(Mode.STORED, Outcome.CODES, null, 1, "P1211"),
                result(Mode.PENDING, Outcome.REFUSED, "the truck's computer turned the request down (code 11: not supported)", 1));
        assertTrue(pendingRefused.succeeded());

        DiagnosticCodes.Scan storedRefused = new DiagnosticCodes.Scan(1L, true,
                result(Mode.STORED, Outcome.REFUSED, "the truck's computer turned the request down (code 11: not supported)", 1),
                result(Mode.PENDING, Outcome.NONE, null, 1));
        assertFalse(storedRefused.succeeded());
        assertEquals("Couldn't read stored codes", storedRefused.problemHeadline());
    }

    @Test
    public void engineStartedReadHasNoTryCount() {
        DiagnosticCodes.Scan scan = new DiagnosticCodes.Scan(1L, false,
                result(Mode.STORED, Outcome.FAILED, DTCReader.ENGINE_STARTED, 1),
                result(Mode.PENDING, Outcome.FAILED, DTCReader.ENGINE_STARTED, 0));
        assertEquals("Couldn't read trouble codes", scan.problemHeadline());
        assertEquals("The engine started before codes could be read.", scan.problemDetail());
    }

    @Test
    public void parseCodesStillListsCodes() {
        List<String> codes = new ArrayList<>(DTCReader.parseCodes("43 01 23 04 56 00 00\r>", Mode.STORED));
        assertEquals(Arrays.asList("P0123", "P0456"), codes);
        assertTrue(DTCReader.parseCodes("NO DATA\r>", Mode.STORED).isEmpty());
    }
}
