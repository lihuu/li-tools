package io.github.lihuu.dukit.core.model;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Outcome of a bounded scan that keeps only the largest entries instead of a full tree.
 *
 * @param largestDirectories directories with the biggest subtrees, largest first
 * @param largestFiles       individual files with the biggest sizes, largest first
 * @param totalSize          total size of everything scanned
 * @param fileCount          number of regular files scanned
 * @param errors             entries that could not be read
 */
public record TopResult(
        @NotNull List<TopEntry> largestDirectories,
        @NotNull List<TopEntry> largestFiles,
        long totalSize,
        long fileCount,
        @NotNull List<ScanError> errors
) {
    public TopResult {
        largestDirectories = List.copyOf(largestDirectories);
        largestFiles = List.copyOf(largestFiles);
        errors = List.copyOf(errors);
    }
}
