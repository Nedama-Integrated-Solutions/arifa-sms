package com.nedama.arifa.core.ack;

import com.nedama.arifa.core.sms.InboundSms;
import com.nedama.arifa.core.sms.SmsTransport;
import com.nedama.arifa.core.sms.SmsTransportException;
import com.nedama.arifa.core.text.PhoneNumbers;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads replies from the SMS provider and turns valid ones into acknowledgements.
 *
 * <p>The caller runs {@link #pollOnce()} on a timer. Each call does nothing, and makes no network
 * request, unless {@link AckRegistry} has codes waiting for a reply. So an idle plant costs
 * nothing, and the gateway only ever makes outbound requests. It never has to be reachable from
 * the internet.</p>
 *
 * <p>A reply is only turned into an acknowledgement when it parses as a reply, comes from a number
 * the alarm SMS was sent to, carries a code that is still waiting, and is not older than that SMS.
 * Everything else is ignored and logged, with the phone number masked and without the message text.</p>
 *
 * @param <A> the attachment type used with {@link AckRegistry}
 */
public final class InboxPoller<A> {

    /** Where the poller writes its messages. The Ignition adapter connects this to the gateway log. */
    public interface Log {
        void info(String message);

        void warn(String message);
    }

    /**
     * What one {@link #pollOnce()} call did.
     *
     * @param fetched      replies read from the provider
     * @param acknowledged replies that acknowledged alarms
     * @param rejected     valid replies whose acknowledgement could not be applied (for example already acknowledged)
     * @param ignored      replies that did not match anything waiting
     * @param error        why the provider could not be read, or null
     */
    public record PollResult(int fetched, int acknowledged, int rejected, int ignored, String error) {
        /** Returned when nothing was waiting, so the provider was not contacted. */
        public static final PollResult IDLE = new PollResult(0, 0, 0, 0, null);
    }

    private enum Handled {
        ACKNOWLEDGED, REJECTED, IGNORED
    }

    /** Safety limit on pages read in one poll. */
    private static final int MAX_PAGES = 20;

    /** The first failure is logged, then every sixth in a row, so a long outage does not flood the log. */
    private static final int LOG_EVERY_N_FAILURES = 6;

    private final SmsTransport transport;
    private final AckRegistry<A> registry;
    private final ReplyParser parser;
    private final AckHandler<A> handler;
    private final Log log;
    private final String defaultCountryCode;

    private long lastId;
    private int consecutiveFailures;

    public InboxPoller(SmsTransport transport, AckRegistry<A> registry, ReplyParser parser,
                       AckHandler<A> handler, Log log, String defaultCountryCode) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.log = Objects.requireNonNull(log, "log");
        if (!PhoneNumbers.isValidCountryCode(defaultCountryCode)) {
            throw new IllegalArgumentException("Default country code must be 1 to 3 digits, for example 254");
        }
        this.defaultCountryCode = defaultCountryCode;
    }

    /**
     * Reads new replies and acknowledges the ones that match. Never throws. Only one poll runs at a time.
     */
    public synchronized PollResult pollOnce() {
        if (registry.pendingCount() == 0) {
            return PollResult.IDLE;
        }
        int fetched = 0;
        int acknowledged = 0;
        int rejected = 0;
        int ignored = 0;

        for (int page = 0; page < MAX_PAGES; page++) {
            List<InboundSms> batch;
            try {
                batch = transport.fetchInbound(lastId);
            } catch (SmsTransportException e) {
                consecutiveFailures++;
                if (consecutiveFailures == 1 || consecutiveFailures % LOG_EVERY_N_FAILURES == 0) {
                    log.warn("Could not read SMS replies (failure " + consecutiveFailures + " in a row): "
                            + e.getMessage());
                }
                return new PollResult(fetched, acknowledged, rejected, ignored, e.getMessage());
            } catch (RuntimeException e) {
                consecutiveFailures++;
                String reason = "unexpected error (" + e.getClass().getSimpleName() + "): " + e.getMessage();
                if (consecutiveFailures == 1 || consecutiveFailures % LOG_EVERY_N_FAILURES == 0) {
                    log.warn("Could not read SMS replies: " + reason);
                }
                return new PollResult(fetched, acknowledged, rejected, ignored, reason);
            }
            if (consecutiveFailures > 0) {
                log.info("Reading SMS replies works again after " + consecutiveFailures + " failure(s).");
                consecutiveFailures = 0;
            }
            if (batch.isEmpty()) {
                break;
            }
            long newest = lastId;
            for (InboundSms sms : batch) {
                newest = Math.max(newest, sms.id());
                fetched++;
                switch (handle(sms)) {
                    case ACKNOWLEDGED -> acknowledged++;
                    case REJECTED -> rejected++;
                    case IGNORED -> ignored++;
                }
            }
            if (newest <= lastId) {
                break;
            }
            lastId = newest;
        }
        return new PollResult(fetched, acknowledged, rejected, ignored, null);
    }

    private Handled handle(InboundSms sms) {
        String from = PhoneNumbers.tryToE164(sms.from(), defaultCountryCode).orElse(null);
        String who = PhoneNumbers.mask(from != null ? from : sms.from());

        Optional<String> code = parser.parseCode(sms.text());
        if (code.isEmpty()) {
            log.info("Ignored an SMS from " + who + ": it is not an acknowledgement reply.");
            return Handled.IGNORED;
        }
        if (from == null) {
            log.info("Ignored an SMS from " + who + ": the sender number is not valid.");
            return Handled.IGNORED;
        }

        Optional<AckRegistry.Claim<A>> claim = registry.claim(from, code.get(), parseTime(sms.receivedAt()));
        if (claim.isEmpty()) {
            log.info("Ignored an acknowledgement reply from " + who
                    + ": no matching code is waiting for this number (wrong code, expired, already used,"
                    + " or sent from a different phone).");
            return Handled.IGNORED;
        }

        AckOutcome outcome;
        try {
            outcome = handler.acknowledge(claim.get());
        } catch (RuntimeException e) {
            log.warn("Acknowledgement for a reply from " + who + " failed with an unexpected error ("
                    + e.getClass().getSimpleName() + "): " + e.getMessage());
            return Handled.REJECTED;
        }
        if (outcome.success()) {
            log.info("Acknowledged by SMS reply from " + who + ": " + outcome.summary());
            return Handled.ACKNOWLEDGED;
        }
        log.warn("Reply from " + who + " was valid but nothing was acknowledged: " + outcome.summary());
        return Handled.REJECTED;
    }

    private static Instant parseTime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
