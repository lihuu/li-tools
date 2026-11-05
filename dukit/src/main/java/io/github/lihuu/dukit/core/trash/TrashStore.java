package io.github.lihuu.dukit.core.trash;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * The system Trash as a boundary: the only thing in the delete path that is allowed to
 * touch the filesystem destructively.
 *
 * <p>{@code prepare} is read-only and exists so a confirmation page can show where the
 * entry would go; {@code move} performs the move. Implementations must never fall back to a
 * permanent delete.
 */
public interface TrashStore {

    /**
     * Resolves where {@code target} would land, without creating directories or files.
     */
    @NotNull TrashTarget prepare(@NotNull Path target) throws TrashException;

    /**
     * Moves {@code target} into the Trash. Never deletes permanently.
     */
    @NotNull TrashReceipt move(@NotNull TrashTarget target) throws TrashException;

    /**
     * A prepared destination: the source to move and where it is going.
     *
     * @param source      absolute path of the entry to move
     * @param destination where the platform will place it; {@code null} when the platform
     *                    decides the final name itself (Windows Recycle Bin)
     * @param trashRoot   root of the Trash that will receive it, used to refuse deleting
     *                    anything already inside the Trash
     * @param display     human-readable destination for the confirmation page
     */
    record TrashTarget(@NotNull Path source, @Nullable Path destination, @NotNull Path trashRoot,
            @NotNull String display) {
    }

    /**
     * What actually happened.
     *
     * @param source      the entry that was moved
     * @param destination where it ended up, when the platform reports it
     */
    record TrashReceipt(@NotNull Path source, @Nullable Path destination) {
    }
}
