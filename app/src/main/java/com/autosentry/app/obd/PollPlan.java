package com.autosentry.app.obd;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides which readings to ask for on each pass, so the bus time goes to readings that
 * answer and change:
 *  - a reading is asked for no more often than its own interval (slow movers every 2 s);
 *  - a reading that misses three times in a row is dropped to one retry every 30 s, and a
 *    reading the truck's computer flatly refuses to one retry every 5 minutes;
 *  - a dropped reading comes straight back the moment it answers.
 * Every reading has a state the screen can show, so one that doesn't answer is labeled, not
 * hidden and never invented.
 *
 * No Android here: time is passed in, and the tracking service does the reading.
 */
public final class PollPlan {
    public enum State {
        /** Not asked yet. */
        WAITING("waiting for the truck"),
        /** The last ask got a value. */
        LIVE("live"),
        /** Asked several times, nothing came back. Retried now and then. */
        NO_ANSWER("truck didn't answer"),
        /** The truck's computer said it doesn't have this reading. */
        REFUSED("the truck's computer says it doesn't have this reading"),
        /** The truck's own list of standard readings leaves this one out, so it isn't asked for. */
        NOT_OFFERED("this truck doesn't report it");

        public final String label;

        State(String label) {
            this.label = label;
        }

        /** True when the reading has been tried and has no value to show. */
        public boolean unavailable() {
            return this == NO_ANSWER || this == REFUSED || this == NOT_OFFERED;
        }
    }

    public static final int MISSES_BEFORE_DROP = 3;
    public static final long NO_ANSWER_RETRY_MS = 30_000L;
    public static final long REFUSED_RETRY_MS = 300_000L;

    private static final class Entry {
        long lastAsked = Long.MIN_VALUE / 2;
        int misses;
        State state = State.WAITING;
    }

    private final Map<Integer, Entry> entries = new HashMap<>();
    private final java.util.Set<Integer> neverDrop = new java.util.HashSet<>();

    /**
     * @param neverDrop readings that are asked for on every pass no matter what: engine speed
     *                  and road speed decide whether the engine is running and a crank is
     *                  starting, so they can't wait out a 30 s retry.
     */
    public PollPlan(Integer... neverDrop) {
        this.neverDrop.addAll(java.util.Arrays.asList(neverDrop));
    }

    private Entry entry(int id) {
        Entry e = entries.get(id);
        if (e == null) {
            e = new Entry();
            entries.put(id, e);
        }
        return e;
    }

    /** The readings among {@code wanted} to ask for now, in the order given. */
    public List<Integer> due(Collection<Integer> wanted, long now) {
        List<Integer> due = new ArrayList<>();
        for (int id : wanted) {
            Entry e = entry(id);
            long interval;
            if (neverDrop.contains(id)) {
                interval = 0;
            } else if (e.state == State.REFUSED) {
                interval = REFUSED_RETRY_MS;
            } else if (e.state == State.NO_ANSWER) {
                interval = NO_ANSWER_RETRY_MS;
            } else {
                PidCatalog.Pid def = PidCatalog.get(id);
                interval = def == null ? 0 : def.pollMs;
            }
            if (now - e.lastAsked >= interval) due.add(id);
        }
        return due;
    }

    /** The reading was asked for and a value came back. */
    public void answered(int id, long now) {
        Entry e = entry(id);
        e.lastAsked = now;
        e.misses = 0;
        e.state = State.LIVE;
    }

    /** The reading was asked for and nothing usable came back. */
    public void missed(int id, long now) {
        Entry e = entry(id);
        e.lastAsked = now;
        e.misses++;
        if (e.state == State.NOT_OFFERED) e.state = State.WAITING; // it's being asked for after all
        if (e.state != State.REFUSED && e.misses >= MISSES_BEFORE_DROP) e.state = State.NO_ANSWER;
    }

    /** The truck's computer answered "not supported" for this reading. */
    public void refused(int id, long now) {
        Entry e = entry(id);
        e.lastAsked = now;
        e.misses++;
        e.state = State.REFUSED;
    }

    /** The reading isn't in the truck's list of supported standard readings. */
    public void notOffered(int id) {
        entry(id).state = State.NOT_OFFERED;
    }

    public State state(int id) {
        Entry e = entries.get(id);
        return e == null ? State.WAITING : e.state;
    }

    /** True while the last value is still worth showing: live, or missed fewer than three times. */
    public boolean hasValue(int id) {
        Entry e = entries.get(id);
        return e != null && (e.state == State.LIVE && e.misses == 0);
    }

    /** New connection to the truck: everything gets a fresh chance. */
    public void reset() {
        entries.clear();
    }
}
