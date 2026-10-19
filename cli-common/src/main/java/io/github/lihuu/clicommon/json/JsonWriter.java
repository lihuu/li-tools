package io.github.lihuu.clicommon.json;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * Lightweight, zero-reflection streaming JSON writer.
 * Compatible with GraalVM Native Image without reflection configuration.
 */
public final class JsonWriter {
    private final StringBuilder out = new StringBuilder();
    private final Deque<Boolean> emptyLevels = new ArrayDeque<>();
    private boolean awaitingValue;

    public @NotNull JsonWriter beginObject() {
        prefix();
        out.append('{');
        emptyLevels.push(Boolean.TRUE);
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
        return this;
    }

    public @NotNull JsonWriter endArray() {
        close(']');
        return this;
    }

    public @NotNull JsonWriter name(@NotNull String name) {
        Objects.requireNonNull(name, "name must not be null");
        if (emptyLevels.isEmpty()) {
            throw new IllegalStateException("member name outside an object");
        }
        separate();
        out.append(quote(name)).append(": ");
        awaitingValue = true;
        return this;
    }

    public @NotNull JsonWriter value(@Nullable String value) {
        prefix();
        out.append(value == null ? "null" : quote(value));
        return this;
    }

    public @NotNull JsonWriter value(long value) {
        prefix();
        out.append(value);
        return this;
    }

    public @NotNull JsonWriter value(double value) {
        prefix();
        out.append(value);
        return this;
    }

    public @NotNull JsonWriter value(boolean value) {
        prefix();
        out.append(value);
        return this;
    }

    public @NotNull JsonWriter nullValue() {
        prefix();
        out.append("null");
        return this;
    }

    @Override
    public @NotNull String toString() {
        if (!emptyLevels.isEmpty()) {
            throw new IllegalStateException("unbalanced JSON document");
        }
        return out.toString();
    }

    private void close(char bracket) {
        if (emptyLevels.isEmpty()) {
            throw new IllegalStateException("nothing open to close with " + bracket);
        }
        emptyLevels.pop();
        out.append(bracket);
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
