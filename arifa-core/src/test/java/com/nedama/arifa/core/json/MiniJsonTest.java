package com.nedama.arifa.core.json;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiniJsonTest {

    @Test
    void parsesNestedObjectsAndArrays() {
        Object parsed = MiniJson.parse("{\"a\": {\"b\": [1, 2.5, \"x\", true, false, null]}}");
        Map<String, Object> root = MiniJson.asObject(parsed, "root");
        Map<String, Object> a = MiniJson.asObject(root.get("a"), "a");
        List<Object> b = MiniJson.asArray(a.get("b"), "b");
        assertEquals(6, b.size());
        assertEquals(1L, b.get(0));
        assertEquals(2.5, b.get(1));
        assertEquals("x", b.get(2));
        assertEquals(Boolean.TRUE, b.get(3));
        assertEquals(Boolean.FALSE, b.get(4));
        assertNull(b.get(5));
    }

    @Test
    void decodesEscapesAndUnicode() {
        Object parsed = MiniJson.parse("\"line1\\nline2 \\\"q\\\" \\u00b0C \\/ \\\\\"");
        assertEquals("line1\nline2 \"q\" \u00b0C / \\", parsed);
    }

    @Test
    void handlesNegativeExponentAndLargeNumbers() {
        assertEquals(-12L, MiniJson.parse("-12"));
        assertEquals(1.5e3, MiniJson.parse("1.5e3"));
        assertTrue(MiniJson.parse("123456789012345678901234567890") instanceof Double);
    }

    @Test
    void readsTypedFields() {
        Map<String, Object> obj = MiniJson.asObject(
                MiniJson.parse("{\"id\": 15071, \"code\": \"101\", \"cost\": 0.8, \"whole\": 3.0}"), "obj");
        assertEquals(Long.valueOf(15071L), MiniJson.longValue(obj, "id"));
        assertEquals(Long.valueOf(101L), MiniJson.longValue(obj, "code"));
        assertNull(MiniJson.longValue(obj, "cost"));
        assertEquals(Long.valueOf(3L), MiniJson.longValue(obj, "whole"));
        assertEquals("15071", MiniJson.string(obj, "id"));
        assertEquals("3", MiniJson.string(obj, "whole"));
        assertNull(MiniJson.string(obj, "missing"));
    }

    @Test
    void parsesEmptyContainersAndWhitespace() {
        assertEquals(Map.of(), MiniJson.parse(" \n {} \t"));
        assertEquals(List.of(), MiniJson.parse("[ ]"));
    }

    @Test
    void rejectsInvalidJson() {
        assertThrows(MiniJson.JsonParseException.class, () -> MiniJson.parse("{\"a\": }"));
        assertThrows(MiniJson.JsonParseException.class, () -> MiniJson.parse("{\"a\": 1,}"));
        assertThrows(MiniJson.JsonParseException.class, () -> MiniJson.parse("[1 2]"));
        assertThrows(MiniJson.JsonParseException.class, () -> MiniJson.parse("\"unterminated"));
        assertThrows(MiniJson.JsonParseException.class, () -> MiniJson.parse("01"));
        assertThrows(MiniJson.JsonParseException.class, () -> MiniJson.parse("The supplied authentication is invalid"));
        assertThrows(MiniJson.JsonParseException.class, () -> MiniJson.parse(""));
        assertThrows(MiniJson.JsonParseException.class, () -> MiniJson.parse(null));
    }

    @Test
    void shapeHelpersRejectWrongTypes() {
        assertThrows(MiniJson.JsonParseException.class, () -> MiniJson.asObject(List.of(), "x"));
        assertThrows(MiniJson.JsonParseException.class, () -> MiniJson.asArray(Map.of(), "x"));
    }

    @Test
    void rejectsExcessiveNesting() {
        String deepArray = "[".repeat(MiniJson.MAX_DEPTH + 1) + "]".repeat(MiniJson.MAX_DEPTH + 1);
        assertThrows(MiniJson.JsonParseException.class, () -> MiniJson.parse(deepArray));

        String deepObject = "{\"a\":".repeat(MiniJson.MAX_DEPTH + 1) + "1" + "}".repeat(MiniJson.MAX_DEPTH + 1);
        assertThrows(MiniJson.JsonParseException.class, () -> MiniJson.parse(deepObject));
    }

    @Test
    void acceptsNestingAtTheLimit() {
        String atLimit = "[".repeat(MiniJson.MAX_DEPTH) + "]".repeat(MiniJson.MAX_DEPTH);
        assertTrue(MiniJson.parse(atLimit) instanceof List);
    }
}
