package com.nedama.arifa.core.africastalking;

import com.nedama.arifa.core.sms.Outcome;

import java.util.Map;

/**
 * Africa's Talking per-recipient status codes from the send response, and how Arifa treats them.
 *
 * <p>Note: "accepted" only means AT handed the message to the mobile network.
 * Final handset delivery is reported separately by AT through delivery-report callbacks.</p>
 */
public final class AfricasTalkingStatus {

    private static final Map<Integer, String> NAMES = Map.ofEntries(
            Map.entry(100, "Processed"),
            Map.entry(101, "Sent"),
            Map.entry(102, "Queued"),
            Map.entry(401, "RiskHold"),
            Map.entry(402, "InvalidSenderId"),
            Map.entry(403, "InvalidPhoneNumber"),
            Map.entry(404, "UnsupportedNumberType"),
            Map.entry(405, "InsufficientBalance"),
            Map.entry(406, "UserInBlacklist"),
            Map.entry(407, "CouldNotRoute"),
            Map.entry(409, "DoNotDisturbRejection"),
            Map.entry(500, "InternalServerError"),
            Map.entry(501, "GatewayError"),
            Map.entry(502, "RejectedByGateway"));

    private AfricasTalkingStatus() {
    }

    /**
     * 100 to 102 are accepted. 500 and 501 are provider-side faults worth one retry.
     * Everything else, including unknown codes, fails over immediately: for alarms it is
     * safer to escalate than to keep retrying something we do not understand.
     */
    public static Outcome classify(int statusCode) {
        if (statusCode >= 100 && statusCode <= 102) {
            return Outcome.ACCEPTED;
        }
        if (statusCode == 500 || statusCode == 501) {
            return Outcome.RETRYABLE_FAILURE;
        }
        return Outcome.PERMANENT_FAILURE;
    }

    /** The AT name for a code, for example 406 gives "UserInBlacklist". */
    public static String name(int statusCode) {
        return NAMES.getOrDefault(statusCode, "Unknown(" + statusCode + ")");
    }

    /** True if Arifa knows this code. */
    public static boolean isKnown(int statusCode) {
        return NAMES.containsKey(statusCode);
    }
}
