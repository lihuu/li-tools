package io.github.lihuu.dukit.core.delete;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * Outcome of executing one {@link DeletePlan}.
 *
 * @param path      the entry the plan targeted
 * @param status    what happened
 * @param error     human-readable reason when {@code status} is {@code FAILED}
 * @param uncertain true when the platform may have moved something before failing, so the
 *                  in-memory tree can no longer be trusted
 */
public record DeleteResult(
        @NotNull Path path,
        @NotNull Status status,
        @Nullable String error,
        boolean uncertain
) {
    public enum Status {
        TRASHED,
        FAILED
    }

    public static @NotNull DeleteResult trashed(@NotNull Path path) {
        return new DeleteResult(path, Status.TRASHED, null, false);
    }

    public static @NotNull DeleteResult failed(@NotNull Path path, @NotNull String error, boolean uncertain) {
        return new DeleteResult(path, Status.FAILED, error, uncertain);
    }
}
