package com.autosentry.app.obd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.autosentry.app.obd.DTCReader.Attempt;
import com.autosentry.app.obd.DTCReader.CodeRead;
import com.autosentry.app.obd.DTCReader.Mode;
import com.autosentry.app.obd.DTCReader.Outcome;
import com.autosentry.app.obd.DTCReader.Reply;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Ford's continuous-memory read (service 13 to the PCM) is not confirmed on a real 7.3L, so
 * these tests pin how every reply shape is treated: codes, no reply, a refusal in either
 * layout, whole frames with headers on, and what the adapter is told before and after.
 */
public class FordMemoryTest {

    /** A whole J1850 frame as the adapter prints it with headers on: header, data, checksum. */
    private static String frame(String headerAndData) {
        String hex = headerAndData.replace(" ", "");
        int crc = 0xFF;
        for (int i = 0; i < hex.length(); i += 2) {
            crc ^= Integer.parseInt(hex.substring(i, i + 2), 16);
            for (int bit = 0; bit < 8; bit++) crc = (crc & 0x80) != 0 ? ((crc << 1) ^ 0x1D) & 0xFF : (crc << 1) & 0xFF;
        }
        return hex + String.format(Locale.US, "%02X", crc ^ 0xFF);
    }

    private static final class Link implements DTCReader.Link {
        final Deque<String> memory = new ArrayDeque<>();
        String patient;
        int asked, patientAsked;

        @Override
        public String requestCodes(Mode mode) {
            if (mode == Mode.STORED) return "43 00 00 00 00 00 00\r>";
            if (mode == Mode.PENDING) return "47 00 00 00 00 00 00\r>";
            asked++;
            return memory.size() > 1 ? memory.poll() : memory.peek();
        }

        @Override
        public int[] readPid01() {
            return new int[]{0, 0, 0, 0};
        }

        @Override
        public String pauseBeforeRetry() {
            return null;
        }

        @Override
        public String requestFordMemoryPatiently() {
            patientAsked++;
            return patient;
        }
    }

    // ---- the checksum that decides whether a line is a whole frame ----

    @Test
    public void checksumMatchesThePublishedJ1850Examples() {
        // SAE J1850 CRC-8 check values (also listed in AUTOSAR's CRC specification).
        assertTrue(DTCReader.checksumOk("0000000059"));
        assertTrue(DTCReader.checksumOk("F2018337"));
        assertTrue(DTCReader.checksumOk("0FAA005579"));
        assertTrue(DTCReader.checksumOk("00FF5511B8"));
        assertTrue(DTCReader.checksumOk("332255AABBCCDDEEFFCB"));
        assertTrue(DTCReader.checksumOk("926B558C"));
        assertTrue(DTCReader.checksumOk("FFFFFFFF74"));
        assertFalse(DTCReader.checksumOk("F2018338"));
    }

    // ---- reply shapes ----

    @Test
    public void codesComeBackThreeToALine() {
        Attempt a = DTCReader.classify("53 12 11 04 70 02 83\r53 03 40 00 00 00 00\r\r>", Mode.FORD_MEMORY);
        assertEquals(Reply.CODES, a.reply);
        assertEquals(Arrays.asList("P1211", "P0470", "P0283", "P0340"), a.codes);
    }

    @Test
    public void paddingOnlyMeansTheTruckAnsweredNone() {
        assertEquals(Reply.NONE, DTCReader.classify("53 00 00 00 00 00 00\r>", Mode.FORD_MEMORY).reply);
    }

    @Test
    public void theSameCodeOnTwoLinesIsListedOnce() {
        Attempt a = DTCReader.classify("53 12 11 00 00 00 00\r53 12 11 00 00 00 00\r>", Mode.FORD_MEMORY);
        assertEquals(Collections.singletonList("P1211"), a.codes);
    }

