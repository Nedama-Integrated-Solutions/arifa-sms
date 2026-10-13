package com.nedama.arifa.core.africastalking;

import java.net.URI;

/**
 * Which Africa's Talking system to talk to.
 */
public enum AfricasTalkingEnvironment {

    /** Free test system. Messages appear in the AT simulator, not on real phones. Username is always "sandbox". */
    SANDBOX(URI.create("https://api.sandbox.africastalking.com")),

    /** Real system. Messages go to real phones and cost money. */
    LIVE(URI.create("https://api.africastalking.com"));

    private final URI baseUri;

    AfricasTalkingEnvironment(URI baseUri) {
        this.baseUri = baseUri;
    }

    public URI baseUri() {
        return baseUri;
    }
}
