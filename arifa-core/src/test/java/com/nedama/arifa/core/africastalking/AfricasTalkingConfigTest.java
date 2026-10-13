package com.nedama.arifa.core.africastalking;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AfricasTalkingConfigTest {

    @Test
    void sandboxDefaults() {
        AfricasTalkingConfig config = AfricasTalkingConfig.sandbox("key-123");
        assertEquals("sandbox", config.username());
        assertEquals(AfricasTalkingConfig.DEFAULT_TIMEOUT, config.requestTimeout());
        assertNull(config.senderId());
        assertEquals(URI.create("https://api.sandbox.africastalking.com/version1/messaging"), config.messagingUri());
    }

    @Test
    void liveEndpoint() {
        AfricasTalkingConfig config = AfricasTalkingConfig.live("nedama_client", "key-123", " NEDAMA ");
        assertEquals(URI.create("https://api.africastalking.com/version1/messaging"), config.messagingUri());
        assertEquals("NEDAMA", config.senderId());
    }

    @Test
    void blankSenderIdMeansDefault() {
        assertNull(AfricasTalkingConfig.live("app", "key", "   ").senderId());
    }

    @Test
    void sandboxRequiresSandboxUsername() {
        assertThrows(IllegalArgumentException.class, () -> new AfricasTalkingConfig(
                "myapp", "key", AfricasTalkingEnvironment.SANDBOX, null, null, null));
    }

    @Test
    void requiresUsernameAndKey() {
        assertThrows(IllegalArgumentException.class, () -> AfricasTalkingConfig.live("", "key", null));
        assertThrows(IllegalArgumentException.class, () -> AfricasTalkingConfig.live("app", " ", null));
        assertThrows(IllegalArgumentException.class, () -> AfricasTalkingConfig.sandbox(null));
    }

    @Test
    void rejectsNonPositiveTimeout() {
        assertThrows(IllegalArgumentException.class, () -> new AfricasTalkingConfig(
                "sandbox", "key", AfricasTalkingEnvironment.SANDBOX, null, Duration.ZERO, null));
    }

    @Test
    void toStringNeverShowsTheApiKey() {
        AfricasTalkingConfig config = AfricasTalkingConfig.live("app", "super-secret-key", "NEDAMA");
        assertFalse(config.toString().contains("super-secret-key"));
    }
}
