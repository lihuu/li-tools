package io.github.lihuu.clicommon.json;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Lightweight, zero-reflection JSON writer.
 * Compatible with GraalVM Native Image without reflection configuration.
 *
 * <p>By default the document accumulates in memory and {@link #toString()} returns it.
 * Constructing with a {@link PrintWriter} streams the document in small chunks instead,
 * which keeps a large report off the heap; call {@link #flush()} when the document is
 * complete. A streaming writer cannot be read back with {@link #toString()}.
 */
public final class JsonWriter {
    private static final int STREAM_FLUSH_THRESHOLD = 8192;

    private final StringBuilder out = new StringBuilder();
    private final @Nullable PrintWriter stream;
    private final Deque<Boolean> emptyLevels = new ArrayDeque<>();
    private boolean awaitingValue;

    public JsonWriter() {
        this.stream = null;
    }

    public JsonWriter(@NotNull PrintWriter stream) {
        this.stream = stream;
    }

    public @NotNull JsonWriter beginObject() {
        prefix();
        out.append('{');
        emptyLevels.push(Boolean.TRUE);
        flushIfNeeded();
        return this;
    }

    public @NotNull JsonWriter endObject() {
        close('}');
        return this;
    }

    public @NotNull JsonWriter beginArray() {
        prefix();
        out.append('[');
        emptyLevels.push(Boolean.TRUE);
        flushIfNeeded();
        return this;
    }

    public @NotNull JsonWriter endArray() {
        close(']');
        return this;
    }

    public @NotNull JsonWriter name(@NotNull String name) {
        if (emptyLevels.isEmpty()) {
            throw new IllegalStateException("member name outside an object");
        }
        separate();
        out.append(quote(name)).append(": ");
        awaitingValue = true;
        flushIfNeeded();
        return this;
    }

    public @NotNull JsonWriter value(@Nullable String value) {
        prefix();
        out.append(value == null ? "null" : quote(value));
        flushIfNeeded();
        return this;
    }

    public @NotNull JsonWriter value(long value) {
        prefix();
        out.append(value);
        flushIfNeeded();
        return this;
    }

    public @NotNull JsonWriter value(double value) {
        prefix();
        out.append(value);
        flushIfNeeded();
        return this;
    }

    public @NotNull JsonWriter value(boolean value) {
        prefix();
        out.append(value);
        flushIfNeeded();
        return this;
    }

    public @NotNull JsonWriter nullValue() {
        prefix();
        out.append("null");
        flushIfNeeded();
        return this;
    }

    /**
     * Writes pending output to the stream, if this writer was created with one.
     */
    public @NotNull JsonWriter flush() {
        if (stream != null) {
            stream.append(out);
            out.setLength(0);
            stream.flush();
        }
        return this;
    }

    @Override
    public @NotNull String toString() {
        if (stream != null) {
            throw new IllegalStateException("a streaming JsonWriter has no in-memory document");
        }
        if (!emptyLevels.isEmpty()) {
            throw new IllegalStateException("unbalanced JSON document");
        }
        return out.toString();
    }

    private void close(char bracket) {
        if (awaitingValue) {
            throw new IllegalStateException("Expected a value before closing with " + bracket);
        }
        if (emptyLevels.isEmpty()) {
            throw new IllegalStateException("nothing open to close with " + bracket);
        }
        emptyLevels.pop();
        out.append(bracket);
        flushIfNeeded();
    }

    private void prefix() {
        if (awaitingValue) {
            awaitingValue = false;
            return;
        }
        separate();
    }

    private void separate() {
        if (!emptyLevels.isEmpty()) {
            if (!emptyLevels.peek()) {
                out.append(',');
            }
            emptyLevels.pop();
            emptyLevels.push(Boolean.FALSE);
        }
    }

    private void flushIfNeeded() {
        if (stream != null && out.length() >= STREAM_FLUSH_THRESHOLD) {
            stream.append(out);
            out.setLength(0);
        }
    }

    private static String quote(String value) {
        StringBuilder quoted = new StringBuilder(value.length() + 2);
        quoted.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                case '\b' -> quoted.append("\\b");
                case '\f' -> quoted.append("\\f");
                default -> {
                    if (character < 0x20) {
                        quoted.append('\\').append(String.format("u%04x", (int) character));
                    } else {
                        quoted.append(character);
                    }
                }
            }
        }
        quoted.append('"');
        return quoted.toString();
    }
}
