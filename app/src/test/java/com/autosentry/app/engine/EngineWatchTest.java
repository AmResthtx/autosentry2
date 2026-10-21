package com.autosentry.app.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.autosentry.app.engine.EngineWatch.Event;
import com.autosentry.app.engine.EngineWatch.State;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class EngineWatchTest {
    private final EngineWatch watch = new EngineWatch();

    private List<Event> at(long ms, Double rpm) {
        return watch.update(ms, rpm);
    }

    @Test
    public void crankThenStart() {
        assertTrue(at(0, 0.0).isEmpty());
        assertEquals(State.KEY_ON, watch.state());
        assertEquals(Arrays.asList(Event.CRANK_STARTED), at(500, 150.0));
        assertTrue(at(1000, 200.0).isEmpty());
        assertEquals(Arrays.asList(Event.STARTED), at(1500, 700.0));
        assertEquals(State.RUNNING, watch.state());
    }

    @Test
    public void crankThenNoStart() {
        at(0, 0.0);
        at(500, 150.0);
        assertEquals(Arrays.asList(Event.NO_START), at(5000, 0.0));
        assertEquals(State.KEY_ON, watch.state());
    }

    @Test
    public void crankEndsWhenKeyTurnedOff() {
        at(0, 0.0);
        at(500, 150.0);
        assertEquals(Arrays.asList(Event.NO_START), at(1000, null));
    }

    @Test
    public void rpmZeroWithKeyOnIsAStall() {
        at(0, 700.0);
        assertTrue(at(1000, 0.0).isEmpty());
        assertTrue(at(3000, 0.0).isEmpty());
        assertEquals(Arrays.asList(Event.STALLED), at(4000, 0.0));
        assertEquals(1000, watch.lastStallAt());
        assertTrue(at(8000, 0.0).isEmpty());
    }

    @Test
    public void keyOffIsNotAStall() {
        at(0, 700.0);
        at(1000, 0.0);
        assertTrue(at(1500, null).isEmpty());
        assertTrue(at(9000, 0.0).isEmpty());
        assertEquals(State.KEY_ON, watch.state());
    }

    @Test
    public void runningStraightToNoAnswerIsAShutoff() {
        at(0, 700.0);
        assertTrue(at(500, null).isEmpty());
        assertEquals(State.KEY_OFF, watch.state());
    }

    @Test
    public void crankingRightAfterAStallReportsBoth() {
        at(0, 700.0);
        at(1000, 0.0);
        assertEquals(Arrays.asList(Event.STALLED, Event.CRANK_STARTED), at(2000, 180.0));
    }

    @Test
    public void slowingOnTheWayToAStallIsNotCranking() {
        at(0, 700.0);
        assertTrue(at(500, 250.0).isEmpty());
        assertEquals(State.RUNNING, watch.state());
        at(1000, 0.0);
        assertEquals(Arrays.asList(Event.STALLED), at(4000, 0.0));
    }

    @Test
    public void connectingToARunningEngineIsQuiet() {
        assertTrue(at(0, 680.0).isEmpty());
        assertEquals(State.RUNNING, watch.state());
        assertTrue(at(500, 720.0).isEmpty());
        assertTrue(at(1000, 650.0).isEmpty());
    }
}
