package com.nedama.arifa.core.sms;

import com.nedama.arifa.core.text.PhoneNumbers;

/**
 * The provider's answer for one recipient of an outgoing SMS.
 *
 * @param number             the recipient in +E.164 format, as reported by the provider
 * @param outcome            how Arifa classifies the result
 * @param providerStatusCode the provider's numeric status code, or -1 if none was given
 * @param providerStatus     the provider's status name, for logs and audit records
 * @param messageId          the provider's message ID, if any
 * @param cost               the provider's cost string, if any (for example "KES 0.8000")
 */
public record RecipientResult(
        String number,
        Outcome outcome,
        int providerStatusCode,
        String providerStatus,
        String messageId,
        String cost) {

    public boolean accepted() {
        return outcome == Outcome.ACCEPTED;
    }

    /** Masks the phone number so this is safe to write to gateway logs. */
    @Override
    public String toString() {
        return "RecipientResult[number=" + PhoneNumbers.mask(number)
                + ", outcome=" + outcome
                + ", providerStatusCode=" + providerStatusCode
                + ", providerStatus=" + providerStatus
                + ", messageId=" + messageId
                + ", cost=" + cost + "]";
    }
}
