package com.nedama.arifa.core.notify;

import com.nedama.arifa.core.sms.RecipientResult;

import java.util.List;
import java.util.Objects;

/**
 * What happened when Arifa tried to notify one user about an alarm.
 *
 * @param status          overall result
 * @param summary         one line for logs, audit records and the pipeline status area.
 *                        Phone numbers in it are masked and it never contains credentials.
 * @param message         the cleaned text that was sent, or would have been sent
 * @param segments        how many SMS the message is billed as
 * @param results         the provider's answer for each recipient, if it answered
 * @param rejectedNumbers how many of the user's numbers were unusable and skipped
 * @param attempts        how many times the provider was called (0 if nothing could be sent)
 */
public record NotificationOutcome(
        Status status,
        String summary,
        String message,
        int segments,
        List<RecipientResult> results,
        int rejectedNumbers,
        int attempts) {

    /** Overall result of a notification. */
    public enum Status {
        /** Every recipient was accepted by the provider. */
        SENT,
        /** At least one recipient was accepted and at least one was not. */
        PARTIAL,
        /** No recipient was accepted. The alarm pipeline should use its fallback. */
        FAILED,
        /** Nothing was sent because the hourly SMS limit was reached. Treated as a failure, so the fallback is used. */
        LIMITED
    }

    public NotificationOutcome {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(summary, "summary");
        message = message == null ? "" : message;
        results = List.copyOf(results);
    }

    /**
     * True when at least one recipient was accepted. The Ignition side calls
     * {@code notificationDone()} for success and {@code notificationFailed()} otherwise.
     */
    public boolean success() {
        return status == Status.SENT || status == Status.PARTIAL;
    }
}
