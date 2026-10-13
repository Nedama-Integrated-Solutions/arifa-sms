package com.nedama.arifa.core.sms;

/**
 * The whole request failed, so no recipient result is available.
 *
 * <p>Examples: the network is down, the credentials are wrong, or the provider returned
 * something unexpected. Per-recipient problems (an opted-out number, for example) are
 * reported in {@link SendResult} instead.</p>
 */
public class SmsTransportException extends Exception {

    private static final long serialVersionUID = 1L;

    private final boolean retryable;
    private final int httpStatus;

    public SmsTransportException(String message, boolean retryable, int httpStatus, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.httpStatus = httpStatus;
    }

    /** True when a retry might succeed (network trouble, provider outage). */
    public boolean isRetryable() {
        return retryable;
    }

    /** The HTTP status returned by the provider, or -1 if the request never got a response. */
    public int getHttpStatus() {
        return httpStatus;
    }
}
