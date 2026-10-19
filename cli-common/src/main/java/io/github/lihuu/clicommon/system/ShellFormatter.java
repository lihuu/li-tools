package io.github.lihuu.clicommon.system;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.Locale;

/**
 * Shell-specific environment variable export and escaping utility.
 */
public final class ShellFormatter {
    private ShellFormatter() {}

    public static @NotNull String formatExport(@Nullable String shell, @NotNull String name, @NotNull String value) {
        String targetShell = (shell != null && !shell.trim().isEmpty())
                ? shell.trim().toLowerCase(Locale.ROOT)
                : detectDefaultShell();

        return switch (targetShell) {
            case "pwsh", "powershell" -> formatPowerShell(name, value);
            case "fish" -> formatFish(name, value);
            default -> formatPosix(name, value);
        };
    }

    public static @NotNull String detectDefaultShell() {
        String envShell = System.getenv("SHELL");
        if (envShell != null && !envShell.trim().isEmpty()) {
            String lower = envShell.toLowerCase(Locale.ROOT);
            if (lower.contains("zsh")) {
                return "zsh";
            }
            if (lower.contains("bash")) {
                return "bash";
            }
            if (lower.contains("fish")) {
                return "fish";
            }
        }
        if (Platform.isWindows()) {
            return "pwsh";
        }
        return "zsh";
    }

    private static String formatPosix(String name, String value) {
        String escaped = value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("$", "\\$")
                .replace("`", "\\`");
        return "export " + name + "=\"" + escaped + "\"";
    }

    private static String formatPowerShell(String name, String value) {
        String escaped = value
                .replace("`", "``")
                .replace("\"", "`\"")
                .replace("$", "`$");
        return "$env:" + name + " = \"" + escaped + "\"";
    }

    private static String formatFish(String name, String value) {
        String escaped = value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("$", "\\$");
        return "set -gx " + name + " \"" + escaped + "\"";
    }
}
