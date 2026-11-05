package io.github.lihuu.dukit.core.trash;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A platform Trash operation refused or failed.
 *
 * <p>{@link Outcome} is the part callers must not ignore: {@code NOT_STARTED} means the
 * filesystem is exactly as it was, while {@code UNKNOWN} means the platform may have moved
 * something before failing — the caller must treat the tree as stale and stop deleting.
 */
public class TrashException extends Exception {
    public enum Reason {
        UNSUPPORTED_PLATFORM,
        NO_TRASH,
        NETWORK_VOLUME,
        CROSS_DEVICE,
        PATH_TOO_LONG,
        MOUNT_POINT,
        REPARSE_POINT,
        TARGET_IS_TRASH,
        TARGET_CONTAINS_TRASH,
        NOT_FOUND,
        TARGET_CHANGED,
        PERMISSION_DENIED,
        ABORTED,
        IO_ERROR
    }

    public enum Outcome {
        NOT_STARTED,
        UNKNOWN
    }

    private final Reason reason;
    private final Outcome outcome;

    public TrashException(@NotNull Reason reason, @NotNull Outcome outcome, @NotNull String message) {
        super(message);
        this.reason = reason;
        this.outcome = outcome;
    }

    public TrashException(@NotNull Reason reason, @NotNull Outcome outcome, @NotNull String message,
            @Nullable Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.outcome = outcome;
    }

    public @NotNull Reason reason() {
        return reason;
    }

    public @NotNull Outcome outcome() {
        return outcome;
    }
}
