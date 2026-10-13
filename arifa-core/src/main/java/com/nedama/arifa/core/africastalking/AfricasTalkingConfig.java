package com.nedama.arifa.core.africastalking;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * Settings for one Africa's Talking account.
 *
 * @param username        the AT app username; always "sandbox" in the sandbox
 * @param apiKey          the AT API key for that app (a secret: never log it)
 * @param environment     sandbox or live
 * @param senderId        optional registered sender ID or shortcode; blank means AT's default
 * @param requestTimeout  how long to wait for AT before giving up; null means 10 seconds
 * @param baseUriOverride for tests only; null in real use
 */
public record AfricasTalkingConfig(
        String username,
        String apiKey,
        AfricasTalkingEnvironment environment,
        String senderId,
        Duration requestTimeout,
        URI baseUriOverride) {

    public static final String SANDBOX_USERNAME = "sandbox";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    public AfricasTalkingConfig {
        username = requireText(username, "username");
        apiKey = requireText(apiKey, "API key");
        Objects.requireNonNull(environment, "environment");
        if (environment == AfricasTalkingEnvironment.SANDBOX && !SANDBOX_USERNAME.equals(username)) {
            throw new IllegalArgumentException("The Africa's Talking sandbox username is always 'sandbox'");
        }
        senderId = (senderId == null || senderId.isBlank()) ? null : senderId.trim();
        requestTimeout = requestTimeout == null ? DEFAULT_TIMEOUT : requestTimeout;
        if (requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("Request timeout must be positive");
        }
    }

    /** Sandbox settings with AT's default sender. */
    public static AfricasTalkingConfig sandbox(String apiKey) {
        return new AfricasTalkingConfig(SANDBOX_USERNAME, apiKey, AfricasTalkingEnvironment.SANDBOX, null, null, null);
    }

    /** Live settings. Pass null as senderId to use AT's default sender. */
    public static AfricasTalkingConfig live(String username, String apiKey, String senderId) {
        return new AfricasTalkingConfig(username, apiKey, AfricasTalkingEnvironment.LIVE, senderId, null, null);
    }

    /** The SMS endpoint, used both to send and to fetch the inbox. */
    public URI messagingUri() {
        URI base = baseUriOverride != null ? baseUriOverride : environment.baseUri();
        return base.resolve("/version1/messaging");
    }

    /** Never prints the API key. */
    @Override
    public String toString() {
        return "AfricasTalkingConfig[username=" + username
                + ", apiKey=****"
                + ", environment=" + environment
                + ", senderId=" + senderId
                + ", requestTimeout=" + requestTimeout + "]";
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Africa's Talking " + name + " is required");
        }
        return value.trim();
    }
}
