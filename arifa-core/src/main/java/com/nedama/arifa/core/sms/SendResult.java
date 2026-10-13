package com.nedama.arifa.core.sms;

import java.util.List;

/**
 * The result of one send call, covering every requested recipient.
 *
 * @param recipients      one entry per requested recipient
 * @param providerSummary the provider's summary line, if any (for example "Sent to 2/2 Total Cost: KES 1.6000")
 */
public record SendResult(List<RecipientResult> recipients, String providerSummary) {

    public SendResult {
        recipients = List.copyOf(recipients);
    }

    /** True when every recipient was accepted. */
    public boolean allAccepted() {
        return !recipients.isEmpty() && recipients.stream().allMatch(RecipientResult::accepted);
    }

    /** True when at least one recipient was accepted. */
    public boolean anyAccepted() {
        return recipients.stream().anyMatch(RecipientResult::accepted);
    }

    /** True when at least one failure is temporary and worth a retry. */
    public boolean hasRetryableFailure() {
        return recipients.stream().anyMatch(r -> r.outcome() == Outcome.RETRYABLE_FAILURE);
    }

    /** All recipients that were not accepted. */
    public List<RecipientResult> failures() {
        return recipients.stream().filter(r -> !r.accepted()).toList();
    }
}
