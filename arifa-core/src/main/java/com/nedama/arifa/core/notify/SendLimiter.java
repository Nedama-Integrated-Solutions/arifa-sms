package com.nedama.arifa.core.notify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * A rolling cap on how many SMS one profile may send, so a runaway alarm cannot empty the balance.
 *
 * <p>The unit is one SMS segment to one recipient, which is what the provider bills. A message of 2 segments to 3
 * people is 6 units. The window rolls: "100 per hour" means no more than 100 in any 60 minutes, not 100 from the
 * top of each hour.</p>
 *
 * <p>Usage: {@link #tryReserve} before sending, then {@link Reservation#settle} with what was really accepted, so
 * a send that was refused does not use up the allowance. The cap is checked and reserved in one step, so two sends
 * at the same moment cannot both slip under it. Safe to use from several threads.</p>
 */
public final class SendLimiter {

    /** A reserved share of the allowance. */
    public final class Reservation {
        private int units;

        private Reservation(int units) {
            this.units = units;
        }

        /**
         * Reduces the reservation to what was really sent. Anything above the reserved amount is ignored.
         * Calling it again changes the figure again, which is harmless.
         */
        public void settle(int actualUnits) {
            synchronized (SendLimiter.this) {
                units = Math.max(0, Math.min(actualUnits, units));
            }
        }
    }

    private static final class Entry {
        final Instant time;
        final Reservation reservation;

        Entry(Instant time, Reservation reservation) {
            this.time = time;
            this.reservation = reservation;
        }
    }

    private final Clock clock;
    private final int maxUnits;
    private final Duration window;
    private final Deque<Entry> entries = new ArrayDeque<>();

    /**
     * @param maxUnits the most units allowed in any window; must be positive (use no limiter at all for "no limit")
     * @param window   how far back to look
     */
    public SendLimiter(Clock clock, int maxUnits, Duration window) {
        if (maxUnits < 1) {
            throw new IllegalArgumentException("maxUnits must be at least 1");
        }
        if (window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("window must be positive");
        }
        this.clock = clock;
        this.maxUnits = maxUnits;
        this.window = window;
    }

    /** One hour rolling window on the system clock. */
    public static SendLimiter perHour(int maxUnits) {
        return new SendLimiter(Clock.systemUTC(), maxUnits, Duration.ofHours(1));
    }

    /**
     * Reserves room for this many units, or says there is none.
     *
     * @return the reservation, or empty if the cap would be exceeded. Nothing is reserved in that case.
     */
    public synchronized Optional<Reservation> tryReserve(int units) {
        purge();
        Reservation reservation = new Reservation(Math.max(0, units));
        if (units > 0) {
            if (used() + units > maxUnits) {
                return Optional.empty();
            }
            entries.addLast(new Entry(clock.instant(), reservation));
        }
        return Optional.of(reservation);
    }

    /** How many units have been used in the current window. */
    public synchronized int used() {
        purge();
        int total = 0;
        for (Entry entry : entries) {
            total += entry.reservation.units;
        }
        return total;
    }

    /** The cap. */
    public int limit() {
        return maxUnits;
    }

    private void purge() {
        Instant cutoff = clock.instant().minus(window);
        while (!entries.isEmpty() && !entries.peekFirst().time.isAfter(cutoff)) {
            entries.removeFirst();
        }
    }
}
