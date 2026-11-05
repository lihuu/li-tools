package io.github.lihuu.dukit.core.delete;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A delete request was refused before anything was touched.
 */
public class DeleteException extends Exception {
    public enum Reason {
        NOT_IN_TREE,
        ROOT,
        NOT_A_DIRECTORY,
        SYMBOLIC_LINK,
        MOUNT_POINT,
        IS_TRASH,
        CONTAINS_TRASH,
        NOT_FOUND,
        UNREADABLE
    }

    private final Reason reason;

    public DeleteException(@NotNull Reason reason, @NotNull String message) {
        super(message);
        this.reason = reason;
    }

    public DeleteException(@NotNull Reason reason, @NotNull String message, @Nullable Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public @NotNull Reason reason() {
        return reason;
    }
}
