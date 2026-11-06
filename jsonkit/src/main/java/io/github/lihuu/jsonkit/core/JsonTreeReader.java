package io.github.lihuu.jsonkit.core;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads one document into ordered Java values and rejects duplicate object keys. */
public final class JsonTreeReader {
    private JsonTreeReader() {}

    public static @Nullable Object read(@NotNull InputStream input, @NotNull String source)
            throws IOException, JsonDocumentException {
        try (JsonParser parser = JsonInputs.open(input)) {
            JsonToken token = next(parser, source);
            if (token == null) {
                throw JsonDocumentException.empty(source);
            }
            Object value = readValue(parser, token, source);
            JsonToken trailing = next(parser, source);
            if (trailing != null) {
                throw JsonInputs.unexpected(source, parser, "unexpected content after the JSON document");
            }
            return value;
        }
    }

    private static @Nullable Object readValue(
            @NotNull JsonParser parser,
            @NotNull JsonToken token,
            @NotNull String source
    ) throws IOException, JsonDocumentException {
        return switch (token) {
            case START_OBJECT -> readObject(parser, source);
            case START_ARRAY -> readArray(parser, source);
            case VALUE_STRING -> parser.getText();
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> number(parser, source);
            case VALUE_TRUE -> Boolean.TRUE;
            case VALUE_FALSE -> Boolean.FALSE;
            case VALUE_NULL -> null;
            default -> throw JsonInputs.unexpected(source, parser, "unexpected token " + token);
        };
    }

    private static @NotNull JsonNumber number(@NotNull JsonParser parser, @NotNull String source)
            throws IOException, JsonDocumentException {
        String literal = parser.getText();
        try {
            return new JsonNumber(literal);
        } catch (NumberFormatException failure) {
            throw JsonInputs.unexpected(source, parser, "number out of range: " + literal, failure);
        }
    }

    private static @NotNull Map<String, Object> readObject(@NotNull JsonParser parser, @NotNull String source)
            throws IOException, JsonDocumentException {
        Map<String, Object> members = new LinkedHashMap<>();
        while (true) {
            JsonToken token = next(parser, source);
            if (token == null) {
                throw JsonInputs.unexpected(source, parser, "unexpected end of input inside an object");
            }
            if (token == JsonToken.END_OBJECT) {
                return members;
            }
            if (token != JsonToken.FIELD_NAME) {
                throw JsonInputs.unexpected(source, parser, "expected an object member name but found " + token);
            }
            String name = parser.currentName();
            if (members.containsKey(name)) {
                throw JsonInputs.unexpected(source, parser,
                        "duplicate object key \"" + name + "\" in query input");
            }
            JsonToken valueToken = next(parser, source);
            if (valueToken == null) {
                throw JsonInputs.unexpected(source, parser, "unexpected end of input after object member \"" + name + "\"");
            }
            members.put(name, readValue(parser, valueToken, source));
        }
    }

    private static @NotNull List<Object> readArray(@NotNull JsonParser parser, @NotNull String source)
            throws IOException, JsonDocumentException {
        List<Object> elements = new ArrayList<>();
        while (true) {
            JsonToken token = next(parser, source);
            if (token == null) {
                throw JsonInputs.unexpected(source, parser, "unexpected end of input inside an array");
            }
            if (token == JsonToken.END_ARRAY) {
                return elements;
            }
            elements.add(readValue(parser, token, source));
        }
    }

    private static @Nullable JsonToken next(@NotNull JsonParser parser, @NotNull String source)
            throws IOException, JsonDocumentException {
        try {
            return parser.nextToken();
        } catch (JsonProcessingException failure) {
            throw JsonInputs.failure(source, failure);
        }
    }
}
