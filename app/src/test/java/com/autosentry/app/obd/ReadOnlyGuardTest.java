package com.autosentry.app.obd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ReadOnlyGuardTest {

    /** A clock that never waits; it just moves time forward and remembers each sleep. */
    static final class FakeClock implements ReadOnlyGuard.Clock {
        long now = 1_000_000;
        final List<Long> sleeps = new ArrayList<>();

        @Override
        public long now() {
            return now;
        }

        @Override
        public void sleep(long ms) {
            sleeps.add(ms);
            now += ms;
        }
    }

    private static ReadOnlyGuard active(FakeClock clock) {
        return new ReadOnlyGuard(false, 100, 250, clock);
    }

    /** Checks a command and, as the adapter does once the write succeeds, records it as sent. */
    private static void send(ReadOnlyGuard guard, String command) throws IOException {
        guard.beforeSend(command);
        guard.sent(command);
    }

    private static void assertBlocked(ReadOnlyGuard guard, String command) {
        try {
            guard.beforeSend(command);
            fail("should have refused " + command);
        } catch (ReadOnlyGuard.Blocked expected) {
            // refused before anything was written
        } catch (IOException e) {
            fail("wrong exception for " + command + ": " + e);
        }
    }

    @Test
    public void readsAreAllowed() throws IOException {
        ReadOnlyGuard guard = active(new FakeClock());
        for (String cmd : new String[]{"0100", "010C", "020200", "03", "07", "0900", "0902", "0A", "221446", "1902FF"}) {
            send(guard, cmd);
        }
        assertEquals(10, guard.busFrames().size());
        assertTrue(guard.rejected().isEmpty());
    }

    @Test
    public void everyWriteOrControlServiceIsRefused() {
        ReadOnlyGuard guard = active(new FakeClock());
        String[] blocked = {"04", "14FFFFFF", "1003", "1101", "2701", "2EF19000", "2F123403", "31010203",
                "3400", "3500", "3601", "37", "3E00", "3E80", "8502", "0801", "2800", "3D00"};
        for (String cmd : blocked) assertBlocked(guard, cmd);
        assertTrue(guard.busFrames().isEmpty());
        assertEquals(blocked.length, guard.rejected().size());
    }

    @Test
    public void adapterWritesAreRefused() {
        ReadOnlyGuard guard = active(new FakeClock());
        for (String cmd : new String[]{"ATSP0", "ATSP6", "ATPP0CSV0A", "ATSD00", "ATM1", "ATBRD23", "ATCSM0",
                "ATTP0", "ATTPA6", "ATWM8110F13E", "STSBR115200", "STPBR2000000", "ATZ", "ATD", "ATFCSH7E0"}) {
            assertBlocked(guard, cmd);
        }
    }

    @Test
    public void readOnlyAdapterCommandsPass() throws IOException {
        ReadOnlyGuard guard = active(new FakeClock());
        for (String cmd : new String[]{"ATI", "AT@1", "ATRV", "ATDP", "ATDPN", "ATH1", "ATH0", "ATSHC410F1",
                "ATSH7E0", "ATIFR0", "ATCSM1", "ATMA", "STI", "STDI", "AT E0"}) {
            send(guard, cmd);
        }
        assertTrue(guard.busFrames().isEmpty());
        assertTrue(guard.rejected().isEmpty());
    }

    @Test
    public void emptyOrSmuggledCommandsAreRefused() {
        ReadOnlyGuard guard = active(new FakeClock());
        assertBlocked(guard, "");
        assertBlocked(guard, "0100\r04");
        assertBlocked(guard, "0100\n04");
        assertBlocked(guard, null);
        assertBlocked(guard, "010"); // odd hex
    }

    @Test
    public void listenOnlySendsNothingToTheTruck() throws IOException {
        ReadOnlyGuard guard = new ReadOnlyGuard(true, 100, 250, new FakeClock());
        assertTrue(guard.isListenOnly());
        assertBlocked(guard, "0100");
        assertBlocked(guard, "0902");
        send(guard, "ATI");
        send(guard, "ATMA");
        assertTrue(guard.busFrames().isEmpty());
    }

    @Test
    public void framesAreLoggedWithTheirHeader() throws IOException {
        ReadOnlyGuard guard = active(new FakeClock());
        send(guard, "0100");
        send(guard, "ATSHC410F1");
        send(guard, "221310");
        assertEquals("[default] 0100", guard.busFrames().get(0));
        assertEquals("[C410F1] 221310", guard.busFrames().get(1));
        assertEquals(Integer.valueOf(1), guard.adapterCommands().get("ATSHC410F1"));
    }

    @Test
    public void requestsArePaced() throws IOException {
        FakeClock clock = new FakeClock();
        ReadOnlyGuard guard = active(clock);
        send(guard, "0100");
        clock.now += 30;
        send(guard, "010C");
        assertEquals(List.of(70L), clock.sleeps);
    }

    @Test
    public void busyOrPendingModulesGetARetryAfterBackingOff() throws IOException {
        FakeClock clock = new FakeClock();
        ReadOnlyGuard guard = active(clock);
        send(guard, "221446");
        assertTrue(guard.afterReply("221446", "7F2278\r\r>", 1));
        assertTrue(guard.afterReply("221446", "7F2221\r\r>", 2));
        assertTrue(clock.sleeps.contains(250L));
        assertFalse(guard.afterReply("221446", "621446037D\r\r>", 3));
        // Gives up retrying after the last attempt and says so.
        assertFalse(guard.afterReply("221446", "7F2278\r\r>", ReadOnlyGuard.MAX_ATTEMPTS));
        assertFalse(guard.problems().isEmpty());
        // Backing off also slows every later request.
        clock.sleeps.clear();
        send(guard, "010C");
        send(guard, "010D");
        assertTrue(clock.sleeps.get(clock.sleeps.size() - 1) >= 250);
    }

    @Test
    public void ordinaryRefusalsAndNoDataAreFine() throws IOException {
        ReadOnlyGuard guard = active(new FakeClock());
        assertFalse(guard.afterReply("221100", "7F2231\r\r>", 1));
        assertFalse(guard.afterReply("0A", "NO DATA\r\r>", 1));
        assertFalse(guard.afterReply("0100", "SEARCHING...\r410008182000\r\r>", 1));
    }

    @Test
    public void unexpectedReplyStopsTheScan() throws IOException {
        ReadOnlyGuard guard = active(new FakeClock());
        try {
            guard.afterReply("010D", "5003\r\r>", 1);
            fail("a reply from another service should stop the scan");
        } catch (ReadOnlyGuard.Abort expected) {
            assertTrue(expected.getMessage().contains("010D"));
        }
    }

    @Test
    public void threeBusErrorsStopTheScan() throws IOException {
        FakeClock clock = new FakeClock();
        ReadOnlyGuard guard = active(clock);
        assertTrue(guard.afterReply("010C", "BUS ERROR\r\r>", 1));
        assertTrue(guard.afterReply("010C", "<DATA ERROR\r\r>", 2));
        try {
            guard.afterReply("010C", "BUS BUSY\r\r>", 3);
            fail("third bus error should stop the scan");
        } catch (ReadOnlyGuard.Abort expected) {
            assertEquals(2, clock.sleeps.size());
        }
    }

    @Test
    public void headersOnRepliesAreCheckedPastTheHeader() throws IOException {
        ReadOnlyGuard guard = active(new FakeClock());
        guard.afterReply("ATDPN", "A1\r\r>", 1);
        send(guard, "ATH1");
        assertFalse(guard.afterReply("0100", "486B10410008182000AA\r\r>", 1));
        try {
            guard.afterReply("0100", "486B10500300AA\r\r>", 1);
            fail("J1850 reply from the wrong service should stop the scan");
        } catch (ReadOnlyGuard.Abort expected) {
            // stopped
        }
    }

    @Test
    public void canConsecutiveFramesAreNotMistakenForAnswers() throws IOException {
        ReadOnlyGuard guard = active(new FakeClock());
        guard.afterReply("ATDPN", "A6\r\r>", 1);
        send(guard, "ATH1");
        assertFalse(guard.afterReply("0902",
                "7E8101449020131464D\r7E82143553131315830\r7E8224433303030303030\r\r>", 1));
    }

    @Test
    public void nothingIsRecordedAsSentUntilTheWriteGoesThrough() throws IOException {
        ReadOnlyGuard guard = active(new FakeClock());
        guard.beforeSend("010C");
        assertTrue(guard.busFrames().isEmpty());
        guard.writeFailed("010C");
        assertTrue(guard.busFrames().isEmpty());
        assertEquals("[default] 010C", guard.unconfirmed().get(0));
        guard.beforeSend("ATSHC410F1");
        assertTrue(guard.adapterCommands().isEmpty());
    }

    @Test
    public void aReplyForADifferentPidStopsTheScan() throws IOException {
        String[][] mismatches = {
                {"221446", "6213102328\r\r>"},
                {"010D", "410C1AF8\r\r>"},
                {"020200", "4202010470\r\r>"},
                {"0904", "4902014D4F\r\r>"},
        };
        for (String[] m : mismatches) {
            ReadOnlyGuard guard = active(new FakeClock());
            try {
                guard.afterReply(m[0], m[1], 1);
                fail("a reply to another PID should stop the scan: " + m[0]);
            } catch (ReadOnlyGuard.Abort expected) {
                // stopped
            }
        }
        ReadOnlyGuard guard = active(new FakeClock());
        assertFalse(guard.afterReply("221446", "621446037D\r\r>", 1));
        assertFalse(guard.afterReply("03", "43047000000000\r\r>", 1));
    }

    @Test
    public void bothErrorsAndAbortsKeepTheVinOutOfTheReport() throws IOException {
        ReadOnlyGuard guard = active(new FakeClock());
        String vinHex = "31464D";
        guard.afterReply("0902", "49020131464D4E57\rBUS ERROR\r\r>", 1);
        for (String p : guard.problems()) assertFalse(p, p.contains(vinHex));
        assertTrue(guard.problems().get(0).contains("BUS ERROR"));
        try {
            guard.afterReply("0902", "4A0131464D4E57\r\r>", 2);
            fail("a reply from another service should stop the scan");
        } catch (ReadOnlyGuard.Abort e) {
            assertFalse(e.getMessage(), e.getMessage().contains(vinHex));
        }
    }
}
