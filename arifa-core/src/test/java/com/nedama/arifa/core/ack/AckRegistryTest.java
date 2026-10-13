package com.nedama.arifa.core.ack;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AckRegistryTest {

    private static final String A = "+254712000001";
    private static final String B = "+254712000002";
    private static final Instant T0 = Instant.parse("2026-10-05T10:00:00Z");

    /** A clock the test can move. */
    private static final class TestClock extends Clock {
        Instant now = T0;

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

    private AckRegistry<String> registry(Duration validFor, int maxPending) {
        return new AckRegistry<>(clock, validFor, maxPending, new Random(42));
    }

    @Test
    void registersAFourDigitCodeAndClaimsItOnce() {
        AckRegistry<String> registry = registry(Duration.ofMinutes(60), 10);

        String code = registry.register(List.of(A), "user-1");

        assertEquals(4, code.length());
        assertTrue(code.matches("\\d{4}"));
        assertEquals(1, registry.pendingCount());

        Optional<AckRegistry.Claim<String>> claim = registry.claim(A, code, T0.plusSeconds(20));
        assertTrue(claim.isPresent());
        assertEquals("user-1", claim.get().attachment());
        assertEquals(A, claim.get().number());
        assertEquals(code, claim.get().code());
        assertEquals(T0, claim.get().createdAt());

        // single use
        assertFalse(registry.claim(A, code, T0.plusSeconds(25)).isPresent());
        assertEquals(0, registry.pendingCount());
    }

    @Test
    void rejectsTheWrongCode() {
        AckRegistry<String> registry = registry(Duration.ofMinutes(60), 10);
        String code = registry.register(List.of(A), "u");
        String wrong = code.equals("0000") ? "1111" : "0000";

        assertFalse(registry.claim(A, wrong, T0.plusSeconds(5)).isPresent());

        // a wrong guess must not use up the real code
        assertTrue(registry.claim(A, code, T0.plusSeconds(10)).isPresent());
    }

    @Test
    void rejectsAReplyFromAnotherNumberAndKeepsTheCode() {
        AckRegistry<String> registry = registry(Duration.ofMinutes(60), 10);
        String code = registry.register(List.of(A), "u");

        assertFalse(registry.claim(B, code, T0.plusSeconds(5)).isPresent());
        assertFalse(registry.claim(null, code, T0.plusSeconds(5)).isPresent());

        assertTrue(registry.claim(A, code, T0.plusSeconds(10)).isPresent());
    }

    @Test
    void anyOfSeveralRegisteredNumbersCanReply() {
        AckRegistry<String> registry = registry(Duration.ofMinutes(60), 10);
        String code = registry.register(List.of(A, B), "u");

        Optional<AckRegistry.Claim<String>> claim = registry.claim(B, code, null);

        assertTrue(claim.isPresent());
        assertEquals(B, claim.get().number());
    }

    @Test
    void codesExpire() {
        AckRegistry<String> registry = registry(Duration.ofMinutes(30), 10);
        String code = registry.register(List.of(A), "u");

        clock.advance(Duration.ofMinutes(29));
        assertEquals(1, registry.pendingCount());

        clock.advance(Duration.ofMinutes(2));
        assertEquals(0, registry.pendingCount());
        assertFalse(registry.claim(A, code, null).isPresent());
    }

    @Test
    void aReplySentBeforeTheSmsCannotAcknowledgeIt() {
        AckRegistry<String> registry = registry(Duration.ofMinutes(60), 10);
        clock.advance(Duration.ofHours(2));
        String code = registry.register(List.of(A), "new alarm");

        // an old message from earlier today carrying the same digits
        Instant old = clock.instant().minus(Duration.ofHours(1));
        assertFalse(registry.claim(A, code, old).isPresent());

        // a little clock difference between provider and gateway is tolerated
        Instant slightlyEarly = clock.instant().minusSeconds(60);
        assertTrue(registry.claim(A, code, slightlyEarly).isPresent());
    }

    @Test
    void anUnknownReceiveTimeIsAccepted() {
        AckRegistry<String> registry = registry(Duration.ofMinutes(60), 10);
        String code = registry.register(List.of(A), "u");
        assertTrue(registry.claim(A, code, null).isPresent());
    }

    @Test
    void cancelForgetsACode() {
        AckRegistry<String> registry = registry(Duration.ofMinutes(60), 10);
        String code = registry.register(List.of(A), "u");

        registry.cancel(code);

        assertEquals(0, registry.pendingCount());
        assertFalse(registry.claim(A, code, null).isPresent());
        registry.cancel("9999"); // unknown code: no error
    }

    @Test
    void liveCodesAreUnique() {
        AckRegistry<String> registry = registry(Duration.ofMinutes(60), 500);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 300; i++) {
            assertTrue(seen.add(registry.register(List.of(A), "u" + i)), "duplicate code at " + i);
        }
        assertEquals(300, registry.pendingCount());
    }

    @Test
    void whenFullTheOldestCodeIsDropped() {
        AckRegistry<String> registry = registry(Duration.ofMinutes(60), 2);
        String first = registry.register(List.of(A), "first");
        String second = registry.register(List.of(A), "second");
        String third = registry.register(List.of(A), "third");

        assertEquals(2, registry.pendingCount());
        assertFalse(registry.claim(A, first, null).isPresent());
        assertTrue(registry.claim(A, second, null).isPresent());
        assertTrue(registry.claim(A, third, null).isPresent());
    }

    @Test
    void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> registry(Duration.ZERO, 10));
        assertThrows(IllegalArgumentException.class, () -> registry(Duration.ofMinutes(-1), 10));
        assertThrows(IllegalArgumentException.class, () -> registry(Duration.ofMinutes(1), 0));
        AckRegistry<String> registry = registry(Duration.ofMinutes(1), 10);
        assertThrows(IllegalArgumentException.class, () -> registry.register(List.of(), "u"));
        assertThrows(IllegalArgumentException.class, () -> registry.register(null, "u"));
        assertNotNull(new AckRegistry<String>(Duration.ofMinutes(5)));
    }
}