    @Test
    public void noDataIsNotAnAnswer() {
        Attempt a = DTCReader.classify("NO DATA\r\r>", Mode.FORD_MEMORY);
        assertEquals(Reply.NO_DATA, a.reply);
        assertEquals("NO DATA", a.raw);
    }

    @Test
    public void fordRefusalPutsTheReasonInTheLastByte() {
        // Layout seen in a public 2001 7.3L capture for Mode 22: 7F, service, padding, reason.
        Attempt a = DTCReader.classify("7F 13 00 00 00 12\r>", Mode.FORD_MEMORY);
        assertEquals(Reply.REFUSED, a.reply);
        assertEquals(0x12, a.nrc);
        assertTrue(a.problem, a.problem.contains("not supported"));
    }

    @Test
    public void isoRefusalStillReadsTheThirdByte() {
        Attempt a = DTCReader.classify("7F 13 11\r>", Mode.FORD_MEMORY);
        assertEquals(Reply.REFUSED, a.reply);
        assertEquals(0x11, a.nrc);
    }

    @Test
    public void busyInFordLayoutIsRetriedNotTreatedAsRefused() {
        assertEquals(Reply.FAILED, DTCReader.classify("7F 13 00 00 00 21\r>", Mode.FORD_MEMORY).reply);
    }

    @Test
    public void wholeFramesWithHeadersOnAreRead() {
        String reply = frame("C4 F1 10 53 12 11 03 40 00 00") + "\r" + frame("C4 F1 10 53 04 70 00 00 00 00") + "\r\r>";
        Attempt a = DTCReader.classify(reply, Mode.FORD_MEMORY);
        assertEquals(Reply.CODES, a.reply);
        assertEquals(Arrays.asList("P1211", "P0340", "P0470"), a.codes);
        assertEquals(Arrays.asList("P1211", "P0340", "P0470"), DTCReader.parseCodes(reply, Mode.FORD_MEMORY));
    }

    @Test
    public void wholeFrameRefusalIsRead() {
        Attempt a = DTCReader.classify(frame("C4 F1 10 7F 13 00 00 00 11") + "\r>", Mode.FORD_MEMORY);
        assertEquals(Reply.REFUSED, a.reply);
        assertEquals(0x11, a.nrc);
    }

    @Test
    public void aFrameWithABadChecksumIsNotTrusted() {
        String good = frame("C4 F1 10 53 12 11 00 00 00 00");
        String bad = good.substring(0, good.length() - 2) + (good.endsWith("00") ? "01" : "00");
        assertEquals(Reply.FAILED, DTCReader.classify(bad + "\r>", Mode.FORD_MEMORY).reply);
    }

    @Test
    public void aPlainReplyThatHappensToHoldF1IsLeftAlone() {
        // Stored-code reply whose first code is U3110 (bytes F1 10): not a frame, not stripped.
        Attempt a = DTCReader.classify("43 F1 10 00 00 00 00\r>", Mode.STORED);
        assertEquals(Collections.singletonList("U3110"), a.codes);
    }

    @Test
    public void anOddLengthAnswerIsAFailureNotAGuess() {
        // Unknown layout (e.g. a status byte per code): shown raw, never decoded on a guess.
        Attempt a = DTCReader.classify("53 12 11 01\r>", Mode.FORD_MEMORY);
        assertEquals(Reply.FAILED, a.reply);
        assertEquals("53 12 11 01", a.raw);
    }

    // ---- the read as a whole ----

    @Test
    public void silenceGetsOneSlowerTryThenFailsWithTheRawReply() throws Exception {
        Link link = new Link();
        link.memory.add("NO DATA\r\r>");
        link.patient = "NO DATA\r\r>";
        CodeRead read = DTCReader.readAll(link, true, true);
        assertEquals(Outcome.FAILED, read.memory.outcome);
        assertEquals(DTCReader.MAX_ATTEMPTS, link.asked);
        assertEquals(1, link.patientAsked);
        assertEquals(DTCReader.MAX_ATTEMPTS + 1, read.memory.attempts);
        assertTrue(read.memory.problem, read.memory.problem.contains("the truck's reply: NO DATA"));
        // A silent truck is never "no codes", and it blocks a clear.
        DiagnosticCodes.Scan scan = DiagnosticCodes.Scan.of(1L, read);
        assertFalse(scan.complete());
        assertFalse(scan.succeeded());
        assertEquals("read incomplete", scan.countText());
    }

