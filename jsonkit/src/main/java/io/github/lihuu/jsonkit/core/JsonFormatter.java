package io.github.lihuu.jsonkit.core;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;

/** Streams one JSON document as two-space-indented UTF-8 JSON. */
public final class JsonFormatter {
    private JsonFormatter() {}

    public static void write(
            @NotNull InputStream input,
            @NotNull String source,
            @NotNull Writer output,
            @NotNull JsonHighlighter highlighter
    ) throws IOException, JsonDocumentException {
        try (JsonParser parser = JsonInputs.open(input)) {
            JsonToken token = next(parser, source);
            if (token == null) {
                throw JsonDocumentException.empty(source);
            }
            writeValue(parser, token, 0, output, highlighter, source);
            JsonToken trailing = next(parser, source);
            if (trailing != null) {
                throw JsonInputs.unexpected(source, parser, "unexpected content after the JSON document");
            }
            output.write('\n');
        }
    }

    private static void writeValue(
            @NotNull JsonParser parser,
            @NotNull JsonToken token,
            int depth,
            @NotNull Writer output,
            @NotNull JsonHighlighter highlighter,
            @NotNull String source
    ) throws IOException, JsonDocumentException {
        switch (token) {
            case START_OBJECT -> writeObject(parser, depth, output, highlighter, source);
            case START_ARRAY -> writeArray(parser, depth, output, highlighter, source);
            case VALUE_STRING -> output.write(highlighter.string(quoted(parser.getText())));
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> output.write(highlighter.number(parser.getText()));
            case VALUE_TRUE, VALUE_FALSE, VALUE_NULL ->
                    output.write(highlighter.literal(token == JsonToken.VALUE_TRUE ? "true"
                            : token == JsonToken.VALUE_FALSE ? "false" : "null"));
            default -> throw JsonInputs.unexpected(source, parser, "unexpected token " + token);
        }
    }

    private static void writeObject(
            @NotNull JsonParser parser,
            int depth,
            @NotNull Writer output,
            @NotNull JsonHighlighter highlighter,
            @NotNull String source
    ) throws IOException, JsonDocumentException {
        output.write('{');
        int members = 0;
        while (true) {
            JsonToken token = next(parser, source);
            if (token == JsonToken.END_OBJECT) {
                if (members > 0) {
                    output.write('\n');
                    indent(output, depth);
                }
                output.write('}');
                return;
            }
            if (token != JsonToken.FIELD_NAME) {
                throw JsonInputs.unexpected(source, parser, "expected an object member name but found " + token);
            }
            output.write(members == 0 ? '\n' : ',');
            if (members > 0) {
                output.write('\n');
            }
            indent(output, depth + 1);
            output.write(highlighter.key(quoted(parser.currentName())));
            output.write(": ");
            JsonToken value = next(parser, source);
            if (value == null) {
                throw JsonInputs.unexpected(source, parser, "unexpected end of input after an object member name");
            }
            writeValue(parser, value, depth + 1, output, highlighter, source);
            members++;
        }
    }

    private static void writeArray(
            @NotNull JsonParser parser,
            int depth,
            @NotNull Writer output,
            @NotNull JsonHighlighter highlighter,
            @NotNull String source
    ) throws IOException, JsonDocumentException {
        output.write('[');
        int elements = 0;
        while (true) {
            JsonToken token = next(parser, source);
            if (token == JsonToken.END_ARRAY) {
                if (elements > 0) {
                    output.write('\n');
                    indent(output, depth);
                }
                output.write(']');
                return;
            }
            if (token == null) {
                throw JsonInputs.unexpected(source, parser, "unexpected end of input inside an array");
            }
            output.write(elements == 0 ? '\n' : ',');
            if (elements > 0) {
                output.write('\n');
            }
            indent(output, depth + 1);
            writeValue(parser, token, depth + 1, output, highlighter, source);
            elements++;
        }
    }

    private static void indent(@NotNull Writer output, int depth) throws IOException {
        for (int level = 0; level < depth; level++) {
            output.write("  ");
        }
    }

    private static @NotNull String quoted(@NotNull String value) {
        return "\"" + new String(JsonStringEncoder.getInstance().quoteAsString(value)) + "\"";
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
