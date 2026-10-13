package com.nedama.arifa.core.ack;

/**
 * Does the real acknowledging when a valid reply has been matched.
 * The Ignition adapter implements this by calling the alarm manager.
 *
 * @param <A> the attachment type used with {@link AckRegistry}
 */
@FunctionalInterface
public interface AckHandler<A> {

    /** Acknowledges what the claim stands for. Should not throw; report problems in the outcome. */
    AckOutcome acknowledge(AckRegistry.Claim<A> claim);
}
