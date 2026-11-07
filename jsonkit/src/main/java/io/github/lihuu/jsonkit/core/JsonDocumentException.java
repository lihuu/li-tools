package io.github.lihuu.jsonkit.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** An input that is not one complete JSON document, with the position where reading failed. */
public final class JsonDocumentException extends Exception {
    private final String source;
    private final long line;
    private final long column;

    public JsonDocumentException(
            @NotNull String source,
            long line,
            long column,
            @NotNull String detail,
            @Nullable Throwable cause
    ) {
        super(diagnostic(source, line, column, detail), cause);
        this.source = source;
        this.line = line;
        this.column = column;
    }

    public static @NotNull JsonDocumentException empty(@NotNull String source) {
        return new JsonDocumentException(source, 0, 0, "empty input", null);
    }

    public @NotNull String source() {
        return source;
    }

    public long line() {
        return line;
    }

    public long column() {
        return column;
    }

    private static @NotNull String diagnostic(@NotNull String source, long line, long column, @NotNull String detail) {
        if (line > 0 && column > 0) {
            return source + ":" + line + ":" + column + ": " + detail;
        }
        if (line > 0) {
            return source + ":" + line + ": " + detail;
        }
        return source + ": " + detail;
    }
}
