package io.github.lihuu.clicommon.system;

import org.jetbrains.annotations.NotNull;
import java.util.Locale;

/**
 * Operating system and host platform detector.
 */
public final class Platform {
    public enum OS {
        MACOS,
        WINDOWS,
        LINUX,
        UNKNOWN
    }

    private Platform() {}

    public static @NotNull OS detect(@NotNull String osName) {
        String lower = osName.toLowerCase(Locale.ROOT);
        if (lower.contains("mac") || lower.contains("darwin")) {
            return OS.MACOS;
        }
        if (lower.contains("win")) {
            return OS.WINDOWS;
        }
        if (lower.contains("linux")) {
            return OS.LINUX;
        }
        return OS.UNKNOWN;
    }

    public static @NotNull OS current() {
        return detect(System.getProperty("os.name", ""));
    }

    public static boolean isMac() {
        return current() == OS.MACOS;
    }

    public static boolean isWindows() {
        return current() == OS.WINDOWS;
    }

    public static boolean isLinux() {
        return current() == OS.LINUX;
    }
}
