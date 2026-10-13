package com.nedama.arifa.core.ack;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReplyParserTest {

    private final ReplyParser parser = new ReplyParser("NEDAMA");

    private void accepts(String text) {
        assertEquals(Optional.of("4821"), parser.parseCode(text), "should accept: [" + text + "]");
    }

    private void rejects(String text) {
        assertEquals(Optional.empty(), parser.parseCode(text), "should reject: [" + text + "]");
    }

    @Test
    void acceptsTheDocumentedFormats() {
        accepts("NEDAMA 4821");
        accepts("nedama 4821");
        accepts("Nedama4821");
        accepts("NEDAMA: 4821");
        accepts("NEDAMA-4821");
        accepts("NEDAMA #4821");
        accepts("ACK 4821");
        accepts("ack4821");
        accepts("4821");
    }

    @Test
    void toleratesSpacesAndTrailingPunctuation() {
        accepts("  NEDAMA   4821  ");
        accepts("NEDAMA 4821.");
        accepts("NEDAMA 4821!");
        accepts("NEDAMA\u00A04821");
        accepts("NEDAMA 4821\n");
    }

    @Test
    void rejectsAnythingElse() {
        rejects(null);
        rejects("");
        rejects("   ");
        rejects("hello");
        rejects("NEDAMA");
        rejects("NEDAMA 482");
        rejects("NEDAMA 48211");
        rejects("NEDAMA 48 21");
        rejects("OTHER 4821");
        rejects("please ack NEDAMA 4821");
        rejects("NEDAMA 4821 thanks");
        rejects("NEDAMA 4821 4821");
        rejects("NEDAMA 4821x");
    }

    @Test
    void acceptsOnlyTheCodeAndAckWhenNoKeywordIsSet() {
        ReplyParser none = new ReplyParser("");
        assertEquals(Optional.of("4821"), none.parseCode("4821"));
        assertEquals(Optional.of("4821"), none.parseCode("ACK 4821"));
        assertEquals(Optional.empty(), none.parseCode("NEDAMA 4821"));
    }

    @Test
    void cleansTheKeyword() {
        assertEquals("NEDAMA", ReplyParser.normalizeKeyword(" Nedama "));
        assertEquals("NEDAMAOPS", ReplyParser.normalizeKeyword("nedama-ops 2"));
        assertEquals("", ReplyParser.normalizeKeyword(null));
        assertEquals("", ReplyParser.normalizeKeyword("123 !!"));
        assertEquals(12, ReplyParser.normalizeKeyword("ABCDEFGHIJKLMNOPQRST").length());
        assertEquals("NEDAMA", new ReplyParser("nedama").keyword());
    }

    @Test
    void aKeywordWithDigitsAndSymbolsStillWorks() {
        ReplyParser odd = new ReplyParser("Ned4ma!");
        assertEquals("NEDMA", odd.keyword());
        assertEquals(Optional.of("4821"), odd.parseCode("NEDMA 4821"));
    }
}
