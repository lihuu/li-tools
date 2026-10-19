package io.github.lihuu.tokenkit.core.crypto;

import org.jetbrains.annotations.NotNull;

public record EncryptedPayload(
        @NotNull byte[] nonce,
        @NotNull byte[] ciphertext
) {
}
