package com.nedama.arifa.core.ack;

import com.nedama.arifa.core.ack.InboxPoller.PollResult;
import com.nedama.arifa.core.sms.InboundSms;
import com.nedama.arifa.core.sms.SendResult;
import com.nedama.arifa.core.sms.SmsTransport;
import com.nedama.arifa.core.sms.SmsTransportException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InboxPollerTest {

    private static final String A = "+254712000001";
    private static final String B = "+254712000002";

    /** An inbox the test fills. Like the real one, it only returns messages newer than the given id. */
    private static final class FakeInbox implements SmsTransport {
        final List<InboundSms> messages = new ArrayList<>();
        final List<Long> asked = new ArrayList<>();
        SmsTransportException failure;
        RuntimeException crash;
        int pageSize = 100;

        void add(long id, String from, String text) {
            add(id, from, text, Instant.now().toString());
        }

        void add(long id, String from, String text, String receivedAt) {
            messages.add(new InboundSms(id, from, "12345", text, receivedAt, null));
        }

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public SendResult send(List<String> recipients, String text) {
            throw new AssertionError("The poller must never send");
        }

        @Override
        public boolean supportsInbound() {
            return true;
        }

        @Override
        public List<InboundSms> fetchInbound(long afterId) throws SmsTransportException {
            asked.add(afterId);
            if (failure != null) {
                throw failure;
            }
            if (crash != null) {
                throw crash;
            }
            return messages.stream().filter(m -> m.id() > afterId).sorted(
                    (x, y) -> Long.compare(x.id(), y.id())).limit(pageSize).toList();
        }
    }

    private static final class RecordingLog implements InboxPoller.Log {
        final List<String> info = new ArrayList<>();
        final List<String> warn = new ArrayList<>();

        @Override
        public void info(String message) {
            info.add(message);
        }

        @Override
        public void warn(String message) {
            warn.add(message);
        }

        String all() {
            return String.join("\n", info) + "\n" + String.join("\n", warn);
        }
    }

    private final FakeInbox inbox = new FakeInbox();
    private final RecordingLog log = new RecordingLog();
    private final AckRegistry<String> registry =
            new AckRegistry<>(Clock.systemUTC(), Duration.ofMinutes(60), 100, new Random(7));
    private final List<AckRegistry.Claim<String>> acknowledged = new ArrayList<>();
    private AckOutcome nextOutcome = new AckOutcome(true, "ok");

    private InboxPoller<String> poller() {
        AckHandler<String> handler = claim -> {
            acknowledged.add(claim);
            return nextOutcome;
        };
        return new InboxPoller<>(inbox, registry, new ReplyParser("NEDAMA"), handler, log, "254");
    }

    @Test
    void doesNothingAndDoesNotCallTheProviderWhenNothingIsWaiting() {
        PollResult result = poller().pollOnce();

        assertEquals(PollResult.IDLE, result);
        assertTrue(inbox.asked.isEmpty());
    }

    @Test
    void acknowledgesAValidReply() {
        String code = registry.register(List.of(A), "alarm-set-1");
        inbox.add(100, A, "NEDAMA " + code);

        PollResult result = poller().pollOnce();

        assertEquals(1, result.fetched());
        assertEquals(1, result.acknowledged());
        assertEquals(0, result.ignored());
        assertEquals("alarm-set-1", acknowledged.get(0).attachment());
        assertEquals(A, acknowledged.get(0).number());
        assertEquals(0, registry.pendingCount());
        assertTrue(log.all().contains("Acknowledged by SMS reply"));
    }

    @Test
    void ignoresAWrongCodeAndKeepsWaiting() {
        String code = registry.register(List.of(A), "x");
        String wrong = code.equals("0000") ? "1111" : "0000";
        inbox.add(100, A, "NEDAMA " + wrong);

        PollResult result = poller().pollOnce();

        assertEquals(1, result.ignored());
        assertTrue(acknowledged.isEmpty());
        assertEquals(1, registry.pendingCount());
        assertTrue(log.all().contains("no matching code"));
    }

    @Test
    void ignoresAReplyFromAnotherPhoneEvenWithTheRightCode() {
        String code = registry.register(List.of(A), "x");
        inbox.add(100, B, "NEDAMA " + code);

        PollResult result = poller().pollOnce();

        assertEquals(1, result.ignored());
        assertTrue(acknowledged.isEmpty());
        assertEquals(1, registry.pendingCount());
    }

    @Test
    void ignoresMessagesThatAreNotReplies() {
        registry.register(List.of(A), "x");
        inbox.add(100, A, "thanks, on my way");

        PollResult result = poller().pollOnce();

        assertEquals(1, result.ignored());
        assertTrue(acknowledged.isEmpty());
        assertTrue(log.all().contains("not an acknowledgement reply"));
        assertFalse(log.all().contains("on my way"));
    }

    @Test
    void ignoresAnOldMessageThatCarriesTheSameDigits() {
        String code = registry.register(List.of(A), "x");
        String longAgo = Instant.now().minus(Duration.ofHours(5)).toString();
        inbox.add(100, A, "NEDAMA " + code, longAgo);

        PollResult result = poller().pollOnce();

        assertEquals(1, result.ignored());
        assertTrue(acknowledged.isEmpty());
    }

    @Test
    void neverReadsTheSameMessageTwice() {
        String code = registry.register(List.of(A), "x");
        inbox.add(100, A, "NEDAMA " + code);
        InboxPoller<String> poller = poller();

        poller.pollOnce();
        registry.register(List.of(A), "second");
        PollResult second = poller.pollOnce();

        assertEquals(0, second.fetched());
        assertEquals(1, acknowledged.size());
        assertEquals(100L, inbox.asked.get(inbox.asked.size() - 1).longValue());
    }

    @Test
    void readsSeveralPagesInOnePoll() {
        inbox.pageSize = 2;
        String code = registry.register(List.of(A), "x");
        inbox.add(1, B, "hello");
        inbox.add(2, B, "hello");
        inbox.add(3, B, "hello");
        inbox.add(4, B, "hello");
        inbox.add(5, A, "NEDAMA " + code);

        PollResult result = poller().pollOnce();

        assertEquals(5, result.fetched());
        assertEquals(1, result.acknowledged());
        assertEquals(4, result.ignored());
    }

    @Test
    void reportsAValidReplyWhoseAcknowledgementFailed() {
        String code = registry.register(List.of(A), "x");
        inbox.add(100, A, "NEDAMA " + code);
        nextOutcome = new AckOutcome(false, "already acknowledged");

        PollResult result = poller().pollOnce();

        assertEquals(1, result.rejected());
        assertEquals(0, result.acknowledged());
        assertTrue(log.warn.get(0).contains("already acknowledged"));
        assertEquals(0, registry.pendingCount()); // the code was used up
    }

    @Test
    void aCrashingHandlerDoesNotStopThePoll() {
        String first = registry.register(List.of(A), "x");
        String second = registry.register(List.of(B), "y");
        inbox.add(100, A, "NEDAMA " + first);
        inbox.add(101, B, "NEDAMA " + second);
        int[] calls = {0};
        AckHandler<String> handler = claim -> {
            if (calls[0]++ == 0) {
                throw new IllegalStateException("boom");
            }
            return new AckOutcome(true, "ok");
        };
        InboxPoller<String> poller = new InboxPoller<>(
                inbox, registry, new ReplyParser("NEDAMA"), handler, log, "254");

        PollResult result = poller.pollOnce();

        assertEquals(1, result.rejected());
        assertEquals(1, result.acknowledged());
    }

    @Test
    void providerFailuresAreReportedAndLogThrottled() {
        registry.register(List.of(A), "x");
        inbox.failure = new SmsTransportException("Timed out", true, -1, null);
        InboxPoller<String> poller = poller();

        for (int i = 0; i < 7; i++) {
            PollResult result = poller.pollOnce();
            assertEquals("Timed out", result.error());
        }

        assertEquals(2, log.warn.size()); // the first failure and the sixth
        assertTrue(log.warn.get(0).contains("failure 1 in a row"));
        assertTrue(log.warn.get(1).contains("failure 6 in a row"));
    }

    @Test
    void recoveryAfterFailuresIsLoggedAndWorks() {
        String code = registry.register(List.of(A), "x");
        inbox.add(100, A, "NEDAMA " + code);
        inbox.failure = new SmsTransportException("Timed out", true, -1, null);
        InboxPoller<String> poller = poller();
        poller.pollOnce();

        inbox.failure = null;
        PollResult result = poller.pollOnce();

        assertEquals(1, result.acknowledged());
        assertTrue(log.all().contains("works again"));
    }

    @Test
    void anUnexpectedErrorFromTheProviderBecomesAFailure() {
        registry.register(List.of(A), "x");
        inbox.crash = new IllegalStateException("weird");

        PollResult result = poller().pollOnce();

        assertTrue(result.error().contains("IllegalStateException"));
    }

    @Test
    void logsNeverContainAFullPhoneNumber() {
        String code = registry.register(List.of(A), "x");
        String wrong = code.equals("0000") ? "1111" : "0000";
        inbox.add(100, A, "NEDAMA " + wrong);
        inbox.add(101, B, "NEDAMA " + code);

        poller().pollOnce();

        assertFalse(log.all().contains(A));
        assertFalse(log.all().contains(B));
        assertTrue(log.all().contains("+254712***001"));
    }

    @Test
    void rejectsBadConstruction() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                new InboxPoller<>(inbox, registry, new ReplyParser("N"), c -> new AckOutcome(true, ""), log, "+254"));
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class, () ->
                new InboxPoller<>(null, registry, new ReplyParser("N"), c -> new AckOutcome(true, ""), log, "254"));
    }
}
