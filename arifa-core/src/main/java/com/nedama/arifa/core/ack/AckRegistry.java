package com.nedama.arifa.core.ack;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Remembers which acknowledgement codes are waiting for a reply.
 *
 * <p>When Arifa sends an alarm SMS it registers a random 4 digit code here, together with the
 * phone numbers the SMS went to and an opaque attachment (for the Ignition adapter: which user
 * and which alarm events). When a reply arrives, {@link #claim} checks all of these at once:</p>
 * <ul>
 *   <li>the code exists and has not expired,</li>
 *   <li>the reply came from one of the numbers the SMS was sent to,</li>
 *   <li>the reply was sent after the SMS (so an old message cannot acknowledge a new alarm).</li>
 * </ul>
 * <p>A code works once. Everything lives in memory only, so pending codes are lost when the
 * gateway restarts or fails over. Replies to older messages are then simply ignored.</p>
 *
 * <p>Safe to use from several threads.</p>
 *
 * @param <A> what the caller wants to get back when a code is claimed
 */
public final class AckRegistry<A> {

    /** Default cap on waiting codes. The oldest are dropped first when it is exceeded. */
    public static final int DEFAULT_MAX_PENDING = 1000;

    /** How much earlier than the SMS a reply may be stamped, to tolerate clock differences. */
    static final Duration CLOCK_SKEW = Duration.ofMinutes(2);

    /**
     * A successfully matched reply.
     *
     * @param code       the code that was claimed
     * @param number     the number the reply came from, in +E.164 format
     * @param attachment what was registered with the code
     * @param createdAt  when the code was registered
     */
    public record Claim<A>(String code, String number, A attachment, Instant createdAt) {
    }

    private static final class Entry<A> {
        final Set<String> numbers;
        final A attachment;
        final Instant createdAt;
        final Instant expiresAt;

        Entry(Set<String> numbers, A attachment, Instant createdAt, Instant expiresAt) {
            this.numbers = numbers;
            this.attachment = attachment;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
        }
    }

    private final Clock clock;
    private final Duration validFor;
    private final int maxPending;
    private final Random random;
    private final Map<String, Entry<A>> byCode = new LinkedHashMap<>();

    /** Uses the system clock, a secure random generator and the default cap. */
    public AckRegistry(Duration validFor) {
        this(Clock.systemUTC(), validFor, DEFAULT_MAX_PENDING, new SecureRandom());
    }

    public AckRegistry(Clock clock, Duration validFor, int maxPending, Random random) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
        this.validFor = Objects.requireNonNull(validFor, "validFor");
        if (validFor.isNegative() || validFor.isZero()) {
            throw new IllegalArgumentException("validFor must be positive");
        }
        if (maxPending < 1) {
            throw new IllegalArgumentException("maxPending must be at least 1");
        }
        this.maxPending = maxPending;
    }

    /**
     * Registers a new code for these numbers and returns it.
     *
     * @param numbers    numbers in +E.164 format that may reply with this code
     * @param attachment returned when the code is claimed
     * @throws IllegalStateException if no free code could be found (practically never)
     */
    public synchronized String register(Collection<String> numbers, A attachment) {
        if (numbers == null || numbers.isEmpty()) {
            throw new IllegalArgumentException("At least one number is required");
        }
        purgeExpired();
        while (byCode.size() >= maxPending) {
            Iterator<String> oldest = byCode.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        String code = null;
        for (int attempt = 0; attempt < 200; attempt++) {
            String candidate = String.format(Locale.ROOT, "%0" + ReplyParser.CODE_DIGITS + "d",
                    random.nextInt((int) Math.pow(10, ReplyParser.CODE_DIGITS)));
            if (!byCode.containsKey(candidate)) {
                code = candidate;
                break;
            }
        }
        if (code == null) {
            throw new IllegalStateException("No free acknowledgement code is available");
        }
        Instant now = clock.instant();
        byCode.put(code, new Entry<>(new HashSet<>(numbers), attachment, now, now.plus(validFor)));
        return code;
    }

    /** Forgets a code, for example because the SMS it was meant for could not be sent. */
    public synchronized void cancel(String code) {
        byCode.remove(code);
    }

    /**
     * Checks a reply and, if it is valid, uses up the code.
     *
     * @param number     where the reply came from, in +E.164 format
     * @param code       the code found in the reply
     * @param receivedAt when the provider received the reply, or null if unknown
     * @return the claim, or empty if the code is unknown, expired, already used, sent from another
     *         number, or older than the SMS it would answer. A rejected reply does not use up the code.
     */
    public synchronized Optional<Claim<A>> claim(String number, String code, Instant receivedAt) {
        purgeExpired();
        Entry<A> entry = byCode.get(code);
        if (entry == null || number == null || !entry.numbers.contains(number)) {
            return Optional.empty();
        }
        if (receivedAt != null && receivedAt.isBefore(entry.createdAt.minus(CLOCK_SKEW))) {
            return Optional.empty();
        }
        byCode.remove(code);
        return Optional.of(new Claim<>(code, number, entry.attachment, entry.createdAt));
    }

    /** How many codes are waiting for a reply right now. */
    public synchronized int pendingCount() {
        purgeExpired();
        return byCode.size();
    }

    private void purgeExpired() {
        Instant now = clock.instant();
        for (String code : new ArrayList<>(byCode.keySet())) {
            if (!byCode.get(code).expiresAt.isAfter(now)) {
                byCode.remove(code);
            }
        }
    }
}
