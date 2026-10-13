package com.nedama.arifa.core.notify;

import com.nedama.arifa.core.notify.NotificationOutcome.Status;
import com.nedama.arifa.core.sms.Outcome;
import com.nedama.arifa.core.sms.RecipientResult;
import com.nedama.arifa.core.sms.SendResult;
import com.nedama.arifa.core.sms.SmsTransport;
import com.nedama.arifa.core.sms.SmsTransportException;
import com.nedama.arifa.core.text.GsmText;
import com.nedama.arifa.core.text.PhoneNumbers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Turns "tell this user about this alarm" into SMS, and decides whether it worked.
 *
 * <p>This is the whole decision logic of an Arifa notification, kept free of Ignition
 * classes so it can be unit tested here and reused unchanged in the 8.1 and 8.3 builds.
 * The Ignition adapter only collects the user's numbers, evaluates the message
 * expression, calls {@link #send}, and then reports the outcome back to Ignition.</p>
 *
 * <p>Rules, chosen for alarms (a missed alarm is worse than a duplicate):</p>
 * <ul>
 *   <li>Numbers are cleaned to +E.164 and de-duplicated. Unusable numbers are skipped.</li>
 *   <li>The text is cleaned to the GSM alphabet and cut to a small number of SMS.</li>
 *   <li>Temporary problems are retried once, after a short pause. Only the recipients that
 *       failed temporarily are retried, so nobody gets a duplicate.</li>
 *   <li>Permanent problems (opted out, bad number, bad credentials, no balance) are never
 *       retried. They fail immediately so the pipeline's fallback can take over.</li>
 *   <li>If at least one recipient is accepted the notification counts as delivered.</li>
 * </ul>
 *
 * <p>Instances are immutable and safe to share between threads, provided the transport is.</p>
 */
public final class SmsNotifier {

    /** Default cap on SMS per notification. Each extra SMS is billed. */
    public static final int DEFAULT_MAX_SEGMENTS = 2;

    /** Default wait before the single retry. */
    public static final long DEFAULT_RETRY_DELAY_MILLIS = 2_000L;

    private static final int MAX_ATTEMPTS = 2;
    private static final int MAX_REASON_CHARS = 200;

    /** Waits for a while. A seam so tests do not really sleep. */
    @FunctionalInterface
    public interface Pause {
        void pause(long millis) throws InterruptedException;
    }

    private final SmsTransport transport;
    private final String defaultCountryCode;
    private final int maxSegments;
    private final long retryDelayMillis;
    private final Pause pause;
    private final SendLimiter limiter;

    /** Uses the default segment cap and retry delay, and really sleeps between attempts. */
    public SmsNotifier(SmsTransport transport, String defaultCountryCode) {
        this(transport, defaultCountryCode, DEFAULT_MAX_SEGMENTS, DEFAULT_RETRY_DELAY_MILLIS, Thread::sleep);
    }

    public SmsNotifier(SmsTransport transport, String defaultCountryCode,
                       int maxSegments, long retryDelayMillis, Pause pause) {
        this(transport, defaultCountryCode, maxSegments, retryDelayMillis, pause, null);
    }

    /**
     * @param limiter a cap on SMS sent, or null for no cap. When the cap is reached {@link #send} returns a
     *                {@link NotificationOutcome.Status#LIMITED} outcome without calling the provider.
     */
    public SmsNotifier(SmsTransport transport, String defaultCountryCode,
                       int maxSegments, long retryDelayMillis, Pause pause, SendLimiter limiter) {
        this.limiter = limiter;
        this.transport = Objects.requireNonNull(transport, "transport");
        if (!PhoneNumbers.isValidCountryCode(defaultCountryCode)) {
            throw new IllegalArgumentException("Default country code must be 1 to 3 digits, for example 254");
        }
        if (maxSegments < 1) {
            throw new IllegalArgumentException("maxSegments must be at least 1");
        }
        if (retryDelayMillis < 0) {
            throw new IllegalArgumentException("retryDelayMillis must not be negative");
        }
        this.defaultCountryCode = defaultCountryCode;
        this.maxSegments = maxSegments;
        this.retryDelayMillis = retryDelayMillis;
        this.pause = Objects.requireNonNull(pause, "pause");
    }

    /**
     * Prepares the message text exactly as {@link #send} will send it: cleaned to the GSM
     * alphabet and cut to the segment cap. Used by test mode to show what would be sent.
     */
    public String prepareMessage(String rawMessage) {
        return prepareMessage(rawMessage, null);
    }

    /**
     * Same, with a suffix (such as the acknowledgement instruction) that is always kept whole.
     * Only the message is shortened to make room for it.
     */
    public String prepareMessage(String rawMessage, String suffix) {
        return GsmText.truncateToSegments(rawMessage, maxSegments, suffix);
    }

    /**
     * Sends the message to the given numbers and reports what happened. Never throws for
     * delivery problems; they are reported in the returned outcome. May block for the
     * network timeout (twice, plus the retry pause), so call it from a worker thread.
     *
     * @param rawNumbers the user's SMS contact values as typed, for example "0712 345 678"
     * @param rawMessage the alarm text, before cleaning
     */
    public NotificationOutcome send(List<String> rawNumbers, String rawMessage) {
        return send(rawNumbers, rawMessage, null);
    }

    /**
     * Same as {@link #send(List, String)}, with a suffix that is kept whole after the message,
     * such as "Reply NEDAMA 4821 to ack".
     */
    public NotificationOutcome send(List<String> rawNumbers, String rawMessage, String suffix) {
        if (GsmText.sanitize(rawMessage).isBlank()) {
            return failed("SMS not sent: the message text is empty", "", 0, List.of(), 0, 0);
        }
        String message = prepareMessage(rawMessage, suffix);
        int segments = GsmText.segmentCount(message);

        Set<String> valid = new LinkedHashSet<>();
        int rejected = 0;
        if (rawNumbers != null) {
            for (String raw : rawNumbers) {
                Optional<String> number = PhoneNumbers.tryToE164(raw, defaultCountryCode);
                if (number.isPresent()) {
                    valid.add(number.get());
                } else {
                    rejected++;
                }
            }
        }
        if (valid.isEmpty()) {
            String why = rejected > 0
                    ? "no usable SMS number (" + rejected + " rejected as invalid)"
                    : "the user has no SMS contact";
            return failed("SMS not sent: " + why, message, segments, List.of(), rejected, 0);
        }

        // Reserve room under the hourly cap first. A refused send gives the reservation back afterwards.
        SendLimiter.Reservation reservation = null;
        if (limiter != null) {
            int units = valid.size() * Math.max(1, segments);
            Optional<SendLimiter.Reservation> reserved = limiter.tryReserve(units);
            if (reserved.isEmpty()) {
                return new NotificationOutcome(Status.LIMITED,
                        "SMS not sent: the limit of " + limiter.limit() + " SMS per hour is reached ("
                                + limiter.used() + " used in the last hour). Raise the limit, or find out why so "
                                + "many alarms are being sent",
                        message, segments, List.of(), rejected, 0);
            }
            reservation = reserved.get();
        }

        Map<String, RecipientResult> results = new LinkedHashMap<>();
        List<String> pending = new ArrayList<>(valid);
        String transportError = null;
        int attempts = 0;
        // Why each failed attempt failed, in order. Shown in the log so a retry is never a mystery.
        List<String> problems = new ArrayList<>();

        while (!pending.isEmpty() && attempts < MAX_ATTEMPTS) {
            if (attempts > 0) {
                try {
                    pause.pause(retryDelayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    transportError = "interrupted while waiting to retry";
                    break;
                }
            }
            attempts++;
            try {
                SendResult sendResult = transport.send(pending, message);
                transportError = null;
                List<String> retry = new ArrayList<>();
                for (RecipientResult r : sendResult.recipients()) {
                    String key = r.number() != null ? r.number() : "unreported-" + results.size();
                    results.put(key, r);
                    if (r.outcome() == Outcome.RETRYABLE_FAILURE && r.number() != null) {
                        retry.add(r.number());
                        problems.add(PhoneNumbers.mask(r.number()) + " " + r.providerStatus());
                    }
                }
                pending = retry;
            } catch (SmsTransportException e) {
                transportError = e.getMessage();
                problems.add(e.getMessage());
                if (!e.isRetryable()) {
                    break;
                }
                // pending stays as it is, so the retry covers everyone
            } catch (RuntimeException e) {
                transportError = "unexpected error (" + e.getClass().getSimpleName() + "): " + e.getMessage();
                break;
            }
        }

        if (reservation != null) {
            int acceptedRecipients = (int) results.values().stream().filter(RecipientResult::accepted).count();
            reservation.settle(acceptedRecipients * Math.max(1, segments));
        }
        return conclude(message, segments, valid.size(), rejected, attempts, results, transportError, problems);
    }

    private NotificationOutcome conclude(String message, int segments, int requested, int rejected,
                                         int attempts, Map<String, RecipientResult> results,
                                         String transportError, List<String> problems) {
        List<RecipientResult> all = new ArrayList<>(results.values());
        int accepted = (int) all.stream().filter(RecipientResult::accepted).count();

        if (accepted == 0) {
            String summary = transportError != null
                    ? "SMS not sent: " + transportError
                    : "SMS not accepted: " + describeFailures(all, requested);
            // After two attempts, also say what went wrong the first time if it was something different.
            if (attempts > 1 && problems.size() > 1 && !problems.get(0).equals(problems.get(problems.size() - 1))) {
                summary += " (first attempt: " + shorten(problems.get(0)) + ")";
            }
            return new NotificationOutcome(Status.FAILED, summary, message, segments, all, rejected, attempts);
        }
        String cost = CostSummary.of(all).describe();
        if (accepted >= requested) {
            String summary = "SMS accepted for " + accepted + " recipient" + (accepted == 1 ? "" : "s");
            String notes = "";
            if (attempts > 1) {
                notes = problems.isEmpty()
                        ? "after retry; "
                        : "after retry; first attempt failed: " + shorten(problems.get(0)) + "; ";
            }
            summary += " (" + notes + "cost " + cost + ")";
            return new NotificationOutcome(Status.SENT, summary, message, segments, all, rejected, attempts);
        }
        String summary = "SMS accepted for " + accepted + " of " + requested + " recipients (cost " + cost
                + "); not sent to: " + describeFailures(all, requested);
        return new NotificationOutcome(Status.PARTIAL, summary, message, segments, all, rejected, attempts);
    }

    /** Keeps one reason short enough for a log line and the profile status. */
    private static String shorten(String text) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return flat.length() <= MAX_REASON_CHARS ? flat : flat.substring(0, MAX_REASON_CHARS) + "...";
    }

    /** Lists the recipients that were not accepted, with masked numbers and the provider's status. */
    private static String describeFailures(List<RecipientResult> all, int requested) {
        List<String> parts = new ArrayList<>();
        for (RecipientResult r : all) {
            if (!r.accepted()) {
                parts.add(PhoneNumbers.mask(r.number()) + " " + r.providerStatus());
            }
        }
        int missing = requested - all.size();
        if (missing > 0) {
            parts.add(missing + " recipient" + (missing == 1 ? "" : "s") + " with no answer");
        }
        return parts.isEmpty() ? "no answer from the provider" : String.join(", ", parts);
    }

    private static NotificationOutcome failed(String summary, String message, int segments,
                                              List<RecipientResult> results, int rejected, int attempts) {
        return new NotificationOutcome(Status.FAILED, summary, message, segments, results, rejected, attempts);
    }
}
