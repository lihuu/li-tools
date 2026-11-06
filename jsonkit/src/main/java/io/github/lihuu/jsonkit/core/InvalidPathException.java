package io.github.lihuu.jsonkit.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** A JSONPath expression that cannot be used, reported as a CLI usage error. */
public final class InvalidPathException extends Exception {
    public InvalidPathException(@NotNull String message) {
        super(message);
    }

    public InvalidPathException(@NotNull String expression, @NotNull String reason, int position, @Nullable Throwable cause) {
        super("invalid JSONPath \"" + expression + "\"" + positionSuffix(position) + ": " + reason, cause);
    }

    private static @NotNull String positionSuffix(int position) {
        return position >= 0 ? " at position " + position : "";
    }
}
