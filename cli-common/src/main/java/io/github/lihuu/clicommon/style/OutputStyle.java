package io.github.lihuu.clicommon.style;

import io.github.lihuu.clicommon.system.Platform;
import org.jetbrains.annotations.NotNull;
import java.util.Map;

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
        return of(environment, interactive, noColorFlag, Platform.current());
    }

    static @NotNull OutputStyle of(
            @NotNull Map<String, String> environment,
            boolean interactive,
            boolean noColorFlag,
            @NotNull Platform.OS platform
    ) {
        if (noColorFlag || environment.containsKey("NO_COLOR") || !interactive) {
            return PLAIN;
        }
        String term = environment.getOrDefault("TERM", "");
        if ("dumb".equalsIgnoreCase(term)) {
            return PLAIN;
        }
        // Windows terminals such as Windows Terminal support ANSI but usually leave TERM unset.
        if (term.isBlank() && platform != Platform.OS.WINDOWS) {
            return PLAIN;
        }
        return ANSI;
    }

    public boolean isAnsi() {
        return ansi;
    }

    public @NotNull String success(@NotNull String text) {
        return ansi ? "\u001b[32;1m✔ " + text + "\u001b[0m" : "✔ " + text;
    }

    public @NotNull String warning(@NotNull String text) {
        return ansi ? "\u001b[33;1m⚠ " + text + "\u001b[0m" : "⚠ " + text;
    }

    public @NotNull String error(@NotNull String text) {
        return ansi ? "\u001b[31;1m✖ " + text + "\u001b[0m" : "✖ " + text;
    }

    public @NotNull String info(@NotNull String text) {
        return ansi ? "\u001b[34;1mℹ " + text + "\u001b[0m" : "ℹ " + text;
    }

    public @NotNull String tip(@NotNull String text) {
        return "💡 " + text;
    }

    public @NotNull String highlight(@NotNull String text) {
        return ansi ? "\u001b[36;1m" + text + "\u001b[0m" : text;
    }

    public @NotNull String muted(@NotNull String text) {
        return ansi ? "\u001b[90m" + text + "\u001b[0m" : text;
    }

    public @NotNull String bold(@NotNull String text) {
        return ansi ? "\u001b[1m" + text + "\u001b[0m" : text;
    }
}
