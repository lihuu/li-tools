package io.github.lihuu.dukit.core.scan;

/**
 * Scratch values for one filesystem entry, reused across the entries a task visits so a
 * scan does not allocate one attribute object per file.
 *
 * <p>{@link #linkCount} is {@code -1} when the platform does not expose it. In that case
 * {@link #fileKey} carries the JDK's opaque key instead, and hardlink detection falls back
 * to identity comparison.
 */
final class FileStat {
    enum Kind {
        DIRECTORY,
        REGULAR_FILE,
        SYMBOLIC_LINK,
        OTHER
    }

    Kind kind = Kind.OTHER;
    long size;
    /** Allocated blocks in 512-byte units; {@code -1} when the platform does not expose it. */
    long blocks = -1;
    long linkCount = -1;
    long device;
    long inode;
    Object fileKey;
}
