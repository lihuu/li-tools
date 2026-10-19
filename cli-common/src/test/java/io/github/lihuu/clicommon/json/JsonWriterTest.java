package io.github.lihuu.clicommon.json;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JsonWriterTest {

    @Test
    void shouldSerializeEmptyObject() {
        JsonWriter writer = new JsonWriter();
        writer.beginObject().endObject();
        assertEquals("{}", writer.toString());
    }

    @Test
    void shouldSerializeEmptyArray() {
        JsonWriter writer = new JsonWriter();
        writer.beginArray().endArray();
        assertEquals("[]", writer.toString());
    }

    @Test
    void shouldSerializeSimpleObjectWithVariousTypes() {
        JsonWriter writer = new JsonWriter();
        writer.beginObject()
                .name("name").value("token")
                .name("count").value(42L)
                .name("active").value(true)
                .name("tag").nullValue()
                .endObject();

        assertEquals("{\"name\":\"token\",\"count\":42,\"active\":true,\"tag\":null}", writer.toString());
    }

    @Test
    void shouldSerializeNestedObjectAndArray() {
        JsonWriter writer = new JsonWriter();
        writer.beginObject()
                .name("status").value("OK")
                .name("items").beginArray()
                .value("a")
                .value("b")
                .endArray()
                .name("meta").beginObject()
                .name("code").value(200L)
                .endObject()
                .endObject();

        assertEquals("{\"status\":\"OK\",\"items\":[\"a\",\"b\"],\"meta\":{\"code\":200}}", writer.toString());
    }

    @Test
    void shouldEscapeSpecialCharacters() {
        JsonWriter writer = new JsonWriter();
        writer.beginObject()
                .name("key\"quote").value("line1\nline2\ttab\\backslash\r")
                .endObject();

        assertEquals("{\"key\\\"quote\":\"line1\\nline2\\ttab\\\\backslash\\r\"}", writer.toString());
    }

    @Test
    void shouldRetainUtf8CharactersDirectly() {
        JsonWriter writer = new JsonWriter();
        writer.beginObject()
                .name("message").value("已就绪 ✔")
                .endObject();

        assertEquals("{\"message\":\"已就绪 ✔\"}", writer.toString());
    }

    @Test
    void shouldThrowOnUnbalancedJson() {
        JsonWriter writer = new JsonWriter();
        writer.beginObject().name("test").value("val");
        assertThrows(IllegalStateException.class, writer::toString);
    }

    @Test
    void shouldThrowOnClosingUnopenedObject() {
        JsonWriter writer = new JsonWriter();
        assertThrows(IllegalStateException.class, writer::endObject);
    }

    @Test
    void shouldThrowOnNameOutsideObject() {
        JsonWriter writer = new JsonWriter();
        assertThrows(IllegalStateException.class, () -> writer.name("outside"));
    }
}
