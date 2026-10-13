package com.nedama.arifa.core.notify;

import com.nedama.arifa.core.notify.NotificationOutcome.Status;
import com.nedama.arifa.core.sms.InboundSms;
import com.nedama.arifa.core.sms.Outcome;
import com.nedama.arifa.core.sms.RecipientResult;
import com.nedama.arifa.core.sms.SendResult;
import com.nedama.arifa.core.sms.SmsTransport;
import com.nedama.arifa.core.sms.SmsTransportException;
import com.nedama.arifa.core.text.GsmText;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmsNotifierTest {

    private static final String A = "+254712000001";
    private static final String B = "+254712000002";

    /** A transport that answers from a script and records what it was asked to send. */
    private static final class FakeTransport implements SmsTransport {
        final Deque<Object> script = new ArrayDeque<>();
        final List<List<String>> calls = new ArrayList<>();
        final List<String> texts = new ArrayList<>();

        FakeTransport then(Object next) {
            script.add(next);
            return this;
        }

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public SendResult send(List<String> recipients, String text) throws SmsTransportException {
            calls.add(List.copyOf(recipients));
            texts.add(text);
            Object next = script.poll();
            if (next == null) {
                throw new AssertionError("The transport was called more often than the test expected");
            }
            if (next instanceof SmsTransportException e) {
                throw e;
            }
            if (next instanceof RuntimeException e) {
                throw e;
            }
            return (SendResult) next;
        }

        @Override
        public boolean supportsInbound() {
            return false;
        }

        @Override
        public List<InboundSms> fetchInbound(long afterId) {
            return List.of();
        }
    }

    private final List<Long> pauses = new ArrayList<>();

    private SmsNotifier notifier(FakeTransport transport) {
        return new SmsNotifier(transport, "254", 2, 1500, pauses::add);
    }

    private static RecipientResult accepted(String number) {
        return new RecipientResult(number, Outcome.ACCEPTED, 101, "Sent", "ATXid_1", "KES 0.8000");
    }

    private static RecipientResult failure(String number, int code, String status, Outcome outcome) {
        return new RecipientResult(number, outcome, code, status, null, null);
    }

    private static SendResult answer(RecipientResult... recipients) {
        return new SendResult(List.of(recipients), "summary");
    }

    private static SmsTransportException transportError(String message, boolean retryable) {
        return new SmsTransportException(message, retryable, retryable ? -1 : 401, null);
    }

    // ------------------------------------------------------------------
    // Happy path and preparation
    // ------------------------------------------------------------------

    @Test
    void sendsOnceToACleanedNumberWithACleanedMessage() {
        FakeTransport transport = new FakeTransport().then(answer(accepted(A)));

        NotificationOutcome outcome = notifier(transport)
                .send(List.of("0712 000 001"), "Tank T-101 temp 85\u00b0C \u2013 HIGH");

        assertEquals(Status.SENT, outcome.status());
        assertTrue(outcome.success());
        assertEquals(1, outcome.attempts());
        assertEquals(List.of(List.of(A)), transport.calls);
        assertEquals("Tank T-101 temp 85degC - HIGH", transport.texts.get(0));
        assertEquals("Tank T-101 temp 85degC - HIGH", outcome.message());
        assertEquals(1, outcome.segments());
        assertTrue(pauses.isEmpty());
    }

    @Test
    void removesDuplicateNumbersWrittenDifferently() {
        FakeTransport transport = new FakeTransport().then(answer(accepted(A)));

        NotificationOutcome outcome = notifier(transport)
                .send(List.of("0712000001", "+254 712 000 001", "254712000001"), "Alarm");

        assertEquals(List.of(List.of(A)), transport.calls);
        assertEquals(Status.SENT, outcome.status());
    }

    @Test
    void skipsUnusableNumbersButStillSendsToGoodOnes() {
        FakeTransport transport = new FakeTransport().then(answer(accepted(A)));

        NotificationOutcome outcome = notifier(transport)
                .send(List.of("rubbish", "0712000001", "0202345678"), "Alarm");

        assertEquals(Status.SENT, outcome.status());
        assertEquals(2, outcome.rejectedNumbers());
        assertEquals(List.of(List.of(A)), transport.calls);
    }

    @Test
    void longMessagesAreCutToTheSegmentCap() {
        FakeTransport transport = new FakeTransport().then(answer(accepted(A)));

        NotificationOutcome outcome = notifier(transport).send(List.of(A), "x".repeat(900));

        assertEquals(2, outcome.segments());
        assertTrue(GsmText.segmentCount(transport.texts.get(0)) <= 2);
        assertTrue(transport.texts.get(0).endsWith("..."));
    }

    @Test
    void prepareMessageMatchesWhatIsSent() {
        SmsNotifier notifier = notifier(new FakeTransport());
        assertEquals("Temp 85degC", notifier.prepareMessage("Temp 85\u00b0C"));
    }

    // ------------------------------------------------------------------
    // Nothing to send
    // ------------------------------------------------------------------

    @Test
    void noNumbersFailsWithoutCallingTheProvider() {
        FakeTransport transport = new FakeTransport();

        NotificationOutcome none = notifier(transport).send(List.of(), "Alarm");
        NotificationOutcome nullList = notifier(transport).send(null, "Alarm");

        assertEquals(Status.FAILED, none.status());
        assertTrue(none.summary().contains("no SMS contact"));
        assertEquals(Status.FAILED, nullList.status());
        assertEquals(0, none.attempts());
        assertTrue(transport.calls.isEmpty());
    }

    @Test
    void onlyInvalidNumbersFailsAndSaysSo() {
        FakeTransport transport = new FakeTransport();

        NotificationOutcome outcome = notifier(transport).send(List.of("12", "abc"), "Alarm");

        assertEquals(Status.FAILED, outcome.status());
        assertTrue(outcome.summary().contains("2 rejected"));
        assertTrue(transport.calls.isEmpty());
    }

    @Test
    void blankMessageFailsWithoutCallingTheProvider() {
        FakeTransport transport = new FakeTransport();

        NotificationOutcome outcome = notifier(transport).send(List.of(A), "   ");

        assertEquals(Status.FAILED, outcome.status());
        assertTrue(outcome.summary().contains("empty"));
        assertTrue(transport.calls.isEmpty());
    }

    // ------------------------------------------------------------------
    // Failures that must not be retried
    // ------------------------------------------------------------------

    @Test
    void optedOutRecipientFailsImmediatelyAndNeverLeaksTheFullNumber() {
        FakeTransport transport = new FakeTransport()
                .then(answer(failure(A, 406, "UserInBlacklist", Outcome.PERMANENT_FAILURE)));

        NotificationOutcome outcome = notifier(transport).send(List.of(A), "Alarm");

        assertEquals(Status.FAILED, outcome.status());
        assertFalse(outcome.success());
        assertEquals(1, outcome.attempts());
        assertTrue(pauses.isEmpty());
        assertTrue(outcome.summary().contains("UserInBlacklist"));
        assertTrue(outcome.summary().contains("+254712***001"));
        assertFalse(outcome.summary().contains(A));
    }

    @Test
    void badCredentialsFailImmediatelyWithoutRetry() {
        FakeTransport transport = new FakeTransport()
                .then(transportError("Africa's Talking rejected the credentials (HTTP 401).", false));

        NotificationOutcome outcome = notifier(transport).send(List.of(A), "Alarm");

        assertEquals(Status.FAILED, outcome.status());
        assertEquals(1, outcome.attempts());
        assertTrue(pauses.isEmpty());
        assertTrue(outcome.summary().contains("rejected the credentials"));
    }

    @Test
    void unexpectedErrorsBecomeAFailureNotACrash() {
        FakeTransport transport = new FakeTransport().then(new IllegalStateException("boom"));

        NotificationOutcome outcome = notifier(transport).send(List.of(A), "Alarm");

        assertEquals(Status.FAILED, outcome.status());
        assertTrue(outcome.summary().contains("IllegalStateException"));
        assertEquals(1, outcome.attempts());
    }

    // ------------------------------------------------------------------
    // Retry
    // ------------------------------------------------------------------

    @Test
    void networkTroubleIsRetriedOnceAndCanSucceed() {
        FakeTransport transport = new FakeTransport()
                .then(transportError("Timed out", true))
                .then(answer(accepted(A)));

        NotificationOutcome outcome = notifier(transport).send(List.of(A), "Alarm");

        assertEquals(Status.SENT, outcome.status());
        assertEquals(2, outcome.attempts());
        assertEquals(List.of(1500L), pauses);
        assertTrue(outcome.summary().contains("after retry"));
        assertTrue(outcome.summary().contains("first attempt failed: Timed out"));
    }

    @Test
    void aProviderFaultThatLaterSucceedsNamesTheFirstFaultAndMasksTheNumber() {
        FakeTransport transport = new FakeTransport()
                .then(answer(failure(A, 500, "InternalServerError", Outcome.RETRYABLE_FAILURE)))
                .then(answer(accepted(A)));

        NotificationOutcome outcome = notifier(transport).send(List.of(A), "Alarm");

        assertEquals(Status.SENT, outcome.status());
        assertTrue(outcome.summary().contains("first attempt failed: +254712***001 InternalServerError"));
        assertFalse(outcome.summary().contains(A));
    }

    @Test
    void whenBothAttemptsFailDifferentlyTheFirstReasonIsShownToo() {
        FakeTransport transport = new FakeTransport()
                .then(transportError("Connection reset", true))
                .then(transportError("Timed out", true));

        NotificationOutcome outcome = notifier(transport).send(List.of(A), "Alarm");

        assertEquals(Status.FAILED, outcome.status());
        assertTrue(outcome.summary().contains("Timed out"));
        assertTrue(outcome.summary().contains("(first attempt: Connection reset)"));
    }

    @Test
    void whenBothAttemptsFailTheSameWayItIsNotRepeated() {
        FakeTransport transport = new FakeTransport()
                .then(transportError("Timed out", true))
                .then(transportError("Timed out", true));

        NotificationOutcome outcome = notifier(transport).send(List.of(A), "Alarm");

        assertFalse(outcome.summary().contains("first attempt"));
    }

    @Test
    void aVeryLongFailureReasonIsCutShort() {
        FakeTransport transport = new FakeTransport()
                .then(transportError("x".repeat(1000), true))
                .then(answer(accepted(A)));

        NotificationOutcome outcome = notifier(transport).send(List.of(A), "Alarm");

        assertTrue(outcome.summary().length() < 400);
        assertTrue(outcome.summary().contains("..."));
    }

    @Test
    void networkTroubleTwiceFailsAfterExactlyTwoAttempts() {
        FakeTransport transport = new FakeTransport()
                .then(transportError("Timed out", true))
                .then(transportError("Timed out again", true));

        NotificationOutcome outcome = notifier(transport).send(List.of(A), "Alarm");

        assertEquals(Status.FAILED, outcome.status());
        assertEquals(2, outcome.attempts());
        assertEquals(2, transport.calls.size());
        assertTrue(outcome.summary().contains("Timed out again"));
    }

    @Test
    void providerFaultIsRetriedOnlyForTheRecipientsThatFailedTemporarily() {
        FakeTransport transport = new FakeTransport()
                .then(answer(accepted(A), failure(B, 500, "InternalServerError", Outcome.RETRYABLE_FAILURE)))
                .then(answer(accepted(B)));

        NotificationOutcome outcome = notifier(transport).send(List.of(A, B), "Alarm");

        assertEquals(Status.SENT, outcome.status());
        assertEquals(2, outcome.attempts());
        assertEquals(List.of(A, B), transport.calls.get(0));
        assertEquals(List.of(B), transport.calls.get(1));
    }

    @Test
    void retryableFailureThatStaysFailedIsReportedAfterTwoAttempts() {
        FakeTransport transport = new FakeTransport()
                .then(answer(failure(A, 500, "InternalServerError", Outcome.RETRYABLE_FAILURE)))
                .then(answer(failure(A, 501, "GatewayError", Outcome.RETRYABLE_FAILURE)));

        NotificationOutcome outcome = notifier(transport).send(List.of(A), "Alarm");

        assertEquals(Status.FAILED, outcome.status());
        assertEquals(2, outcome.attempts());
        assertTrue(outcome.summary().contains("GatewayError"));
    }

    @Test
    void interruptedWhileWaitingToRetryFailsAndKeepsTheInterruptFlag() {
        FakeTransport transport = new FakeTransport().then(transportError("Timed out", true));
        SmsNotifier notifier = new SmsNotifier(transport, "254", 2, 1500, ms -> {
            throw new InterruptedException();
        });

        NotificationOutcome outcome = notifier.send(List.of(A), "Alarm");

        assertEquals(Status.FAILED, outcome.status());
        assertEquals(1, outcome.attempts());
        assertTrue(outcome.summary().contains("interrupted"));
        assertTrue(Thread.interrupted()); // reads and clears the flag so other tests are unaffected
    }

    // ------------------------------------------------------------------
    // Partial delivery
    // ------------------------------------------------------------------

    @Test
    void oneAcceptedAndOneRefusedCountsAsPartialSuccess() {
        FakeTransport transport = new FakeTransport().then(answer(
                accepted(A), failure(B, 406, "UserInBlacklist", Outcome.PERMANENT_FAILURE)));

        NotificationOutcome outcome = notifier(transport).send(List.of(A, B), "Alarm");

        assertEquals(Status.PARTIAL, outcome.status());
        assertTrue(outcome.success());
        assertEquals(1, outcome.attempts());
        assertTrue(outcome.summary().contains("1 of 2"));
        assertTrue(outcome.summary().contains("UserInBlacklist"));
        assertFalse(outcome.summary().contains(B));
    }

    @Test
    void recipientsTheProviderDidNotMentionAreReported() {
        FakeTransport transport = new FakeTransport().then(answer(accepted(A)));

        NotificationOutcome outcome = notifier(transport).send(List.of(A, B), "Alarm");

        assertEquals(Status.PARTIAL, outcome.status());
        assertTrue(outcome.summary().contains("1 recipient with no answer"));
    }

    // ------------------------------------------------------------------
    // Suffix (acknowledgement instruction)
    // ------------------------------------------------------------------

    @Test
    void theSuffixIsSentWholeAfterTheMessage() {
        FakeTransport transport = new FakeTransport().then(answer(accepted(A)));

        NotificationOutcome outcome = notifier(transport)
                .send(List.of(A), "Tank T-101 temp 85\u00b0C HIGH", "Reply NEDAMA 4821 to ack");

        assertEquals(Status.SENT, outcome.status());
        assertEquals("Tank T-101 temp 85degC HIGH\nReply NEDAMA 4821 to ack", transport.texts.get(0));
        assertEquals(outcome.message(), transport.texts.get(0));
    }

    @Test
    void aLongMessageIsCutButTheSuffixSurvives() {
        FakeTransport transport = new FakeTransport().then(answer(accepted(A)));

        NotificationOutcome outcome = notifier(transport).send(List.of(A), "x".repeat(900), "Reply NEDAMA 4821 to ack");

        assertTrue(transport.texts.get(0).endsWith("\nReply NEDAMA 4821 to ack"));
        assertEquals(2, outcome.segments());
    }

    @Test
    void prepareMessageWithASuffixMatchesWhatIsSent() {
        SmsNotifier notifier = notifier(new FakeTransport());
        assertEquals("Temp\nReply 4821 to ack", notifier.prepareMessage("Temp", "Reply 4821 to ack"));
    }

    @Test
    void aBlankMessageStillFailsEvenWithASuffix() {
        FakeTransport transport = new FakeTransport();

        NotificationOutcome outcome = notifier(transport).send(List.of(A), "  ", "Reply 4821 to ack");

        assertEquals(Status.FAILED, outcome.status());
        assertTrue(outcome.summary().contains("empty"));
        assertTrue(transport.calls.isEmpty());
    }

    // ------------------------------------------------------------------
    // Cost in the summary
    // ------------------------------------------------------------------

    @Test
    void theSummaryShowsWhatTheSmsCost() {
        FakeTransport transport = new FakeTransport().then(answer(accepted(A)));

        NotificationOutcome outcome = notifier(transport).send(List.of(A), "Alarm");

        assertEquals("SMS accepted for 1 recipient (cost KES 0.80)", outcome.summary());
    }

    @Test
    void theSummaryShowsTheCostAfterARetryToo() {
        FakeTransport transport = new FakeTransport()
                .then(transportError("Timed out", true))
                .then(answer(accepted(A)));

        NotificationOutcome outcome = notifier(transport).send(List.of(A), "Alarm");

        assertTrue(outcome.summary().contains("after retry; first attempt failed: Timed out; cost KES 0.80"));
    }

    @Test
    void aPartialSendShowsTheCostOfWhatWasAccepted() {
        FakeTransport transport = new FakeTransport().then(answer(
                accepted(A), failure(B, 406, "UserInBlacklist", Outcome.PERMANENT_FAILURE)));

        NotificationOutcome outcome = notifier(transport).send(List.of(A, B), "Alarm");

        assertTrue(outcome.summary().contains("1 of 2 recipients (cost KES 0.80)"));
    }

    // ------------------------------------------------------------------
    // Hourly limit
    // ------------------------------------------------------------------

    private SmsNotifier limited(FakeTransport transport, SendLimiter limiter) {
        return new SmsNotifier(transport, "254", 2, 0, ms -> { }, limiter);
    }

    @Test
    void sendsNormallyUnderTheLimit() {
        FakeTransport transport = new FakeTransport().then(answer(accepted(A)));
        SendLimiter limiter = SendLimiter.perHour(5);

        NotificationOutcome outcome = limited(transport, limiter).send(List.of(A), "Alarm");

        assertEquals(Status.SENT, outcome.status());
        assertEquals(1, limiter.used());
    }

    @Test
    void refusesWithoutCallingTheProviderWhenTheLimitIsReached() {
        FakeTransport transport = new FakeTransport()
                .then(answer(accepted(A)))
                .then(answer(accepted(A)));
        SendLimiter limiter = SendLimiter.perHour(2);
        SmsNotifier notifier = limited(transport, limiter);
        notifier.send(List.of(A), "one");
        notifier.send(List.of(A), "two");

        NotificationOutcome third = notifier.send(List.of(A), "three");

        assertEquals(Status.LIMITED, third.status());
        assertFalse(third.success());
        assertEquals(0, third.attempts());
        assertEquals(2, transport.calls.size()); // the third never reached the provider
        assertTrue(third.summary().contains("limit of 2 SMS per hour is reached"));
    }

    @Test
    void aLongMessageToSeveralPeopleUsesMoreOfTheAllowance() {
        FakeTransport transport = new FakeTransport().then(answer(accepted(A), accepted(B)));
        SendLimiter limiter = SendLimiter.perHour(10);

        // 2 segments (long text) to 2 people is 4 units
        limited(transport, limiter).send(List.of(A, B), "x".repeat(250));

        assertEquals(4, limiter.used());
    }

    @Test
    void aRequestThatDoesNotFitIsRefusedWholeNotPartly() {
        FakeTransport transport = new FakeTransport();
        SendLimiter limiter = SendLimiter.perHour(3);

        NotificationOutcome outcome = limited(transport, limiter).send(List.of(A, B), "x".repeat(250)); // needs 4

        assertEquals(Status.LIMITED, outcome.status());
        assertTrue(transport.calls.isEmpty());
        assertEquals(0, limiter.used());
    }

    @Test
    void aRefusedSendDoesNotUseUpTheAllowance() {
        FakeTransport transport = new FakeTransport()
                .then(answer(failure(A, 406, "UserInBlacklist", Outcome.PERMANENT_FAILURE)));
        SendLimiter limiter = SendLimiter.perHour(5);

        NotificationOutcome outcome = limited(transport, limiter).send(List.of(A), "Alarm");

        assertEquals(Status.FAILED, outcome.status());
        assertEquals(0, limiter.used());
    }

    @Test
    void aPartialSendUsesOnlyTheAcceptedShare() {
        FakeTransport transport = new FakeTransport().then(answer(
                accepted(A), failure(B, 406, "UserInBlacklist", Outcome.PERMANENT_FAILURE)));
        SendLimiter limiter = SendLimiter.perHour(5);

        limited(transport, limiter).send(List.of(A, B), "Alarm");

        assertEquals(1, limiter.used());
    }

    @Test
    void aRetryDoesNotCostTheAllowanceTwice() {
        FakeTransport transport = new FakeTransport()
                .then(transportError("Timed out", true))
                .then(answer(accepted(A)));
        SendLimiter limiter = SendLimiter.perHour(5);

        limited(transport, limiter).send(List.of(A), "Alarm");

        assertEquals(1, limiter.used());
    }

    @Test
    void nothingToSendDoesNotTouchTheLimiter() {
        FakeTransport transport = new FakeTransport();
        SendLimiter limiter = SendLimiter.perHour(5);

        limited(transport, limiter).send(List.of("rubbish"), "Alarm");

        assertEquals(0, limiter.used());
    }

    @Test
    void limitedIsAFailureForTheAlarmPipeline() {
        NotificationOutcome limited = new NotificationOutcome(Status.LIMITED, "x", "m", 1, List.of(), 0, 0);
        assertFalse(limited.success());
        assertTrue(new NotificationOutcome(Status.PARTIAL, "x", "m", 1, List.of(), 0, 1).success());
    }

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    @Test
    void rejectsBadSettings() {
        FakeTransport transport = new FakeTransport();
        assertThrows(NullPointerException.class, () -> new SmsNotifier(null, "254"));
        assertThrows(IllegalArgumentException.class, () -> new SmsNotifier(transport, "+254"));
        assertThrows(IllegalArgumentException.class, () -> new SmsNotifier(transport, ""));
        assertThrows(IllegalArgumentException.class, () -> new SmsNotifier(transport, "254", 0, 0, ms -> { }));
        assertThrows(IllegalArgumentException.class, () -> new SmsNotifier(transport, "254", 1, -1, ms -> { }));
    }

    @Test
    void outcomeCopiesItsResults() {
        List<RecipientResult> mutable = new ArrayList<>(List.of(accepted(A)));
        NotificationOutcome outcome = new NotificationOutcome(Status.SENT, "ok", "m", 1, mutable, 0, 1);
        mutable.clear();
        assertEquals(1, outcome.results().size());
    }
}
