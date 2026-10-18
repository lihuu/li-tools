package io.github.lihuu.tokenkit.core.model;

import java.util.Objects;
import org.jetbrains.annotations.NotNull;

public record VaultMetadata(
        @NotNull String key,
        @NotNull String value
) {
    public VaultMetadata {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");
    }
}
