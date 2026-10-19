package io.github.lihuu.tokenkit.core.model;

import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public record Token(
        @NotNull String id,
        @NotNull String profile,
        @NotNull String name,
        @NotNull String kind,
        byte @NotNull [] nonce,
        byte @NotNull [] ciphertext,
        @Nullable String description,
        @Nullable String tags,
        @Nullable Long expiresAt,
        long createdAt,
        long updatedAt
) {
    public Token {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(profile, "profile must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(nonce, "nonce must not be null");
        Objects.requireNonNull(ciphertext, "ciphertext must not be null");
    }

    public static @NotNull String mask(@Nullable String secret) {
        return io.github.lihuu.clicommon.format.TextMasker.mask(secret);
    }
}
