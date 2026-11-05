package io.github.lihuu.dukit.core.model;

import org.jetbrains.annotations.NotNull;

/**
 * One row in the interactive listing: either a directory aggregate or one of the largest
 * regular files directly inside the current directory.
 *
 * <p>A file row is display-only. Navigation and deletion stay directory-oriented, so a
 * {@code FileEntry} never becomes part of the navigation trail.
 */
public sealed interface TreeEntry permits DirEntry, FileEntry {
    @NotNull String name();

    /** Apparent (logical) size in bytes. */
    long size();

    /** Space actually occupied; equals {@link #size()} where the platform reports no blocks. */
    long diskSize();
}
