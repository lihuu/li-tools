package io.github.lihuu.jsonkit.core;

import com.fasterxml.jackson.core.io.JsonStringEncoder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Map;

/** Writes a selected Java value tree as formatted JSON. */
public final class JsonValueWriter {
    private JsonValueWriter() {}

    public static void write(@Nullable Object value, @NotNull Writer output, @NotNull JsonHighlighter highlighter)
            throws IOException {
        writeValue(value, 0, output, highlighter);
        output.write('\n');
    }

    private static void writeValue(
            @Nullable Object value,
            int depth,
            @NotNull Writer output,
            @NotNull JsonHighlighter highlighter
    ) throws IOException {
        if (value instanceof Map<?, ?> object) {
            writeObject(object, depth, output, highlighter);
        } else if (value instanceof List<?> array) {
            writeArray(array, depth, output, highlighter);
        } else if (value instanceof String text) {
            output.write(highlighter.string(quoted(text)));
        } else if (value instanceof JsonNumber number) {
            output.write(highlighter.number(number.literal()));
        } else if (value instanceof Boolean literal) {
            output.write(highlighter.literal(literal.toString()));
        } else if (value == null) {
            output.write(highlighter.literal("null"));
        } else {
            throw new IllegalArgumentException("not a JSON value: " + value.getClass().getName());
        }
    }

    private static void writeObject(
            @NotNull Map<?, ?> object,
            int depth,
            @NotNull Writer output,
            @NotNull JsonHighlighter highlighter
    ) throws IOException {
        if (object.isEmpty()) {
            output.write("{}");
            return;
        }
        output.write('{');
        int members = 0;
        for (Map.Entry<?, ?> member : object.entrySet()) {
            if (!(member.getKey() instanceof String name)) {
                throw new IllegalArgumentException("JSON object key is not a string: " + member.getKey());
            }
            output.write(members == 0 ? '\n' : ',');
            if (members > 0) {
                output.write('\n');
            }
            indent(output, depth + 1);
            output.write(highlighter.key(quoted(name)));
            output.write(": ");
            writeValue(member.getValue(), depth + 1, output, highlighter);
            members++;
        }
        output.write('\n');
        indent(output, depth);
        output.write('}');
    }

    private static void writeArray(
            @NotNull List<?> array,
            int depth,
            @NotNull Writer output,
            @NotNull JsonHighlighter highlighter
    ) throws IOException {
        if (array.isEmpty()) {
            output.write("[]");
            return;
        }
        output.write('[');
        int elements = 0;
        for (Object element : array) {
            output.write(elements == 0 ? '\n' : ',');
            if (elements > 0) {
                output.write('\n');
            }
            indent(output, depth + 1);
            writeValue(element, depth + 1, output, highlighter);
            elements++;
        }
        output.write('\n');
        indent(output, depth);
        output.write(']');
    }

    private static void indent(@NotNull Writer output, int depth) throws IOException {
        for (int level = 0; level < depth; level++) {
            output.write("  ");
        }
    }

    private static @NotNull String quoted(@NotNull String value) {
        return "\"" + new String(JsonStringEncoder.getInstance().quoteAsString(value)) + "\"";
    }
}
