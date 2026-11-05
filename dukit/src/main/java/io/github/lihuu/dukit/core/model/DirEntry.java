package io.github.lihuu.dukit.core.model;

import org.jetbrains.annotations.NotNull;

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
 * <p>{@link #topFiles} is a bounded preview of the largest regular files directly inside the
 * directory. The scanner fills it only when the caller asks (the interactive browser), and
 * the JSON reporter never serializes it, so an ordinary scan keeps its small payload.
 *
 * <p>Nodes carry no path. A scan's root path lives on {@link ScanResult}, and a browser
 * reconstructs the current path from its navigation trail, which keeps one
 * {@code Path}/{@code String}/{@code int[]} trio out of every node in a large tree.
 *
 * <p>Two size metrics are carried side by side. {@link #diskSize} is the space the subtree
 * actually occupies — allocated blocks on macOS and Linux, which is what {@code du} and the
 * system file manager report. {@link #size} is the apparent (logical) size, which differs
 * wildly for sparse files and APFS clones and is the only metric available on Windows, where
 * java.nio.file exposes no block count.
 *
 * <p>Symlinks contribute neither size nor file counts. Hardlinked files count their sizes
 * once per scan; every hardlink still counts toward {@code fileCount}.
 *
 * @param name          directory name; the root entry carries the scanned path's file name
 * @param diskSize      space the subtree occupies; falls back to {@code size} where the
 *                      platform exposes no block count
 * @param size          apparent (logical) size of the subtree, including own files
 * @param fileCount     regular files in the subtree, including hardlink twins
 * @param dirCount      subdirectories in the subtree, excluding this directory itself
 * @param errorCount    entries under this subtree that could not be read
 * @param ownFileSize   size of the regular files directly inside this directory
 * @param ownFileCount  number of regular files directly inside this directory
 * @param topFiles      largest regular files directly inside this directory, largest first;
 *                      empty when the scan did not request a file preview
 * @param children      subdirectories, sorted by size descending, then name
 */
public record DirEntry(
        @NotNull String name,
        long diskSize,
        long size,
        long fileCount,
        long dirCount,
        long errorCount,
        long ownFileSize,
        long ownFileCount,
        @NotNull List<FileEntry> topFiles,
        @NotNull List<DirEntry> children
) implements TreeEntry {
    public DirEntry {
        topFiles = List.copyOf(topFiles);
        children = List.copyOf(children);
    }
}
