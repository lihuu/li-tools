package io.github.lihuu.jsonkit.core;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTreeReaderTest {

    private static Object read(String input) throws IOException, JsonDocumentException {
        return JsonTreeReader.read(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), "fixture.json");
    }

    @Test
    void preservesObjectMemberOrder() throws Exception {
        Map<?, ?> document = assertInstanceOf(Map.class, read("{\"b\":1,\"a\":2,\"c\":3}"));

        assertEquals(List.of("b", "a", "c"), List.copyOf(document.keySet()));
    }

    @Test
    void keepsNumberLiteralsAndComparableValues() throws Exception {
        Map<?, ?> document = assertInstanceOf(Map.class,
                read("{\"big\":123456789012345678901234567890,\"precise\":1.2300}"));

        JsonNumber big = assertInstanceOf(JsonNumber.class, document.get("big"));
        JsonNumber precise = assertInstanceOf(JsonNumber.class, document.get("precise"));
        assertEquals("123456789012345678901234567890", big.toString());
        assertEquals(new BigDecimal("123456789012345678901234567890"), big.decimalValue());
        assertEquals("1.2300", precise.toString());
        assertEquals(new BigDecimal("1.2300"), precise.decimalValue());
    }

    @Test
    void rejectsDuplicateObjectKeysForQueries() {
        JsonDocumentException failure = assertThrows(JsonDocumentException.class,
                () -> read("{\"a\":1,\"a\":2}"));

        assertTrue(failure.getMessage().contains("duplicate"), failure.getMessage());
        assertTrue(failure.getMessage().contains("\"a\""), failure.getMessage());
        assertTrue(failure.line() > 0, failure.getMessage());
        assertTrue(failure.column() > 0, failure.getMessage());
    }

    @Test
    void rejectsANumberBeyondBigDecimalRangeWithALocation() {
        JsonDocumentException failure = assertThrows(JsonDocumentException.class,
                () -> read("{\"huge\":1e999999999999}"));

        assertTrue(failure.getMessage().contains("number out of range"), failure.getMessage());
        assertTrue(failure.line() > 0, failure.getMessage());
    }

    @Test
    void detectsADuplicateEvenWhenTheFirstValueIsNull() {
        JsonDocumentException failure = assertThrows(JsonDocumentException.class,
                () -> read("{\"a\":null,\"a\":2}"));

        assertTrue(failure.getMessage().contains("duplicate"), failure.getMessage());
    }

    @Test
    void readsNullAndNestedValues() throws Exception {
        Map<?, ?> document = assertInstanceOf(Map.class, read("{\"missing\":null,\"items\":[null,true,\"x\"]}"));

        assertTrue(document.containsKey("missing"));
        assertNull(document.get("missing"));
        assertEquals(List.of(java.util.Arrays.asList(null, true, "x")), List.of(document.get("items")));
    }
}
