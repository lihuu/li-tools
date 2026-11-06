package io.github.lihuu.jsonkit.core;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonFormatterTest {

    private static String format(byte[] input) throws IOException, JsonDocumentException {
        StringWriter output = new StringWriter();
        JsonFormatter.write(new ByteArrayInputStream(input), "fixture.json", output, JsonHighlighter.plain());
        return output.toString();
    }

    private static String format(String input) throws IOException, JsonDocumentException {
        return format(input.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void prettyPrintsNestedObjectsAndArraysWithTwoSpaces() throws Exception {
        String output = format("{\"name\":\"Alice\",\"items\":[1,2],\"empty\":{},\"none\":[]}");

        assertEquals("""
                {
                  "name": "Alice",
                  "items": [
                    1,
                    2
                  ],
                  "empty": {},
                  "none": []
                }
                """, output);
    }

    @Test
    void preservesNumberLiteralsExactly() throws Exception {
        String input = "{\"big\":123456789012345678901234567890,\"precise\":1.2300,\"exp\":1e+10,\"negativeZero\":-0}";

        String output = format(input);

        assertTrue(output.contains("\"big\": 123456789012345678901234567890"), output);
        assertTrue(output.contains("\"precise\": 1.2300"), output);
        assertTrue(output.contains("\"exp\": 1e+10"), output);
        assertTrue(output.contains("\"negativeZero\": -0"), output);
    }

    @Test
    void acceptsUtf8ByteOrderMark() throws Exception {
        byte[] document = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '{', '"', 'a', '"', ':', '1', '}'};

        assertEquals("{\n  \"a\": 1\n}\n", format(document));
    }

    @Test
    void displaysDuplicateKeysWithoutChoosingOne() throws Exception {
        assertEquals("{\n  \"a\": 1,\n  \"a\": 2\n}\n", format("{\"a\":1,\"a\":2}"));
    }

    @Test
    void convertsCrlfInputToLfOutput() throws Exception {
        assertEquals("{\n  \"a\": 1,\n  \"b\": 2\n}\n", format("{\r\n  \"a\": 1,\r\n  \"b\": 2\r\n}\r\n"));
    }

    @Test
    void handlesEveryValidRootValue() throws Exception {
        assertEquals("\"text\"\n", format("\"text\""));
        assertEquals("42\n", format("42"));
        assertEquals("true\n", format("true"));
        assertEquals("false\n", format("false"));
        assertEquals("null\n", format("null"));
        assertEquals("[]\n", format("[]"));
    }

    @Test
    void escapesStringsAsValidJson() throws Exception {
        String output = format("{\"quote\":\"a\\\"b\",\"newline\":\"a\\nb\",\"unicode\":\"键\"}");

        assertTrue(output.contains("\"quote\": \"a\\\"b\""), output);
        assertTrue(output.contains("\"newline\": \"a\\nb\""), output);
        assertTrue(output.contains("\"unicode\": \"键\""), output);
    }

    @Test
    void rejectsEmptyInput() {
        JsonDocumentException failure = assertThrows(JsonDocumentException.class, () -> format("   \n\t"));

        assertTrue(failure.getMessage().contains("empty input"), failure.getMessage());
    }

    @Test
    void rejectsTruncatedDocumentWithLocation() {
        JsonDocumentException failure = assertThrows(JsonDocumentException.class, () -> format("{\n  \"a\": "));

        assertEquals("fixture.json", failure.source());
        assertTrue(failure.line() > 0, failure.getMessage());
        assertTrue(failure.column() > 0, failure.getMessage());
    }

    @Test
    void rejectsContentAfterTheRootValue() {
        JsonDocumentException failure = assertThrows(JsonDocumentException.class, () -> format("{\"a\":1}\n{\"b\":2}"));

        assertTrue(failure.getMessage().contains("after"), failure.getMessage());
    }

    @Test
    void rejectsTrailingGarbage() {
        JsonDocumentException failure = assertThrows(JsonDocumentException.class, () -> format("{\"a\":1}x"));

        assertTrue(failure.getMessage().contains("fixture.json"), failure.getMessage());
    }
}
