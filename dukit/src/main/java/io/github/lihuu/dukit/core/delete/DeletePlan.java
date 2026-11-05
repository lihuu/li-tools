package io.github.lihuu.dukit.core.delete;

import io.github.lihuu.dukit.core.trash.TrashStore;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/**
 * One confirmed delete: what will move, how big it is, and where it will land.
 *
 * @param path      absolute path of the directory being removed
 * @param name      entry name, for the confirmation page
 * @param size      apparent size of the subtree
 * @param files     regular files in the subtree
 * @param directories subdirectories in the subtree
 * @param trash     prepared Trash destination
 */
public record DeletePlan(
        @NotNull Path path,
        @NotNull String name,
        long size,
        long files,
        long directories,
        @NotNull TrashStore.TrashTarget trash
) {
}
