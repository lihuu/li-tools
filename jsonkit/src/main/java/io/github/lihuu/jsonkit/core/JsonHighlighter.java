package io.github.lihuu.jsonkit.core;

import org.jetbrains.annotations.NotNull;

/** Wraps JSON tokens in ANSI color codes when the destination is a terminal. */
public final class JsonHighlighter {
    private static final String RESET = "\u001b[0m";
    private static final JsonHighlighter PLAIN = new JsonHighlighter(false);
    private static final JsonHighlighter ANSI = new JsonHighlighter(true);

    private final boolean ansi;

    private JsonHighlighter(boolean ansi) {
        this.ansi = ansi;
    }

    public static @NotNull JsonHighlighter plain() {
        return PLAIN;
    }

    public static @NotNull JsonHighlighter ansi() {
        return ANSI;
    }

    public @NotNull String key(@NotNull String quoted) {
        return wrap("\u001b[36;1m", quoted);
    }

    public @NotNull String string(@NotNull String quoted) {
        return wrap("\u001b[32m", quoted);
    }

    public @NotNull String number(@NotNull String literal) {
        return wrap("\u001b[33m", literal);
    }

    public @NotNull String literal(@NotNull String literal) {
        return wrap("\u001b[35m", literal);
    }

    private @NotNull String wrap(@NotNull String code, @NotNull String text) {
        return ansi ? code + text + RESET : text;
    }
}
