package io.github.lihuu.dukit.core.delete;

import io.github.lihuu.dukit.core.model.DirEntry;
import io.github.lihuu.dukit.core.model.ScanResult;
import io.github.lihuu.dukit.core.trash.TrashException;
import io.github.lihuu.dukit.core.trash.TrashStore;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/**
 * Plans and executes one delete. The only class that talks to a {@link TrashStore}.
 *
 * <p>Planning is read-only and produces everything the confirmation page needs. Execution
 * re-validates the target before moving, because the plan may be minutes old by then.
 */
public final class DeleteService {
    private final TrashStore trash;

    public DeleteService(@NotNull TrashStore trash) {
        this.trash = trash;
    }

    /**
     * @param scan        the tree the user is browsing
     * @param currentPath absolute path of the directory being shown
     * @param entry       the child directory the user selected
     */
    public @NotNull DeletePlan plan(@NotNull ScanResult scan, @NotNull String currentPath, @NotNull DirEntry entry)
            throws DeleteException, TrashException {
        Path target = Path.of(currentPath).resolve(entry.name()).toAbsolutePath().normalize();
        DeleteGuard.validate(scan.path(), target);
        TrashStore.TrashTarget trashTarget = trash.prepare(target);
        DeleteGuard.validateTrashRelation(target, trashTarget);
        return new DeletePlan(target, entry.name(), entry.size(), entry.fileCount(), entry.dirCount(), trashTarget);
    }

    /**
     * Moves the planned target to the Trash. Never throws: the caller gets a result it can
     * render and, when the outcome is uncertain, a signal to stop trusting the tree.
     */
    public @NotNull DeleteResult execute(@NotNull ScanResult scan, @NotNull DeletePlan plan) {
        try {
            DeleteGuard.validate(scan.path(), plan.path());
            trash.move(plan.trash());
            return DeleteResult.trashed(plan.path());
        } catch (DeleteException refusal) {
            return DeleteResult.failed(plan.path(), refusal.getMessage(), false);
        } catch (TrashException failure) {
            boolean uncertain = failure.outcome() == TrashException.Outcome.UNKNOWN;
            return DeleteResult.failed(plan.path(), failure.getMessage(), uncertain);
        }
    }
}
