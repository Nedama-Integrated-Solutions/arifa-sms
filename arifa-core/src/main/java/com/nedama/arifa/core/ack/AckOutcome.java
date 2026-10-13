package com.nedama.arifa.core.ack;

/**
 * What happened when a valid reply was turned into an acknowledgement.
 *
 * @param success true if the alarm events were acknowledged
 * @param summary one line for logs; never contains credentials
 */
public record AckOutcome(boolean success, String summary) {
}
