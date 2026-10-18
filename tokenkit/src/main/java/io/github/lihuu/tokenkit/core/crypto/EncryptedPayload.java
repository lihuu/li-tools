package io.github.lihuu.tokenkit.core.crypto;

import java.util.Objects;
import org.jetbrains.annotations.NotNull;

public record EncryptedPayload(
        byte @NotNull [] nonce,
        byte @NotNull [] ciphertext
) {
    public EncryptedPayload {
        Objects.requireNonNull(nonce, "nonce must not be null");
        Objects.requireNonNull(ciphertext, "ciphertext must not be null");
    }
}
