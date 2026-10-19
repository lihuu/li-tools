package io.github.lihuu.tokenkit.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Adapter delegating to cli-common ShellFormatter.
 */
public final class ShellFormatter {
    private ShellFormatter() {}

    public static @NotNull String formatExport(@Nullable String shell, @NotNull String name, @NotNull String value) {
        return io.github.lihuu.clicommon.system.ShellFormatter.formatExport(shell, name, value);
    }

    public static @NotNull String detectDefaultShell() {
        return io.github.lihuu.clicommon.system.ShellFormatter.detectDefaultShell();
    }
}
