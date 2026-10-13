package com.nedama.arifa.core.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, strict JSON parser with no dependencies.
 *
 * <p>Why not Gson or Jackson: arifa-core must ship inside an Ignition module without
 * dragging in libraries that could clash with the versions Ignition already loads.
 * The Africa's Talking responses we read are small, so a compact parser is enough.</p>
 *
 * <p>Output types: objects become {@code Map<String, Object>} (insertion ordered),
 * arrays become {@code List<Object>}, strings become {@code String}, whole numbers
 * become {@code Long} (or {@code Double} if too large), other numbers become
 * {@code Double}, booleans become {@code Boolean}, and null stays {@code null}.</p>
 */
public final class MiniJson {

    /**
     * Caps how deeply objects and arrays may nest. The responses this parser reads (Africa's Talking's API)
     * are a few levels deep at most, so this is generous headroom, not a tight fit. It exists only so that a
     * malformed or unexpectedly large response cannot exhaust the stack with a recursive-descent parse.
     */
    public static final int MAX_DEPTH = 64;

    private final String src;
    private int pos;
    private int depth;

    private MiniJson(String src) {
        this.src = src;
    }

    /**
     * Parses a complete JSON document.
     *
     * @throws JsonParseException if the text is not valid JSON, or nests objects and arrays deeper than
     *                            {@link #MAX_DEPTH}
     */
    public static Object parse(String text) {
        if (text == null) {
            throw new JsonParseException("Input is null", 0);
        }
        MiniJson parser = new MiniJson(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (parser.pos != parser.src.length()) {
            throw parser.error("Unexpected content after the JSON value");
        }
        return value;
    }

    // ------------------------------------------------------------------
    // Typed access helpers
    // ------------------------------------------------------------------

    /** Casts a parsed value to a JSON object, or throws with a readable message. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object value, String what) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        throw new JsonParseException("Expected " + what + " to be a JSON object", -1);
    }

    /** Casts a parsed value to a JSON array, or throws with a readable message. */
    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object value, String what) {
        if (value instanceof List) {
            return (List<Object>) value;
        }
        throw new JsonParseException("Expected " + what + " to be a JSON array", -1);
    }

    /** Returns the field as text. Numbers and booleans are converted. Missing or null gives null. */
    public static String string(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d)) {
            return Long.toString(d.longValue());
        }
        return value.toString();
    }

    /** Returns the field as a whole number. Accepts JSON numbers and numeric strings. Otherwise null. */
    public static Long longValue(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d)) {
            return d.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Parser internals
    // ------------------------------------------------------------------

    private Object readValue() {
        if (pos >= src.length()) {
            throw error("Unexpected end of input");
        }
        char c = src.charAt(pos);
        switch (c) {
            case '{':
                return withDepth(this::readObject);
            case '[':
                return withDepth(this::readArray);
            case '"':
                return readString();
            case 't':
                expectWord("true");
                return Boolean.TRUE;
            case 'f':
                expectWord("false");
                return Boolean.FALSE;
            case 'n':
                expectWord("null");
                return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return readNumber();
                }
                throw error("Unexpected character '" + c + "'");
        }
    }

    private <T> T withDepth(java.util.function.Supplier<T> body) {
        depth++;
        if (depth > MAX_DEPTH) {
            throw error("Nested too deeply (more than " + MAX_DEPTH + " levels of objects or arrays)");
        }
        try {
            return body.get();
        } finally {
            depth--;
        }
    }

    private Map<String, Object> readObject() {
        Map<String, Object> result = new LinkedHashMap<>();
        pos++; // opening brace
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return result;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw error("Expected a quoted field name");
            }
            String key = readString();
            skipWhitespace();
            expectChar(':');
            skipWhitespace();
            result.put(key, readValue());
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                pos++;
            } else if (c == '}') {
                pos++;
                return result;
            } else {
                throw error("Expected ',' or '}' in object");
            }
        }
    }

    private List<Object> readArray() {
        List<Object> result = new ArrayList<>();
        pos++; // opening bracket
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return result;
        }
        while (true) {
            skipWhitespace();
            result.add(readValue());
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                pos++;
            } else if (c == ']') {
                pos++;
                return result;
            } else {
                throw error("Expected ',' or ']' in array");
            }
        }
    }

    private String readString() {
        StringBuilder out = new StringBuilder();
        pos++; // opening quote
        while (true) {
            if (pos >= src.length()) {
                throw error("Unterminated string");
            }
            char c = src.charAt(pos++);
            if (c == '"') {
                return out.toString();
            }
            if (c == '\\') {
                if (pos >= src.length()) {
                    throw error("Unterminated escape sequence");
                }
                char esc = src.charAt(pos++);
                switch (esc) {
                    case '"':
                    case '\\':
                    case '/':
                        out.append(esc);
                        break;
                    case 'b':
                        out.append('\b');
                        break;
                    case 'f':
                        out.append('\f');
                        break;
                    case 'n':
                        out.append('\n');
                        break;
                    case 'r':
                        out.append('\r');
                        break;
                    case 't':
                        out.append('\t');
                        break;
                    case 'u':
                        if (pos + 4 > src.length()) {
                            throw error("Incomplete unicode escape");
                        }
                        try {
                            out.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        } catch (NumberFormatException e) {
                            throw error("Invalid unicode escape");
                        }
                        pos += 4;
                        break;
                    default:
                        throw error("Invalid escape '\\" + esc + "'");
                }
            } else if (c < 0x20) {
                throw error("Unescaped control character in string");
            } else {
                out.append(c);
            }
        }
    }

    private Object readNumber() {
        int start = pos;
        boolean whole = true;
        if (peek() == '-') {
            pos++;
        }
        if (peek() == '0') {
            pos++;
        } else if (isDigit(peek())) {
            while (isDigit(peek())) {
                pos++;
            }
        } else {
            throw error("Invalid number");
        }
        if (peek() == '.') {
            whole = false;
            pos++;
            if (!isDigit(peek())) {
                throw error("Expected digits after the decimal point");
            }
            while (isDigit(peek())) {
                pos++;
            }
        }
        if (peek() == 'e' || peek() == 'E') {
            whole = false;
            pos++;
            if (peek() == '+' || peek() == '-') {
                pos++;
            }
            if (!isDigit(peek())) {
                throw error("Expected digits in the exponent");
            }
            while (isDigit(peek())) {
                pos++;
            }
        }
        String number = src.substring(start, pos);
        if (whole) {
            try {
                return Long.parseLong(number);
            } catch (NumberFormatException tooLarge) {
                return Double.parseDouble(number);
            }
        }
        return Double.parseDouble(number);
    }

    private void expectWord(String word) {
        if (!src.startsWith(word, pos)) {
            throw error("Expected '" + word + "'");
        }
        pos += word.length();
    }

    private void expectChar(char expected) {
        if (peek() != expected) {
            throw error("Expected '" + expected + "'");
        }
        pos++;
    }

    private char peek() {
        return pos < src.length() ? src.charAt(pos) : '\0';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private void skipWhitespace() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                return;
            }
        }
    }

    private JsonParseException error(String message) {
        return new JsonParseException(message, pos);
    }

    /** Thrown when text is not valid JSON or does not have the expected shape. */
    public static final class JsonParseException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final int position;

        public JsonParseException(String message, int position) {
            super(position >= 0 ? message + " (at character " + position + ")" : message);
            this.position = position;
        }

        /** Character offset where parsing failed, or -1 for shape errors. */
        public int getPosition() {
            return position;
        }
    }
}
