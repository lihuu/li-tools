package io.github.lihuu.dukit.core.model;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;

/**
 * Immutable aggregate for one directory subtree produced by a scan.
 *
 * <p>Only directories appear in the tree; regular files are folded into their containing
 * directory's counters instead of becoming nodes. That keeps the tree proportional to the
 * directory count rather than the file count — an order of magnitude smaller on real
 * filesystems — while {@link io.github.lihuu.dukit.core.scan.TopCollector} still surfaces
 * the largest individual files when asked.
 *
 * <p>Sizes are apparent sizes: the sum of logical file sizes as reported by the platform.
 * This is the only size metric the JDK exposes portably (allocated block counts are not
 * available through java.nio.file), and it keeps numbers consistent across macOS, Linux and
 * Windows. Symlinks contribute neither size nor file counts. Hardlinked files count their
 * sizes once per scan; every hardlink still counts toward {@code fileCount}.
 *
 * @param name          directory name; the root entry carries the scanned path's file name
 * @param path          absolute path of the directory
 * @param size          total size of the subtree, including the directory's own files
 * @param fileCount     regular files in the subtree, including hardlink twins
 * @param dirCount      subdirectories in the subtree, excluding this directory itself
 * @param errorCount    entries under this subtree that could not be read
 * @param ownFileSize   size of the regular files directly inside this directory
 * @param ownFileCount  number of regular files directly inside this directory
 * @param children      subdirectories, sorted by size descending, then name
 */
public record DirEntry(
        @NotNull String name,
        @NotNull Path path,
        long size,
        long fileCount,
        long dirCount,
        long errorCount,
        long ownFileSize,
        long ownFileCount,
        @NotNull List<DirEntry> children
) {
    public DirEntry {
        children = List.copyOf(children);
    }
}
