package io.github.lihuu.tokenkit.core.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public record Profile(
        @NotNull String name,
        @Nullable String description,
        boolean active,
        long createdAt
) {
}
