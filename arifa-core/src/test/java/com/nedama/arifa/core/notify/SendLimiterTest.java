package com.nedama.arifa.core.notify;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SendLimiterTest {

    private static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-05T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final TestClock clock = new TestClock();

    private SendLimiter limiter(int max) {
        return new SendLimiter(clock, max, Duration.ofHours(1));
    }

    @Test
    void allowsUpToTheCapAndRefusesBeyondIt() {
        SendLimiter limiter = limiter(10);

        assertTrue(limiter.tryReserve(6).isPresent());
        assertTrue(limiter.tryReserve(4).isPresent());
        assertEquals(10, limiter.used());
        assertFalse(limiter.tryReserve(1).isPresent());
        assertEquals(10, limiter.used());
    }

    @Test
    void aRefusedReservationReservesNothing() {
        SendLimiter limiter = limiter(10);
        limiter.tryReserve(8);

        assertFalse(limiter.tryReserve(5).isPresent());
        assertEquals(8, limiter.used());
        assertTrue(limiter.tryReserve(2).isPresent());
    }

    @Test
    void theWindowRollsSoOldSendsStopCounting() {
        SendLimiter limiter = limiter(10);
        limiter.tryReserve(10);

        clock.advance(Duration.ofMinutes(59));
        assertFalse(limiter.tryReserve(1).isPresent());

        clock.advance(Duration.ofMinutes(2));
        assertEquals(0, limiter.used());
        assertTrue(limiter.tryReserve(10).isPresent());
    }

    @Test
    void rollingMeansNoMoreThanTheCapInAnySixtyMinutes() {
        SendLimiter limiter = limiter(10);
        limiter.tryReserve(5);              // 10:00
        clock.advance(Duration.ofMinutes(30));
        limiter.tryReserve(5);              // 10:30
        clock.advance(Duration.ofMinutes(31)); // 11:01: the 10:00 batch is gone, the 10:30 one is not

        assertEquals(5, limiter.used());
        assertTrue(limiter.tryReserve(5).isPresent());
        assertFalse(limiter.tryReserve(1).isPresent());
    }

    @Test
    void settlingGivesBackWhatWasNotSent() {
        SendLimiter limiter = limiter(10);
        SendLimiter.Reservation reservation = limiter.tryReserve(8).orElseThrow();

        reservation.settle(0);

        assertEquals(0, limiter.used());
        assertTrue(limiter.tryReserve(10).isPresent());
    }

    @Test
    void settlingCanOnlyLowerTheReservation() {
        SendLimiter limiter = limiter(10);
        SendLimiter.Reservation reservation = limiter.tryReserve(4).orElseThrow();

        reservation.settle(9);
        assertEquals(4, limiter.used());

        reservation.settle(-3);
        assertEquals(0, limiter.used());
    }

    @Test
    void zeroUnitsAreAlwaysAllowed() {
        SendLimiter limiter = limiter(1);
        limiter.tryReserve(1);

        Optional<SendLimiter.Reservation> none = limiter.tryReserve(0);

        assertTrue(none.isPresent());
        assertEquals(1, limiter.used());
    }

    @Test
    void aSingleRequestLargerThanTheCapIsRefused() {
        assertFalse(limiter(5).tryReserve(6).isPresent());
    }

    @Test
    void reportsItsLimit() {
        assertEquals(100, SendLimiter.perHour(100).limit());
        assertNotNull(SendLimiter.perHour(1));
    }

    @Test
    void rejectsBadSettings() {
        assertThrows(IllegalArgumentException.class, () -> limiter(0));
        assertThrows(IllegalArgumentException.class, () -> new SendLimiter(clock, 5, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new SendLimiter(clock, 5, Duration.ofMinutes(-1)));
    }

    @Test
    void manyThreadsCannotSlipPastTheCap() throws Exception {
        SendLimiter limiter = SendLimiter.perHour(50);
        int threads = 20;
        java.util.concurrent.atomic.AtomicInteger granted = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<Thread> pool = new java.util.ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < 10; i++) {
                    if (limiter.tryReserve(1).isPresent()) {
                        granted.incrementAndGet();
                    }
                }
            });
            pool.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread thread : pool) {
            thread.join();
        }

        assertEquals(50, granted.get());
        assertEquals(50, limiter.used());
    }
}
