package com.nedama.arifa.core.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhoneNumbersTest {

    private static final String KE = PhoneNumbers.KENYA;

    @Test
    void convertsCommonKenyanFormats() {
        assertEquals("+254712345678", PhoneNumbers.toE164("0712345678", KE));
        assertEquals("+254712345678", PhoneNumbers.toE164("0712 345 678", KE));
        assertEquals("+254712345678", PhoneNumbers.toE164(" +254 712-345-678 ", KE));
        assertEquals("+254712345678", PhoneNumbers.toE164("254712345678", KE));
        assertEquals("+254712345678", PhoneNumbers.toE164("712345678", KE));
        assertEquals("+254712345678", PhoneNumbers.toE164("00254712345678", KE));
        assertEquals("+254712345678", PhoneNumbers.toE164("(0712) 345.678", KE));
    }

    @Test
    void acceptsNewerOneHundredSeriesNumbers() {
        assertEquals("+254110123456", PhoneNumbers.toE164("0110123456", KE));
        assertEquals("+254100123456", PhoneNumbers.toE164("+254100123456", KE));
    }

    @Test
    void keepsForeignNumbers() {
        assertEquals("+447911123456", PhoneNumbers.toE164("+44 7911 123456", KE));
        assertEquals("+256772123456", PhoneNumbers.toE164("00256772123456", KE));
    }

    @Test
    void rejectsInvalidNumbers() {
        assertThrows(IllegalArgumentException.class, () -> PhoneNumbers.toE164(null, KE));
        assertThrows(IllegalArgumentException.class, () -> PhoneNumbers.toE164("", KE));
        assertThrows(IllegalArgumentException.class, () -> PhoneNumbers.toE164("   ", KE));
        assertThrows(IllegalArgumentException.class, () -> PhoneNumbers.toE164("12345", KE));
        assertThrows(IllegalArgumentException.class, () -> PhoneNumbers.toE164("07123", KE));
        assertThrows(IllegalArgumentException.class, () -> PhoneNumbers.toE164("not a number", KE));
        // Kenyan landline, not a mobile, cannot receive SMS alarms
        assertThrows(IllegalArgumentException.class, () -> PhoneNumbers.toE164("0202345678", KE));
        assertThrows(IllegalArgumentException.class, () -> PhoneNumbers.toE164("+254812345678", KE));
    }

    @Test
    void rejectsBadCountryCode() {
        assertThrows(IllegalArgumentException.class, () -> PhoneNumbers.toE164("0712345678", null));
        assertThrows(IllegalArgumentException.class, () -> PhoneNumbers.toE164("0712345678", "+254"));
        assertThrows(IllegalArgumentException.class, () -> PhoneNumbers.toE164("0712345678", "2540"));
    }

    @Test
    void validatesCountryCodes() {
        assertTrue(PhoneNumbers.isValidCountryCode("254"));
        assertTrue(PhoneNumbers.isValidCountryCode("1"));
        assertFalse(PhoneNumbers.isValidCountryCode("+254"));
        assertFalse(PhoneNumbers.isValidCountryCode("0254"));
        assertFalse(PhoneNumbers.isValidCountryCode("2540"));
        assertFalse(PhoneNumbers.isValidCountryCode(""));
        assertFalse(PhoneNumbers.isValidCountryCode(null));
    }

    @Test
    void errorMessagesDoNotLeakTheFullNumber() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PhoneNumbers.toE164("+254812345678", KE));
        assertFalse(e.getMessage().contains("812345678"));
    }

    @Test
    void tryVariantReturnsEmptyInsteadOfThrowing() {
        assertTrue(PhoneNumbers.tryToE164("0712345678", KE).isPresent());
        assertTrue(PhoneNumbers.tryToE164("rubbish", KE).isEmpty());
    }

    @Test
    void masksNumbersForLogs() {
        assertEquals("+254712***678", PhoneNumbers.mask("+254712345678"));
        assertEquals("(none)", PhoneNumbers.mask(null));
        assertEquals("***", PhoneNumbers.mask("12345"));
        assertEquals("07***23", PhoneNumbers.mask("07123423"));
    }
}
