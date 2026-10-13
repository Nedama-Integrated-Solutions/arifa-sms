package com.nedama.arifa.core.ack;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an operator's SMS reply and extracts the acknowledgement code.
 *
 * <p>Accepted replies (not case sensitive), with the keyword NEDAMA and the code 4821:</p>
 * <ul>
 *   <li>{@code NEDAMA 4821}</li>
 *   <li>{@code nedama4821}, {@code Nedama: 4821}, {@code NEDAMA-4821}</li>
 *   <li>{@code ACK 4821}</li>
 *   <li>{@code 4821} on its own</li>
 * </ul>
 *
 * <p>Anything else is not treated as an acknowledgement, including extra words after the
 * code. The parser is deliberately strict: a stray text must never acknowledge an alarm.
 * Whether the code is correct, and whether it came from the right phone, is checked later
 * by {@link AckRegistry}.</p>
 */
public final class ReplyParser {

    /** Number of digits in an acknowledgement code. */
    public static final int CODE_DIGITS = 4;

    private static final int MAX_KEYWORD_LENGTH = 12;
    private static final Pattern REPLY = Pattern.compile("^([A-Z]*)[\\s:#.\\-]*(\\d{" + CODE_DIGITS + "})[\\s.!]*$");

    private final String keyword;

    /**
     * @param keyword the keyword operators are told to type first. Blank means replies are just the code.
     *                See {@link #normalizeKeyword(String)} for how it is cleaned.
     */
    public ReplyParser(String keyword) {
        this.keyword = normalizeKeyword(keyword);
    }

    /**
     * Cleans a keyword typed in the settings form: upper case, letters only, at most 12 characters.
     * Null or blank gives an empty keyword.
     */
    public static String normalizeKeyword(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (char c : raw.toUpperCase(Locale.ROOT).toCharArray()) {
            if (c >= 'A' && c <= 'Z' && out.length() < MAX_KEYWORD_LENGTH) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** The cleaned keyword, possibly empty. */
    public String keyword() {
        return keyword;
    }

    /** The code in the reply, or empty if the text is not an acknowledgement reply. */
    public Optional<String> parseCode(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String cleaned = text.replace('\u00A0', ' ').trim().toUpperCase(Locale.ROOT);
        Matcher matcher = REPLY.matcher(cleaned);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String prefix = matcher.group(1);
        if (prefix.isEmpty() || prefix.equals("ACK") || (!keyword.isEmpty() && prefix.equals(keyword))) {
            return Optional.of(matcher.group(2));
        }
        return Optional.empty();
    }
}