    @Test
    public void theSlowerTryCanFindTheCodes() throws Exception {
        Link link = new Link();
        link.memory.add("NO DATA\r\r>");
        link.patient = frame("C4 F1 10 53 12 11 00 00 00 00") + "\r\r>";
        CodeRead read = DTCReader.readAll(link, true, true);
        assertEquals(Outcome.CODES, read.memory.outcome);
        assertEquals(Collections.singletonList("P1211"), read.memory.codes);
        assertNull(read.memory.problem);
    }

    @Test
    public void aRefusalEndsTheReadWithoutTheSlowerTry() throws Exception {
        Link link = new Link();
        link.memory.add("7F 13 00 00 00 11\r>");
        CodeRead read = DTCReader.readAll(link, true, true);
        assertEquals(Outcome.REFUSED, read.memory.outcome);
        assertEquals(1, link.asked);
        assertEquals(0, link.patientAsked);
        assertTrue(read.memory.problem, read.memory.problem.contains("7F 13 00 00 00 11"));
        assertTrue(DiagnosticCodes.Scan.of(1L, read).succeeded()); // nothing more to save before a clear
    }

    @Test
    public void anAnswerNeedsNoSlowerTry() throws Exception {
        Link link = new Link();
        link.memory.add("53 00 00 00 00 00 00\r>");
        CodeRead read = DTCReader.readAll(link, true, true);
        assertEquals(Outcome.NONE, read.memory.outcome);
        assertEquals(0, link.patientAsked);
    }

    @Test
    public void aFailedReadWithItsRawReplySurvivesSaving() throws Exception {
        Link link = new Link();
        link.memory.add("7F 13 00 00 00 11\r>");
        DiagnosticCodes.Scan scan = DiagnosticCodes.Scan.of(9L, DTCReader.readAll(link, true, true));
        DiagnosticCodes.Scan back = DiagnosticCodes.Scan.deserialize(scan.serialize());
        assertEquals(scan.memory.problem, back.memory.problem);
        assertEquals(Outcome.REFUSED, back.memory.outcome);
    }

    // ---- what the adapter is told ----

    private static List<String> since(FakeElm elm, int from) {
        return new ArrayList<>(elm.received.subList(from, elm.received.size()));
    }

    @Test
    public void requestGoesToThePcmAndTheObdHeaderComesBack() throws Exception {
        FakeElm elm = new FakeElm();
        ELM327Adapter adapter = new ELM327Adapter(elm.in, elm.out);
        elm.override("13", "53121100000000");
        String reply = adapter.requestTroubleCodes(Mode.FORD_MEMORY);
        assertEquals(Arrays.asList("ATSHC410F1", "13", "ATSH616AF1"), elm.received);
        assertEquals(Collections.singletonList("[C410F1] 13"), elm.busRequests);
        assertEquals(Collections.singletonList("P1211"), DTCReader.classify(reply, Mode.FORD_MEMORY).codes);
    }

    @Test
    public void nothingGoesToTheTruckIfTheAdapterRefusesThePcmHeader() throws Exception {
        FakeElm elm = new FakeElm();
        ELM327Adapter adapter = new ELM327Adapter(elm.in, elm.out);
        elm.override("ATSHC410F1", "?");
        String reply = adapter.requestTroubleCodes(Mode.FORD_MEMORY);
        assertTrue(elm.busRequests.isEmpty());
        assertEquals(Reply.FAILED, DTCReader.classify(reply, Mode.FORD_MEMORY).reply);
        assertEquals("ATSH616AF1", elm.received.get(elm.received.size() - 1));
    }

