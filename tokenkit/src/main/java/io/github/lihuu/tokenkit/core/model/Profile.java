package io.github.lihuu.tokenkit.core.model;

import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public record Profile(
        @NotNull String name,
        @Nullable String description,
        boolean active,
        long createdAt
) {
    public Profile {
        Objects.requireNonNull(name, "profile name must not be null");
    }
}
