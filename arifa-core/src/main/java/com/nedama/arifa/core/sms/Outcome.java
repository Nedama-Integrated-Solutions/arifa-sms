package com.nedama.arifa.core.sms;

/**
 * What happened to one recipient of an outgoing SMS.
 */
public enum Outcome {

    /** The provider accepted the message for delivery. This is not proof it reached the handset. */
    ACCEPTED,

    /** A temporary problem (network, provider outage). Worth one retry before failing over. */
    RETRYABLE_FAILURE,

    /** Retrying will not help (bad number, opted out, no balance, bad credentials). Fail over now. */
    PERMANENT_FAILURE
}
