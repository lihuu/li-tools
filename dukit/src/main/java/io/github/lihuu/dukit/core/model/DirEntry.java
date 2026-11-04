package io.github.lihuu.dukit.core.model;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;

/**
 * Immutable aggregate for one directory subtree produced by a scan.
 *
 * <p>Sizes are apparent sizes: the sum of logical file sizes as reported by the platform,
 * including the directory's own size. This is the only size metric the JDK exposes
 * portably (allocated block counts are not available through java.nio.file), and it keeps
 * numbers consistent across macOS, Linux and Windows. Symlinks contribute neither size nor
 * file counts. Hardlinked files count their sizes once per scan; every hardlink still
 * counts toward {@code fileCount}.
 *
 * @param name        entry name; the root entry carries the scanned path's file name
 * @param path        absolute path of the entry
 * @param directory   true when this entry is a directory, false for regular files
 * @param size        apparent size of the subtree in bytes, including the directory's own size
 * @param fileCount   regular files in the subtree, including hardlink twins
 * @param dirCount    subdirectories in the subtree, excluding this directory itself
 * @param errorCount  entries under this subtree that could not be read
 * @param children    subdirectories and regular files, sorted by size descending, then name
 */
public record DirEntry(
        @NotNull String name,
        @NotNull Path path,
        boolean directory,
        long size,
        long fileCount,
        long dirCount,
        long errorCount,
        @NotNull List<DirEntry> children
) {
    public DirEntry {
        children = List.copyOf(children);
    }
}
