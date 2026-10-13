package com.nedama.arifa.core.ack;

/**
 * Turns "N alarm events were sent to Ignition, M of them could not be acknowledged" into the wording
 * for the log and for the optional confirmation SMS.
 *
 * <p>Kept free of Ignition classes so the decisions can be tested here. The Ignition adapter only
 * counts the events and asks this class what to say.</p>
 */
public final class AckReport {

    private AckReport() {
    }

    /**
     * The result of one acknowledgement.
     *
     * @param total           how many alarm events the SMS was about
     * @param notAcknowledged how many of them Ignition could not acknowledge, usually because they were already
     *                        acknowledged or are no longer active
     * @param userLabel       who the events were acknowledged as, for the log
     * @return success when at least one event was acknowledged
     */
    public static AckOutcome outcome(int total, int notAcknowledged, String userLabel) {
        if (total <= 0) {
            return new AckOutcome(false, "no alarm events were attached to this code");
        }
        int failed = clamp(notAcknowledged, total);
        int done = total - failed;
        if (done == 0) {
            return new AckOutcome(false, "none of the " + total + " alarm event(s) could be acknowledged, "
                    + "usually because they were already acknowledged or are no longer active");
        }
        if (failed == 0) {
            return new AckOutcome(true, total + " alarm event(s) acknowledged as " + userLabel);
        }
        return new AckOutcome(true, done + " of " + total + " alarm events acknowledged as " + userLabel + "; "
                + failed + " could not be (already acknowledged or no longer active)");
    }

    /** The short SMS sent back to the operator when confirmations are on. Plain GSM characters only. */
    public static String confirmationText(int total, int notAcknowledged) {
        if (total <= 0) {
            return "Could not acknowledge. Check Ignition.";
        }
        int failed = clamp(notAcknowledged, total);
        int done = total - failed;
        if (done == 0) {
            return "Could not acknowledge: it may already be acknowledged or cleared. Check Ignition.";
        }
        if (failed == 0) {
            return total == 1 ? "Alarm acknowledged. Thank you." : total + " alarms acknowledged. Thank you.";
        }
        return "Acknowledged " + done + " of " + total + " alarms. The rest may already be acknowledged.";
    }

    /** The text for an unexpected error while acknowledging. */
    public static String errorConfirmationText() {
        return "Could not acknowledge. Please acknowledge it in Ignition.";
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(value, max));
    }
}
