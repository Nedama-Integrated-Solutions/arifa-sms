package com.nedama.arifa.core.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GsmTextTest {

    @Test
    void replacesCommonPlantSymbols() {
        assertEquals("Tank T-101 temp 85degC - HIGH", GsmText.sanitize("Tank T-101 temp 85°C – HIGH"));
        assertEquals("Flow 3.5 m3/h +/- 0.2", GsmText.sanitize("Flow 3.5 m³/h ± 0.2"));
        assertEquals("Conductivity 120 uS/cm", GsmText.sanitize("Conductivity 120 µS/cm"));
        assertEquals("\"Pump\" isn't running...", GsmText.sanitize("“Pump” isn’t running…"));
    }

    @Test
    void keepsGsmAccentsAndStripsOthers() {
        assertEquals("Café Résumé", GsmText.sanitize("Café Résumé"));
        assertEquals("Sao Tome", GsmText.sanitize("São Tome"));
        assertEquals("naive", GsmText.sanitize("naïve"));
    }

    @Test
    void replacesUnknownCharactersAndEmoji() {
        assertEquals("Alarm ? active", GsmText.sanitize("Alarm \uD83D\uDD25 active"));
        assertEquals("Line ?", GsmText.sanitize("Line \u4E00"));
    }

    @Test
    void normalisesLineEndingsAndTrailingSpace() {
        assertEquals("a\nb\nc", GsmText.sanitize("a\r\nb\rc  \n "));
        assertEquals("", GsmText.sanitize(null));
    }

    @Test
    void sanitizedTextIsAlwaysGsm() {
        String messy = "T=85°C “hi” 😀 ã ½ ≥ 3 \t tab";
        assertFalse(GsmText.isGsm7(messy));
        assertTrue(GsmText.isGsm7(GsmText.sanitize(messy)));
    }

    @Test
    void countsSegments() {
        assertEquals(0, GsmText.segmentCount(""));
        assertEquals(1, GsmText.segmentCount("a".repeat(160)));
        assertEquals(2, GsmText.segmentCount("a".repeat(161)));
        assertEquals(2, GsmText.segmentCount("a".repeat(306)));
        assertEquals(3, GsmText.segmentCount("a".repeat(307)));
    }

    @Test
    void extensionCharactersCountDouble() {
        assertEquals(160, GsmText.septetLength("{".repeat(80)));
        assertEquals(1, GsmText.segmentCount("{".repeat(80)));
        assertEquals(2, GsmText.segmentCount("{".repeat(81)));
    }

    @Test
    void unicodeTextUsesSmallerSegments() {
        assertEquals(1, GsmText.segmentCount("°".repeat(70)));
        assertEquals(2, GsmText.segmentCount("°".repeat(71)));
    }

    @Test
    void truncatesToSegmentBudget() {
        String longText = "x".repeat(400);
        String one = GsmText.truncateToSegments(longText, 1);
        assertEquals(160, one.length());
        assertTrue(one.endsWith("..."));
        String two = GsmText.truncateToSegments(longText, 2);
        assertEquals(306, two.length());
        assertEquals(2, GsmText.segmentCount(two));
        assertEquals("short", GsmText.truncateToSegments("short", 1));
        assertThrows(IllegalArgumentException.class, () -> GsmText.truncateToSegments("x", 0));
    }

    @Test
    void truncationRespectsDoubleWidthCharacters() {
        String result = GsmText.truncateToSegments("[".repeat(100), 1);
        assertTrue(GsmText.septetLength(result) <= 160);
        assertTrue(result.endsWith("..."));
    }

    @Test
    void keepsTheSuffixWholeAndOnItsOwnLine() {
        String result = GsmText.truncateToSegments("Pump P-101 tripped", 2, "Reply NEDAMA 4821 to ack");
        assertEquals("Pump P-101 tripped\nReply NEDAMA 4821 to ack", result);
        assertEquals(1, GsmText.segmentCount(result));
    }

    @Test
    void shortensOnlyTheMessageToMakeRoomForTheSuffix() {
        String suffix = "Reply NEDAMA 4821 to ack";
        String result = GsmText.truncateToSegments("x".repeat(900), 2, suffix);

        assertTrue(result.endsWith("\n" + suffix));
        assertTrue(result.contains("...\n"));
        assertTrue(GsmText.septetLength(result) <= 306);
        assertEquals(2, GsmText.segmentCount(result));
        // and it is full: the message part uses everything the suffix leaves free
        assertEquals(306, GsmText.septetLength(result));
    }

    @Test
    void suffixLimitsApplyToASingleSegmentToo() {
        String suffix = "Reply 4821 to ack";
        String result = GsmText.truncateToSegments("y".repeat(400), 1, suffix);

        assertTrue(result.endsWith("\n" + suffix));
        assertEquals(160, GsmText.septetLength(result));
        assertEquals(1, GsmText.segmentCount(result));
    }

    @Test
    void suffixIsCleanedLikeTheMessage() {
        String result = GsmText.truncateToSegments("Temp 85\u00b0C", 1, "Reply \u201cNEDAMA\u201d 4821");
        assertEquals("Temp 85degC\nReply \"NEDAMA\" 4821", result);
    }

    @Test
    void aBlankSuffixBehavesLikeNoSuffix() {
        assertEquals(GsmText.truncateToSegments("hello", 1), GsmText.truncateToSegments("hello", 1, null));
        assertEquals(GsmText.truncateToSegments("hello", 1), GsmText.truncateToSegments("hello", 1, "  "));
    }

    @Test
    void aBlankMessageGivesJustTheSuffix() {
        assertEquals("Reply 4821 to ack", GsmText.truncateToSegments("  ", 2, "Reply 4821 to ack"));
    }

    @Test
    void aSuffixThatCannotFitIsStillCutToTheLimit() {
        String result = GsmText.truncateToSegments("msg", 1, "z".repeat(400));
        assertTrue(GsmText.septetLength(result) <= 160);
    }

    @Test
    void suffixHandlesDoubleWidthCharactersInTheMessage() {
        String result = GsmText.truncateToSegments("[".repeat(200), 1, "Reply 4821 to ack");
        assertTrue(GsmText.septetLength(result) <= 160);
        assertTrue(result.endsWith("\nReply 4821 to ack"));
    }
}
