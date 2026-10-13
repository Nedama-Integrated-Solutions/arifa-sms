package com.nedama.arifa.core.sms;

import java.util.List;

/**
 * A way of sending SMS and, optionally, reading replies.
 *
 * <p>Africa's Talking is the first implementation. A LAN cellular router (Teltonika) can be
 * added later behind the same interface, so the Ignition side of Arifa never changes.</p>
 *
 * <p>Implementations must be safe to call from several threads at once.</p>
 */
public interface SmsTransport {

    /** A short name for logs and status pages, for example "Africa's Talking". */
    String name();

    /**
     * Sends one message to one or more recipients.
     *
     * @param recipients numbers in +E.164 format (use {@code PhoneNumbers.toE164} first)
     * @param text       the message, already cleaned with {@code GsmText.sanitize}
     * @return one result per unique recipient
     * @throws SmsTransportException if the request as a whole failed
     */
    SendResult send(List<String> recipients, String text) throws SmsTransportException;

    /** True if {@link #fetchInbound(long)} is supported, which two-way acknowledgement needs. */
    boolean supportsInbound();

    /**
     * Returns messages received after the given ID, oldest first.
     *
     * @param afterId only messages with a larger ID are returned; use 0 to fetch from the start
     * @throws SmsTransportException if the request failed
     */
    List<InboundSms> fetchInbound(long afterId) throws SmsTransportException;
}
