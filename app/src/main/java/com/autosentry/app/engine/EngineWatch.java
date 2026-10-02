package com.autosentry.app.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Tells cranking, running, a stall and a normal shutoff apart from the RPM
 * reading alone. With the key on the PCM keeps answering (RPM 0 when the
 * engine isn't turning); with the key off it stops answering. So after
 * running, RPM 0 is a stall and no answer is the key being turned off.
 */
public final class EngineWatch {
    public enum State { KEY_OFF, KEY_ON, CRANKING, RUNNING }

    public enum Event { CRANK_STARTED, STARTED, NO_START, STALLED }

    // Cranking stays well under this and idle sits above it, so crossing it means the engine started.
    public static final double RUNNING_RPM = 500;
    // RPM 0 must hold this long with the PCM still answering before it counts as a stall,
    // so the moment between the engine stopping and the PCM going quiet at key-off isn't one.
    static final long STALL_CONFIRM_MS = 3_000L;

    private State state = State.KEY_OFF;
    private long pendingStallAt = 0;
    private long lastStallAt = 0;

    public State state() {
        return state;
    }

    /** When RPM last fell to 0 from running, for the stall report. */
    public long lastStallAt() {
        return lastStallAt;
    }

    /** {@code rpm} is null when the PCM didn't answer. */
    public List<Event> update(long now, Double rpm) {
        List<Event> events = new ArrayList<>(2);
        if (pendingStallAt > 0) {
            if (rpm == null) {
                pendingStallAt = 0; // PCM went quiet: the key was turned off
            } else if (rpm > 0 || now - pendingStallAt >= STALL_CONFIRM_MS) {
                lastStallAt = pendingStallAt;
                pendingStallAt = 0;
                events.add(Event.STALLED);
            }
        }

        State next = classify(rpm);
        switch (state) {
            case RUNNING:
                if (next == State.CRANKING) next = State.RUNNING; // slowing down on the way to a stop
                if (next == State.KEY_ON) pendingStallAt = now;
                break;
            case CRANKING:
                if (next == State.RUNNING) {
                    events.add(Event.STARTED);
                } else if (next != State.CRANKING) {
                    events.add(Event.NO_START);
                }
                break;
            default:
                if (next == State.CRANKING) events.add(Event.CRANK_STARTED);
                break;
        }
        state = next;
        return events;
    }

    private static State classify(Double rpm) {
        if (rpm == null) return State.KEY_OFF;
        if (rpm <= 0) return State.KEY_ON;
        return rpm < RUNNING_RPM ? State.CRANKING : State.RUNNING;
    }
}
