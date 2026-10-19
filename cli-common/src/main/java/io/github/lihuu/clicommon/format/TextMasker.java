package io.github.lihuu.clicommon.format;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Masking and formatting utilities for secrets and key fingerprints.
 */
public final class TextMasker {
    private TextMasker() {}

    public static @NotNull String mask(@Nullable String secret) {
        if (secret == null || secret.isEmpty()) {
            return "";
        }
        int length = secret.length();
        if (length <= 4) {
            return "*".repeat(length);
        }
        if (length <= 8) {
            return secret.charAt(0) + "••••" + secret.charAt(length - 1);
        }
        String prefix = secret.substring(0, Math.min(5, length / 3));
        String suffix = secret.substring(length - Math.min(4, length / 3));
        return prefix + "••••" + suffix;
    }

    public static @NotNull String fingerprint(@NotNull String rawHex) {
        if (rawHex.isEmpty()) {
            return "";
        }
        String clean = rawHex.replaceAll("\\s+", "").toUpperCase();
        if (clean.length() != 40) {
            return clean;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i += 4) {
            sb.append(clean, i, i + 4);
            if (i == 16) {
                sb.append("  ");
            } else if (i < 36) {
                sb.append(" ");
            }
        }
        return sb.toString();
    }
}
