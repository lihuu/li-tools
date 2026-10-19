package io.github.lihuu.tokenkit.util;

import org.jetbrains.annotations.NotNull;

/**
 * Adapter delegating to cli-common ClipboardUtil.
 */
public final class ClipboardUtil {
    private ClipboardUtil() {}

    public static boolean copyToClipboard(@NotNull String text) {
        return io.github.lihuu.clicommon.system.ClipboardUtil.copyToClipboard(text);
    }
}
