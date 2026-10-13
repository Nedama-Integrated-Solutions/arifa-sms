package com.nedama.arifa.core.ack;

import com.nedama.arifa.core.text.GsmText;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AckReportTest {

    private static final String WHO = "usr-prov:default:/usr:arifa.test";

    @Test
    void everythingAcknowledged() {
        AckOutcome outcome = AckReport.outcome(3, 0, WHO);
        assertTrue(outcome.success());
        assertEquals("3 alarm event(s) acknowledged as " + WHO, outcome.summary());
        assertEquals("Alarm acknowledged. Thank you.", AckReport.confirmationText(1, 0));
        assertEquals("3 alarms acknowledged. Thank you.", AckReport.confirmationText(3, 0));
    }

    @Test
    void someAcknowledgedCountsAsSuccessAndSaysWhatHappenedToTheRest() {
        AckOutcome outcome = AckReport.outcome(3, 1, WHO);
        assertTrue(outcome.success());
        assertTrue(outcome.summary().contains("2 of 3 alarm events acknowledged"));
        assertTrue(outcome.summary().contains("1 could not be"));
        assertEquals("Acknowledged 2 of 3 alarms. The rest may already be acknowledged.",
                AckReport.confirmationText(3, 1));
    }

    @Test
    void nothingAcknowledgedIsAFailure() {
        AckOutcome outcome = AckReport.outcome(1, 1, WHO);
        assertFalse(outcome.success());
        assertTrue(outcome.summary().contains("none of the 1 alarm event(s)"));
        assertTrue(AckReport.confirmationText(1, 1).startsWith("Could not acknowledge"));
        assertFalse(AckReport.outcome(3, 3, WHO).success());
    }

    @Test
    void noEventsIsAFailure() {
        assertFalse(AckReport.outcome(0, 0, WHO).success());
        assertTrue(AckReport.confirmationText(0, 0).startsWith("Could not acknowledge"));
    }

    @Test
    void anImpossibleFailureCountIsClamped() {
        assertFalse(AckReport.outcome(2, 5, WHO).success());
        assertTrue(AckReport.outcome(2, -4, WHO).success());
        assertEquals("2 alarms acknowledged. Thank you.", AckReport.confirmationText(2, -4));
    }

    @Test
    void confirmationTextsStayInTheGsmAlphabetAndInOneSms() {
        String[] texts = {
                AckReport.confirmationText(1, 0), AckReport.confirmationText(12, 0),
                AckReport.confirmationText(5, 2), AckReport.confirmationText(2, 2),
                AckReport.confirmationText(0, 0), AckReport.errorConfirmationText()};
        for (String text : texts) {
            assertTrue(GsmText.isGsm7(text), text);
            assertEquals(1, GsmText.segmentCount(text), text);
        }
    }
}
