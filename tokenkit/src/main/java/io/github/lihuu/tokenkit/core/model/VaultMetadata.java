package io.github.lihuu.tokenkit.core.model;

import org.jetbrains.annotations.NotNull;

public record VaultMetadata(
        @NotNull String key,
        @NotNull String value
) {
}
