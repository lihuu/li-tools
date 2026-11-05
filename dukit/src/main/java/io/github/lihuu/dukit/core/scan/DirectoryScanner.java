package io.github.lihuu.dukit.core.scan;

import io.github.lihuu.dukit.core.model.DirEntry;
import io.github.lihuu.dukit.core.model.ScanError;
import io.github.lihuu.dukit.core.model.ScanResult;
import io.github.lihuu.dukit.core.model.TopEntry;
import io.github.lihuu.dukit.core.model.TopResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.FileVisitResult;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Walks a directory tree once and aggregates apparent sizes. Two shapes are supported from
 * the same pass:
 *
 * <ul>
 *   <li>{@link #scan} builds a tree of <em>directories only</em>; regular files are folded
 *       into their parent's counters. The result is proportional to the directory count,
 *       which is roughly an order of magnitude smaller than the file count.</li>
 *   <li>{@link #top} builds no tree at all and keeps only the N largest directories and
 *       files in bounded heaps, so a full scan of a huge volume costs {@code O(N)} memory
 *       beyond the live traversal stack.</li>
 * </ul>
 *
 * <p>Symlinks are never followed. Unreadable entries are collected as errors and the scan
 * continues; only the root failing to be a readable directory aborts. Hardlinked regular
 * files count their sizes once per scan (first occurrence wins) while still counting toward
 * the file totals. Directories contribute only their contents, never their own inode size,
 * which keeps numbers comparable across filesystems.
 */
public final class DirectoryScanner {
    private static final long PROGRESS_INTERVAL_MASK = 0x3FF;

    private final List<String> excludePatterns;

    public DirectoryScanner(@NotNull List<String> excludePatterns) {
        this.excludePatterns = List.copyOf(excludePatterns);
    }

    public @NotNull ScanResult scan(@NotNull Path root, @NotNull ProgressListener listener) throws IOException {
        Traversal traversal = traverse(root, listener, true, 0);
        if (traversal.rootEntry == null) {
            throw new IOException("cannot scan " + traversal.root + ": " + firstErrorReason(traversal));
        }
        return new ScanResult(traversal.rootEntry, traversal.errors);
    }

    public @NotNull TopResult top(@NotNull Path root, int limit, @NotNull ProgressListener listener) throws IOException {
        Traversal traversal = traverse(root, listener, false, limit);
        if (!traversal.rootVisited) {
            throw new IOException("cannot scan " + traversal.root + ": " + firstErrorReason(traversal));
        }
        return new TopResult(
                traversal.largestDirectories == null ? List.of() : traversal.largestDirectories.result(),
                traversal.largestFiles == null ? List.of() : traversal.largestFiles.result(),
                traversal.totalSize,
                traversal.fileCount,
                traversal.errors);
    }

    private static String firstErrorReason(@NotNull Traversal traversal) {
        return traversal.errors.isEmpty() ? "not a readable directory" : traversal.errors.get(0).message();
    }

    private Traversal traverse(@NotNull Path root, @NotNull ProgressListener listener,
            boolean buildTree, int topLimit) throws IOException {
        Path absolute = root.toAbsolutePath().normalize();
        FileSystem fileSystem = absolute.getFileSystem();
        List<PathMatcher> nameMatchers = new ArrayList<>();
        List<PathMatcher> relativeMatchers = new ArrayList<>();
        for (String pattern : excludePatterns) {
            PathMatcher matcher = fileSystem.getPathMatcher("glob:" + pattern);
            if (pattern.indexOf('/') >= 0) {
                relativeMatchers.add(matcher);
            } else {
                nameMatchers.add(matcher);
            }
        }
        Traversal traversal = new Traversal(absolute, nameMatchers, relativeMatchers, listener, buildTree, topLimit);
        Files.walkFileTree(absolute, Set.of(), Integer.MAX_VALUE, traversal);
        listener.onUpdate(traversal.fileCount, traversal.totalSize, absolute);
        return traversal;
    }

    private static final class Traversal extends SimpleFileVisitor<Path> {
        private final Path root;
        private final List<PathMatcher> nameMatchers;
        private final List<PathMatcher> relativeMatchers;
        private final ProgressListener listener;
        private final boolean buildTree;
        private final Deque<Accumulator> stack = new ArrayDeque<>();
        private final Set<Object> seenFileKeys = new HashSet<>();
        private final List<ScanError> errors = new ArrayList<>();
        private final @Nullable TopCollector largestFiles;
        private final @Nullable TopCollector largestDirectories;
        private long fileCount;
        private long totalSize;
        private boolean rootVisited;
        private @Nullable DirEntry rootEntry;

        Traversal(@NotNull Path root, @NotNull List<PathMatcher> nameMatchers,
                @NotNull List<PathMatcher> relativeMatchers, @NotNull ProgressListener listener,
                boolean buildTree, int topLimit) {
            this.root = root;
            this.nameMatchers = nameMatchers;
            this.relativeMatchers = relativeMatchers;
            this.listener = listener;
            this.buildTree = buildTree;
            this.largestFiles = topLimit > 0 ? new TopCollector(topLimit) : null;
            this.largestDirectories = topLimit > 0 ? new TopCollector(topLimit) : null;
        }

        @Override
        public FileVisitResult preVisitDirectory(@NotNull Path dir, @NotNull BasicFileAttributes attrs) {
            if (excluded(dir)) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            if (dir.equals(root)) {
                rootVisited = true;
            }
            stack.push(new Accumulator(dir));
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) {
            if (excluded(file) || attrs.isSymbolicLink() || !attrs.isRegularFile()) {
                return FileVisitResult.CONTINUE;
            }
            boolean hardlinkTwin = attrs.fileKey() != null && !seenFileKeys.add(attrs.fileKey());
            long size = hardlinkTwin ? 0 : attrs.size();
            fileCount++;
            totalSize += size;
            Accumulator current = stack.peek();
            if (current != null) {
                current.addFile(size);
                if (largestFiles != null && size > 0) {
                    largestFiles.offer(file, size);
                }
                progress();
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(@NotNull Path path, @NotNull IOException failure) {
            Accumulator current = stack.peek();
            if (current != null && path.equals(current.dir)) {
                stack.pop();
                recordError(path, failure);
            } else if (!Files.isSymbolicLink(path)) {
                recordError(path, failure);
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(@NotNull Path dir, @Nullable IOException failure) {
            if (failure != null) {
                recordError(dir, failure);
            }
            Accumulator finished = stack.pop();
            if (largestDirectories != null && finished.size > 0 && !dir.equals(root)) {
                // The scan root is the query's starting point, not a discovery; reporting it
                // would just repeat the whole total at rank 1.
                largestDirectories.offer(dir, finished.size);
            }
            if (!buildTree) {
                Accumulator parent = stack.peek();
                if (parent != null) {
                    parent.merge(finished);
                }
                return FileVisitResult.CONTINUE;
            }
            DirEntry entry = finished.toEntry();
            if (dir.equals(root)) {
                rootEntry = entry;
            } else {
                Accumulator parent = stack.peek();
                if (parent != null) {
                    parent.children.add(entry);
                    parent.merge(finished);
                    progress();
                }
            }
            return FileVisitResult.CONTINUE;
        }

        private void recordError(@NotNull Path path, @NotNull Exception failure) {
            errors.add(new ScanError(path, failure.toString()));
            Accumulator current = stack.peek();
            if (current != null) {
                current.errorCount++;
            }
        }

        private void progress() {
            if ((fileCount & PROGRESS_INTERVAL_MASK) == 0) {
                Accumulator current = stack.peek();
                if (current != null) {
                    listener.onUpdate(fileCount, totalSize, current.dir);
                }
            }
        }

        private boolean excluded(@NotNull Path path) {
            if (!nameMatchers.isEmpty()) {
                Path name = path.getFileName();
                if (name != null) {
                    for (PathMatcher matcher : nameMatchers) {
                        if (matcher.matches(name)) {
                            return true;
                        }
                    }
                }
            }
            for (PathMatcher matcher : relativeMatchers) {
                if (matcher.matches(root.relativize(path))) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class Accumulator {
        private final Path dir;
        private final List<DirEntry> children = new ArrayList<>();
        private long ownFileSize;
        private long ownFileCount;
        private long subtreeFileCount;
        private long subtreeDirCount;
        private long size;
        private long errorCount;

        Accumulator(@NotNull Path dir) {
            this.dir = dir;
        }

        private void addFile(long bytes) {
            ownFileSize += bytes;
            ownFileCount++;
            size += bytes;
        }

        /**
         * Folds a finished child directory's aggregates into this directory.
         */
        private void merge(@NotNull Accumulator child) {
            size += child.size;
            subtreeFileCount += child.ownFileCount + child.subtreeFileCount;
            subtreeDirCount += 1 + child.subtreeDirCount;
            errorCount += child.errorCount;
        }

        /**
         * Folds a finished child {@link DirEntry} into this directory when a tree is built.
         */
        private void merge(@NotNull DirEntry child) {
            size += child.size();
            subtreeFileCount += child.fileCount();
            subtreeDirCount += 1 + child.dirCount();
            errorCount += child.errorCount();
        }

        private DirEntry toEntry() {
            children.sort(Comparator.comparingLong((DirEntry entry) -> entry.size())
                    .reversed()
                    .thenComparing(DirEntry::name));
            return new DirEntry(name(dir), dir, size, ownFileCount + subtreeFileCount,
                    subtreeDirCount, errorCount, ownFileSize, ownFileCount, children);
        }

        private static String name(@NotNull Path path) {
            Path name = path.getFileName();
            return name != null ? name.toString() : path.toString();
        }
    }
}
