package com.nedama.arifa.core.notify;

import com.nedama.arifa.core.sms.Outcome;
import com.nedama.arifa.core.sms.RecipientResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CostSummaryTest {

    private static RecipientResult accepted(String cost) {
        return new RecipientResult("+254712000001", Outcome.ACCEPTED, 101, "Sent", "id", cost);
    }

    private static RecipientResult refused() {
        return new RecipientResult("+254712000002", Outcome.PERMANENT_FAILURE, 406, "UserInBlacklist", null, null);
    }

    @Test
    void oneSmsInKes() {
        CostSummary summary = CostSummary.of(List.of(accepted("KES 0.8000")));
        assertEquals("KES 0.80", summary.describe());
        assertTrue(summary.onlyCurrency("KES"));
        assertEquals(0, new BigDecimal("0.80").compareTo(summary.total("kes")));
    }

    @Test
    void addsRecipientsAndKeepsTheFormatTidy() {
        assertEquals("KES 1.60", CostSummary.of(List.of(accepted("KES 0.8000"), accepted("KES 0.8000"))).describe());
        assertEquals("KES 12.50", CostSummary.of(List.of(accepted("KES 12.5"))).describe());
        assertEquals("KES 0.0075", CostSummary.of(List.of(accepted("KES 0.0075"))).describe());
        assertEquals("KES 2.00", CostSummary.of(List.of(accepted("KES 2"))).describe());
    }

    @Test
    void doesNotCountRefusedRecipients() {
        CostSummary summary = CostSummary.of(List.of(accepted("KES 0.8000"), refused()));
        assertEquals("KES 0.80", summary.describe());
        assertEquals(0, summary.unreported());
    }

    @Test
    void aForeignCurrencyIsReportedAndFlagged() {
        CostSummary summary = CostSummary.of(List.of(accepted("KES 0.8000"), accepted("USD 0.2200")));

        assertEquals("KES 0.80 + USD 0.22", summary.describe());
        assertFalse(summary.onlyCurrency("KES"));
        assertTrue(summary.currencies().contains("USD"));
    }

    @Test
    void currencyChecksIgnoreCaseAndBlank() {
        CostSummary summary = CostSummary.of(List.of(accepted("kes 0.80")));
        assertTrue(summary.onlyCurrency(" kes "));
        assertTrue(summary.onlyCurrency(null));
        assertTrue(summary.onlyCurrency(""));
    }

    @Test
    void missingOrUnreadableCostsAreCountedNotGuessed() {
        CostSummary summary = CostSummary.of(List.of(accepted(null), accepted("free"), accepted("")));

        assertTrue(summary.isEmpty());
        assertEquals(3, summary.unreported());
        assertEquals("cost not reported", summary.describe());
        assertTrue(summary.onlyCurrency("KES")); // nothing to object to
    }

    @Test
    void mixOfReadableAndUnreadable() {
        CostSummary summary = CostSummary.of(List.of(accepted("KES 0.8000"), accepted(null)));
        assertEquals("KES 0.80", summary.describe());
        assertEquals(1, summary.unreported());
    }
}
