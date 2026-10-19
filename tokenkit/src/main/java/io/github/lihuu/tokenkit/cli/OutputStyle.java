package io.github.lihuu.tokenkit.cli;

import org.jetbrains.annotations.NotNull;
import java.util.Map;

/**
 * Adapter delegating to cli-common OutputStyle.
 */
public final class OutputStyle {
    private final io.github.lihuu.clicommon.style.OutputStyle delegate;

    private OutputStyle(io.github.lihuu.clicommon.style.OutputStyle delegate) {
        this.delegate = delegate;
    }

    public static @NotNull OutputStyle plain() {
        return new OutputStyle(io.github.lihuu.clicommon.style.OutputStyle.plain());
    }

    public static @NotNull OutputStyle ansi() {
        return new OutputStyle(io.github.lihuu.clicommon.style.OutputStyle.ansi());
    }

    public static @NotNull OutputStyle of(@NotNull Map<String, String> environment, boolean interactive, boolean noColorFlag) {
        return new OutputStyle(io.github.lihuu.clicommon.style.OutputStyle.of(environment, interactive, noColorFlag));
    }

    public boolean isAnsi() {
        return delegate.isAnsi();
    }

    public @NotNull String success(@NotNull String text) {
        return delegate.success(text);
    }

    public @NotNull String warning(@NotNull String text) {
        return delegate.warning(text);
    }

    public @NotNull String error(@NotNull String text) {
        return delegate.error(text);
    }

    public @NotNull String highlight(@NotNull String text) {
        return delegate.highlight(text);
    }

    public @NotNull String muted(@NotNull String text) {
        return delegate.muted(text);
    }

    public @NotNull String bold(@NotNull String text) {
        return delegate.bold(text);
    }

    public @NotNull io.github.lihuu.clicommon.style.OutputStyle unwrap() {
        return delegate;
    }
}
