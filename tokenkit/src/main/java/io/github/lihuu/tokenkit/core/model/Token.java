package io.github.lihuu.tokenkit.core.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public record Token(
        @NotNull String id,
        @NotNull String profile,
        @NotNull String name,
        @NotNull String kind,
        @NotNull byte[] nonce,
        @NotNull byte[] ciphertext,
        @Nullable String description,
        @Nullable String tags,
        @Nullable Long expiresAt,
        long createdAt,
        long updatedAt
) {
    public static @NotNull String mask(@Nullable String secret) {
        return io.github.lihuu.clicommon.format.TextMasker.mask(secret);
    }
}
