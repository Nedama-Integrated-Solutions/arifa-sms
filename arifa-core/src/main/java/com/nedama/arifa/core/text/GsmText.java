package com.nedama.arifa.core.text;

import java.text.Normalizer;
import java.util.Map;

/**
 * Keeps SMS text inside the GSM 7-bit alphabet.
 *
 * <p>Why it matters: one SMS holds 160 GSM characters, but a single character outside that
 * alphabet (a degree sign, a curly quote, an emoji) switches the whole message to Unicode,
 * which holds only 70 characters per SMS. Alarm text like "Temp 85°C" would then cost two
 * or three times as much and may arrive split.</p>
 *
 * <p>{@link #sanitize} swaps common symbols for plain equivalents ("°" becomes "deg"),
 * strips accents the GSM alphabet lacks, and replaces anything else with "?".</p>
 */
public final class GsmText {

    /** GSM 03.38 basic character set (the escape character is deliberately left out). */
    private static final String BASIC =
            "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
                    + "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";

    /** GSM 03.38 extension characters. Each one costs two characters of space. */
    private static final String EXTENSION = "^{}\\[~]|€";

    public static final int SINGLE_SEGMENT_GSM = 160;
    public static final int MULTI_SEGMENT_GSM = 153;
    public static final int SINGLE_SEGMENT_UNICODE = 70;
    public static final int MULTI_SEGMENT_UNICODE = 67;

    private static final Map<Character, String> REPLACEMENTS = Map.ofEntries(
            Map.entry('°', "deg"),
            Map.entry('º', "deg"),
            Map.entry('±', "+/-"),
            Map.entry('µ', "u"),
            Map.entry('μ', "u"),
            Map.entry('²', "2"),
            Map.entry('³', "3"),
            Map.entry('½', "1/2"),
            Map.entry('¼', "1/4"),
            Map.entry('¾', "3/4"),
            Map.entry('×', "x"),
            Map.entry('÷', "/"),
            Map.entry('≤', "<="),
            Map.entry('≥', ">="),
            Map.entry('≈', "~"),
            Map.entry('–', "-"),
            Map.entry('—', "-"),
            Map.entry('−', "-"),
            Map.entry('‘', "'"),
            Map.entry('’', "'"),
            Map.entry('‚', "'"),
            Map.entry('′', "'"),
            Map.entry('“', "\""),
            Map.entry('”', "\""),
            Map.entry('„', "\""),
            Map.entry('″', "\""),
            Map.entry('…', "..."),
            Map.entry('•', "*"),
            Map.entry('©', "(c)"),
            Map.entry('®', "(R)"),
            Map.entry('™', "TM"),
            Map.entry('\u00A0', " "),
            Map.entry('\t', " "),
            Map.entry('\f', " "));

    private GsmText() {
    }

    /**
     * Returns text that uses only GSM characters. Line endings become "\n" and
     * trailing whitespace is removed. Null becomes an empty string.
     */
    public static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        String normalised = text.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder out = new StringBuilder(normalised.length());
        normalised.codePoints().forEach(cp -> {
            if (Character.charCount(cp) > 1) {
                out.append('?'); // emoji and other characters outside the basic plane
                return;
            }
            char c = (char) cp;
            if (isGsmChar(c)) {
                out.append(c);
                return;
            }
            String replacement = REPLACEMENTS.get(c);
            if (replacement != null) {
                out.append(replacement);
                return;
            }
            String decomposed = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD);
            if (decomposed.length() > 1 && isGsmChar(decomposed.charAt(0))) {
                out.append(decomposed.charAt(0)); // for example "ã" becomes "a"
                return;
            }
            out.append('?');
        });
        return out.toString().stripTrailing();
    }

    /** True if every character is in the GSM basic or extension set. */
    public static boolean isGsm7(String text) {
        if (text == null) {
            return true;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!isGsmChar(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Number of SMS segments the text will be billed as.
     * Returns 0 for empty text.
     */
    public static int segmentCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        if (isGsm7(text)) {
            int septets = septetLength(text);
            return septets <= SINGLE_SEGMENT_GSM ? 1 : ceilDiv(septets, MULTI_SEGMENT_GSM);
        }
        int units = text.length();
        return units <= SINGLE_SEGMENT_UNICODE ? 1 : ceilDiv(units, MULTI_SEGMENT_UNICODE);
    }

    /**
     * Shortens text so it fits in at most {@code maxSegments} SMS, ending with "..." when cut.
     * The text is sanitized first, so the result is always GSM-only.
     */
    public static String truncateToSegments(String text, int maxSegments) {
        if (maxSegments < 1) {
            throw new IllegalArgumentException("maxSegments must be at least 1");
        }
        String clean = sanitize(text);
        int limit = septetLimit(maxSegments);
        if (septetLength(clean) <= limit) {
            return clean;
        }
        return cutToSeptets(clean, limit - 3) + "...";
    }

    /**
     * Like {@link #truncateToSegments(String, int)}, but the suffix is always kept whole, on its own
     * line after the text. Only the text is shortened to make room. Used for the acknowledgement
     * instruction, which must never be cut off. A null or blank suffix behaves exactly like the
     * two-argument method.
     */
    public static String truncateToSegments(String text, int maxSegments, String suffix) {
        if (maxSegments < 1) {
            throw new IllegalArgumentException("maxSegments must be at least 1");
        }
        String cleanSuffix = sanitize(suffix);
        if (cleanSuffix.isBlank()) {
            return truncateToSegments(text, maxSegments);
        }
        String clean = sanitize(text);
        if (clean.isBlank()) {
            return truncateToSegments(cleanSuffix, maxSegments);
        }
        int limit = septetLimit(maxSegments);
        int suffixCost = 1 + septetLength(cleanSuffix); // the new line, then the suffix
        if (suffixCost >= limit) {
            return truncateToSegments(cleanSuffix, maxSegments);
        }
        int bodyBudget = limit - suffixCost;
        String body = septetLength(clean) <= bodyBudget
                ? clean
                : cutToSeptets(clean, bodyBudget - 3) + "...";
        return body + "\n" + cleanSuffix;
    }

    private static int septetLimit(int maxSegments) {
        return maxSegments == 1 ? SINGLE_SEGMENT_GSM : MULTI_SEGMENT_GSM * maxSegments;
    }

    /** The longest start of the text that fits in the budget, without trailing space. */
    private static String cutToSeptets(String clean, int budget) {
        int used = 0;
        int cut = 0;
        while (cut < clean.length() && budget > 0) {
            int cost = septetCost(clean.charAt(cut));
            if (used + cost > budget) {
                break;
            }
            used += cost;
            cut++;
        }
        return clean.substring(0, cut).stripTrailing();
    }

    /** Length in GSM septets: extension characters count twice. Assumes GSM-only text. */
    public static int septetLength(String text) {
        int total = 0;
        for (int i = 0; i < text.length(); i++) {
            total += septetCost(text.charAt(i));
        }
        return total;
    }

    private static int septetCost(char c) {
        return EXTENSION.indexOf(c) >= 0 ? 2 : 1;
    }

    private static boolean isGsmChar(char c) {
        return BASIC.indexOf(c) >= 0 || EXTENSION.indexOf(c) >= 0;
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
