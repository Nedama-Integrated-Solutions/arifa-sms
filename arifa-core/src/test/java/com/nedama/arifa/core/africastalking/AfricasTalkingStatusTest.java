package com.nedama.arifa.core.africastalking;

import com.nedama.arifa.core.sms.Outcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AfricasTalkingStatusTest {

    @Test
    void acceptedCodes() {
        assertEquals(Outcome.ACCEPTED, AfricasTalkingStatus.classify(100));
        assertEquals(Outcome.ACCEPTED, AfricasTalkingStatus.classify(101));
        assertEquals(Outcome.ACCEPTED, AfricasTalkingStatus.classify(102));
    }

    @Test
    void retryableCodes() {
        assertEquals(Outcome.RETRYABLE_FAILURE, AfricasTalkingStatus.classify(500));
        assertEquals(Outcome.RETRYABLE_FAILURE, AfricasTalkingStatus.classify(501));
    }

    @Test
    void permanentCodesIncludingOptOutAndUnknown() {
        for (int code : new int[] {401, 402, 403, 404, 405, 406, 407, 409, 502, 999, 0}) {
            assertEquals(Outcome.PERMANENT_FAILURE, AfricasTalkingStatus.classify(code), "code " + code);
        }
    }

    @Test
    void namesCodes() {
        assertEquals("UserInBlacklist", AfricasTalkingStatus.name(406));
        assertEquals("Unknown(999)", AfricasTalkingStatus.name(999));
        assertTrue(AfricasTalkingStatus.isKnown(101));
        assertFalse(AfricasTalkingStatus.isKnown(999));
    }
}
