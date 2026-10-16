package io.github.lihuu.gpgkit;

/**
 * The smallest JSON writer that covers what this tool prints: objects, arrays, strings, numbers.
 *
 * <p>Hand-written rather than pulled in, because a serializer is exactly the kind of library that
 * Native Image cannot see through: it reflects over the classes it writes, and reflection is what
 * turns "it compiled" into "the field is silently missing from the executable".
 *
 * <p>Not a general JSON library. There is no parser, no pretty printer, and no type mapping —
 * callers build the document they want to print, member by member.
 *
 * <p>Every method returns {@code this} so a document reads as one expression.
 */
final class Json {
    private final StringBuilder out = new StringBuilder();

    /**
     * Per open object or array: true while nothing has been written inside it yet, which is what
     * decides whether the next member needs a comma. A stack rather than a depth counter because
     * "empty so far" is a property of each level, not of the document.
     */
    private final java.util.Deque<Boolean> emptyLevels = new java.util.ArrayDeque<>();

    /** Set between {@link #name} and the value that follows it, which must not get a comma. */
    private boolean awaitingValue;

    Json beginObject() {
        prefix();
        out.append('{');
        emptyLevels.push(Boolean.TRUE);
        return this;
    }

    Json endObject() {
        close('}');
        return this;
    }

    Json beginArray() {
        prefix();
        out.append('[');
        emptyLevels.push(Boolean.TRUE);
        return this;
    }

    Json endArray() {
        close(']');
        return this;
    }

    /** Starts a member of the object currently open. */
    Json name(String name) {
        if (emptyLevels.isEmpty()) {
            throw new IllegalStateException("member name outside an object");
        }
        separate();
        out.append(quote(name)).append(':');
        awaitingValue = true;
        return this;
    }

    Json value(String value) {
        prefix();
        out.append(value == null ? "null" : quote(value));
        return this;
    }

    Json value(long value) {
        prefix();
        out.append(value);
        return this;
    }

    Json value(boolean value) {
        prefix();
        out.append(value);
        return this;
    }

    Json nullValue() {
        prefix();
        out.append("null");
        return this;
    }

    @Override
    public String toString() {
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

    /** Writes whatever must precede a value: a comma, or nothing when it follows a member name. */
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

    /**
     * Quotes a string. Non-ASCII is written through as-is: the output stream is pinned to UTF-8
     * and every JSON consumer reads UTF-8, so escaping Chinese into backslash-u escapes would
     * only make the raw output unreadable for no gain.
     */
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
                        // Written as a backslash plus "u%04x" rather than one literal, because javac
                        // expands backslash-u anywhere in a source file — including inside a
                        // string literal, where it is a compile error rather than the two
                        // characters this is trying to produce.
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
