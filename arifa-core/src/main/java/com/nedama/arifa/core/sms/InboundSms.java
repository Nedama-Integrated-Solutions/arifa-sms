package com.nedama.arifa.core.sms;

import com.nedama.arifa.core.text.PhoneNumbers;

/**
 * An SMS received from a phone, for example an operator replying to acknowledge an alarm.
 *
 * @param id         the provider's message ID; increases over time, used to fetch only newer messages
 * @param from       the sender's number in +E.164 format
 * @param to         the shortcode or number the message was sent to
 * @param text       the message body as typed by the sender
 * @param receivedAt the provider's timestamp text
 * @param linkId     the provider's link ID (used for premium services; usually not needed)
 */
public record InboundSms(long id, String from, String to, String text, String receivedAt, String linkId) {

    /** Masks the sender's number so this is safe to write to gateway logs. */
    @Override
    public String toString() {
        return "InboundSms[id=" + id
                + ", from=" + PhoneNumbers.mask(from)
                + ", to=" + to
                + ", text=" + text
                + ", receivedAt=" + receivedAt + "]";
    }
}
