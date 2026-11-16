package io.github.lihuu.dukit.core.scan;

import io.github.lihuu.dukit.core.model.FileEntry;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Lists the regular files directly inside one directory by reading it again, instead of
 * remembering every file during the scan.
 *
 * <p>The tree deliberately holds directories only: a per-directory file list for a whole
 * filesystem costs one object per file and the scan is designed to walk trees with hundreds
 * of thousands of them. The browser only ever shows one directory at a time, so it asks for
 * that one directory on demand — exact, including files the bounded scan preview never
 * retained, and flat in memory no matter how big the tree is.
 *
 * <p>Rows follow the same rules as the scan: symlinks and other reparse points are skipped,
 * hardlinked content is listed once (the first name wins), and files the user excluded stay
 * excluded. Sizes are measured the same way the scan measures them, so a listing adds up to
 * the numbers the header shows.
 */
public final class DirectoryLister {
    /**
     * Files listed per directory. A directory with more is truncated to the largest ones;
     * the listing reports the full count so the browser can say what it left out.
     */
    public static final int LIMIT = 2000;

    /**
     * Blocks are coarse: on APFS and ext4 every file below one block occupies the same, so
     * the occupied-size comparison ties constantly in real directories. Content size breaks
     * the tie before the name does, which is what a user reading a size-ranked list expects.
     */
    private static final Comparator<FileEntry> LARGEST_FIRST = Comparator
            .comparingLong(FileEntry::diskSize).reversed()
            .thenComparing(Comparator.comparingLong(FileEntry::size).reversed())
            .thenComparing(FileEntry::name);

    private DirectoryLister() {
    }

    public static @NotNull Listing list(@NotNull Path directory, @NotNull Path root) {
        return list(directory, root, Exclusions.none(), LIMIT);
    }

    /**
     * @param directory  directory to read
     * @param root       absolute scan root, the anchor for relative exclude patterns
     * @param exclusions what the scan was told to skip
     * @param limit      maximum files returned, largest first
     */
    public static @NotNull Listing list(@NotNull Path directory, @NotNull Path root,
            @NotNull Exclusions exclusions, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }
        List<FileEntry> listed = new ArrayList<>();
        long total = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            // Keep the largest `limit` without materializing the rest: the smallest survivor
            // sits on top of the heap, so a candidate is compared once and usually dropped.
            PriorityQueue<FileEntry> smallestFirst = new PriorityQueue<>(LARGEST_FIRST.reversed());
            try (StatReader.Cursor cursor = StatReader.create().open()) {
                FileStat stat = new FileStat();
                HardlinkSet seenLinks = new HardlinkSet();
                for (Path entry : entries) {
                    if (exclusions.matches(root, entry)) {
                        continue;
                    }
                    try {
                        cursor.read(entry, stat);
                    } catch (IOException unreadable) {
                        continue;
                    }
                    if (stat.kind != FileStat.Kind.REGULAR_FILE) {
                        continue;
                    }
                    if (stat.linkCount > 1 && !seenLinks.add(stat.device, stat.inode)) {
                        continue;
                    }
                    total++;
                    FileEntry candidate = new FileEntry(name(entry), stat.size, diskSize(stat));
                    if (smallestFirst.size() < limit) {
                        smallestFirst.add(candidate);
                        continue;
                    }
                    FileEntry smallest = smallestFirst.peek();
                    if (smallest != null && LARGEST_FIRST.compare(candidate, smallest) < 0) {
                        smallestFirst.poll();
                        smallestFirst.add(candidate);
                    }
                }
            }
            listed = new ArrayList<>(smallestFirst);
        } catch (IOException | RuntimeException unreadableDirectory) {
            // The directory moved, lost permissions, or is on a device that stopped
            // answering since the scan. An empty listing is the honest answer; the browser
            // falls back to whatever the scan retained.
            return new Listing(List.of(), 0);
        }
        listed.sort(LARGEST_FIRST);
        return new Listing(List.copyOf(listed), total);
    }

    private static long diskSize(@NotNull FileStat stat) {
        return stat.blocks >= 0 ? stat.blocks * 512 : stat.size;
    }

    private static @NotNull String name(@NotNull Path path) {
        Path name = path.getFileName();
        return name != null ? name.toString() : path.toString();
    }

    /**
     * @param files regular files, largest first, at most {@code limit} of them
     * @param total regular files that qualified, including any the limit dropped
     */
    public record Listing(@NotNull List<FileEntry> files, long total) {
        public boolean truncated() {
            return files.size() < total;
        }
    }
}
