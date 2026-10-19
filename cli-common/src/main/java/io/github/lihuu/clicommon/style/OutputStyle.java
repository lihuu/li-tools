package io.github.lihuu.clicommon.style;

import org.jetbrains.annotations.NotNull;
import java.util.Map;
import java.util.Objects;

/**
 * Terminal output styling with automatic NO_COLOR, TERM=dumb, and non-interactive detection.
 */
public final class OutputStyle {
    private static final OutputStyle PLAIN = new OutputStyle(false);
    private static final OutputStyle ANSI = new OutputStyle(true);

    private final boolean ansi;

    private OutputStyle(boolean ansi) {
        this.ansi = ansi;
    }

    public static @NotNull OutputStyle plain() {
        return PLAIN;
    }

    public static @NotNull OutputStyle ansi() {
        return ANSI;
    }

    public static @NotNull OutputStyle of(@NotNull Map<String, String> environment, boolean interactive, boolean noColorFlag) {
        Objects.requireNonNull(environment, "environment must not be null");
        if (noColorFlag || environment.containsKey("NO_COLOR")) {
            return PLAIN;
        }
        String term = environment.getOrDefault("TERM", "");
        if (!interactive || term.isBlank() || "dumb".equalsIgnoreCase(term)) {
            return PLAIN;
        }
        return ANSI;
    }

    public boolean isAnsi() {
        return ansi;
    }

    public @NotNull String success(@NotNull String text) {
        Objects.requireNonNull(text, "text must not be null");
        return ansi ? "\u001b[32;1m✔ " + text + "\u001b[0m" : "✔ " + text;
    }

    public @NotNull String warning(@NotNull String text) {
        Objects.requireNonNull(text, "text must not be null");
        return ansi ? "\u001b[33;1m⚠ " + text + "\u001b[0m" : "⚠ " + text;
    }

    public @NotNull String error(@NotNull String text) {
        Objects.requireNonNull(text, "text must not be null");
        return ansi ? "\u001b[31;1m✖ " + text + "\u001b[0m" : "✖ " + text;
    }

    public @NotNull String info(@NotNull String text) {
        Objects.requireNonNull(text, "text must not be null");
        return ansi ? "\u001b[34;1mℹ " + text + "\u001b[0m" : "ℹ " + text;
    }

    public @NotNull String tip(@NotNull String text) {
        Objects.requireNonNull(text, "text must not be null");
        return "💡 " + text;
    }

    public @NotNull String highlight(@NotNull String text) {
        Objects.requireNonNull(text, "text must not be null");
        return ansi ? "\u001b[36;1m" + text + "\u001b[0m" : text;
    }

    public @NotNull String muted(@NotNull String text) {
        Objects.requireNonNull(text, "text must not be null");
        return ansi ? "\u001b[90m" + text + "\u001b[0m" : text;
    }

    public @NotNull String bold(@NotNull String text) {
        Objects.requireNonNull(text, "text must not be null");
        return ansi ? "\u001b[1m" + text + "\u001b[0m" : text;
    }
}