    @Test
    public void aHeaderThatWontRestoreResetsTheLinkInsteadOfPollingTheWrongAddress() throws Exception {
        FakeElm elm = new FakeElm();
        ELM327Adapter adapter = new ELM327Adapter(elm.in, elm.out);
        elm.override("13", "53000000000000");
        elm.override("ATSH616AF1", "?", "?");
        try {
            adapter.requestTroubleCodes(Mode.FORD_MEMORY);
            fail("carried on with the PCM header still set");
        } catch (IOException expected) {
            assertEquals(2, elm.count("ATSH616AF1")); // asked twice before giving up
        }
    }

    @Test
    public void oneRefusedRestoreIsRetried() throws Exception {
        FakeElm elm = new FakeElm();
        ELM327Adapter adapter = new ELM327Adapter(elm.in, elm.out);
        elm.override("13", "53000000000000");
        elm.override("ATSH616AF1", "?");
        adapter.requestTroubleCodes(Mode.FORD_MEMORY);
        assertEquals(2, elm.count("ATSH616AF1"));
    }

    @Test
    public void theSlowerTryChangesOnlyAdapterSettingsAndPutsThemBack() throws Exception {
        FakeElm elm = new FakeElm();
        ELM327Adapter adapter = new ELM327Adapter(elm.in, elm.out);
        elm.override("13", "NO DATA");
        adapter.requestFordMemoryPatiently();
        assertEquals(Arrays.asList("ATSHC410F1", "ATH1", "ATAT0", "ATSTFF", "13", "ATH0", "ATAT1", "ATST32", "ATSH616AF1"),
                elm.received);
        assertEquals(Collections.singletonList("[C410F1] 13"), elm.busRequests);
    }

    @Test
    public void headersLeftOnAfterTheSlowerTryResetTheLink() throws Exception {
        FakeElm elm = new FakeElm();
        ELM327Adapter adapter = new ELM327Adapter(elm.in, elm.out);
        elm.override("13", "NO DATA");
        elm.override("ATH0", "?");
        try {
            adapter.requestFordMemoryPatiently();
            fail("carried on with headers on");
        } catch (IOException expected) {
            assertEquals("ATSH616AF1", elm.received.get(elm.received.size() - 1)); // still tried to restore
        }
    }

    @Test
    public void mode22ReadsCheckBothHeaderChanges() throws Exception {
        FakeElm elm = new FakeElm();
        ELM327Adapter adapter = new ELM327Adapter(elm.in, elm.out);
        elm.override("ATSHC410F1", "?");
        assertNull(adapter.readFordRaw("1310")[0]);
        assertTrue(elm.busRequests.isEmpty());
        assertTrue(adapter.lastEnhancedReply(), adapter.lastEnhancedReply().contains("refused"));
    }

    // ---- connecting ----

    @Test
    public void aFailedProtocolSearchIsRetriedOnPwmDirectly() throws Exception {
        FakeElm elm = new FakeElm();
        ELM327Adapter adapter = new ELM327Adapter(elm.in, elm.out);
        elm.override("0100", "SEARCHING...\rUNABLE TO CONNECT");
        assertFalse(adapter.readSupportedPids().isEmpty());
        assertEquals(Arrays.asList("0100", "ATTP1", "0100"), since(elm, 0).subList(0, 3));
        assertEquals(0, elm.count("ATSP0"));
    }

    @Test
    public void aTruckThatIsOffGoesBackToSearching() throws Exception {
        FakeElm elm = new FakeElm();
        ELM327Adapter adapter = new ELM327Adapter(elm.in, elm.out);
        elm.override("0100", "SEARCHING...\rUNABLE TO CONNECT", "NO DATA");
        assertTrue(adapter.readSupportedPids().isEmpty());
        assertEquals(Arrays.asList("0100", "ATTP1", "0100", "ATSP0"), elm.received);
    }
}
