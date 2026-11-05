package io.github.lihuu.dukit.core.scan;

import io.github.lihuu.dukit.core.model.DirEntry;
import io.github.lihuu.dukit.core.model.ScanError;
import io.github.lihuu.dukit.core.model.ScanResult;
import io.github.lihuu.dukit.core.model.TopResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Walks a directory tree and aggregates apparent sizes. Two shapes are supported from the
 * same traversal:
 *
 * <ul>
 *   <li>{@link #scan} builds a tree of <em>directories only</em>; regular files are folded
 *       into their parent's counters.</li>
 *   <li>{@link #top} builds no tree and keeps only the N largest directories and files in
 *       bounded heaps.</li>
 * </ul>
 *
 * <p>Traversal is parallel: a directory forks tasks only once it holds enough subdirectories
 * to pay for one. Below that threshold the work stays inline on the calling thread, so a
 * small tree costs what a serial walk costs — the fork threshold is what keeps parallelism
 * from becoming a tax on cheap scans.
 *
 * <p>Symlinks and other reparse points (Windows junctions) are never followed. Unreadable
 * entries are collected as errors and the walk continues; only the root failing to be a
 * readable directory aborts. Hardlinked regular files count their sizes once per scan (first
 * occurrence wins) while still counting toward the file totals. Directories contribute only
 * their contents, never their own inode size.
 */
public final class DirectoryScanner {
    /** Forks only once a directory holds this many subdirectories. */
    private static final int FORK_THRESHOLD = 4;
    private static final long PROGRESS_INTERVAL_MASK = 0x3FF;

    private final List<String> excludePatterns;
    private final int parallelism;

    public DirectoryScanner(@NotNull List<String> excludePatterns) {
        this(excludePatterns, 0);
    }

    /**
     * @param parallelism worker count; {@code 0} sizes the pool from the machine. {@code 1}
     *                    forces the serial path, which is what tests use when they assert
     *                    error ordering.
     */
    DirectoryScanner(@NotNull List<String> excludePatterns, int parallelism) {
        this.excludePatterns = List.copyOf(excludePatterns);
        this.parallelism = parallelism;
    }

    public @NotNull ScanResult scan(@NotNull Path root, @NotNull ProgressListener listener) throws IOException {
        return scan(root, listener, 0);
    }

    @NotNull ScanResult scan(@NotNull Path root, @NotNull ProgressListener listener, int topLimit) throws IOException {
        Run run = execute(root, listener, true, topLimit);
        if (run.node == null) {
            throw new IOException("cannot scan " + run.root + ": " + run.reason());
        }
        return new ScanResult(run.root, run.node.toEntry(run.root), run.errors());
    }

    public @NotNull TopResult top(@NotNull Path root, int limit, @NotNull ProgressListener listener) throws IOException {
        Run run = execute(root, listener, false, limit);
        if (run.node == null) {
            throw new IOException("cannot scan " + run.root + ": " + run.reason());
        }
        return new TopResult(run.largestDirectories.result(), run.largestFiles.result(),
                run.node.size, run.node.ownFileCount + run.node.subtreeFileCount, run.errors());
    }

    private @NotNull Run execute(@NotNull Path root, @NotNull ProgressListener listener,
            boolean buildTree, int topLimit) throws IOException {
        Path absolute = root.toAbsolutePath().normalize();
        List<PathMatcher> nameMatchers = new ArrayList<>();
        List<PathMatcher> relativeMatchers = new ArrayList<>();
        if (Files.isDirectory(absolute)) {
            FileSystem fileSystem = absolute.getFileSystem();
            for (String pattern : excludePatterns) {
                PathMatcher matcher = fileSystem.getPathMatcher("glob:" + pattern);
                if (pattern.indexOf('/') >= 0) {
                    relativeMatchers.add(matcher);
                } else {
                    nameMatchers.add(matcher);
                }
            }
        }

        Run run = new Run(absolute, nameMatchers, relativeMatchers, listener, buildTree, topLimit);
        if (!Files.isDirectory(absolute)) {
            return run;
        }
        int workers = parallelism > 0 ? parallelism : Math.max(1, Runtime.getRuntime().availableProcessors() * 2);
        if (workers == 1) {
            run.node = new DirectoryTask(absolute, run).compute();
        } else {
            try (ForkJoinPool pool = new ForkJoinPool(workers)) {
                run.node = pool.invoke(new DirectoryTask(absolute, run));
            }
        }
        listener.onUpdate(run.fileCount.get(), run.bytesSeen.get(), absolute);
        return run;
    }

    private static final class Run {
        private final Path root;
        private final List<PathMatcher> nameMatchers;
        private final List<PathMatcher> relativeMatchers;
        private final ProgressListener listener;
        private final boolean buildTree;
        private final StatReader statReader = StatReader.create();
        private final HardlinkSet seenHardlinks = new HardlinkSet();
        private final Set<Object> seenFileKeys = ConcurrentHashMap.newKeySet();
        private final ConcurrentLinkedQueue<ScanError> errors = new ConcurrentLinkedQueue<>();
        private final AtomicLong fileCount = new AtomicLong();
        private final AtomicLong bytesSeen = new AtomicLong();
        private final TopCollector largestFiles;
        private final TopCollector largestDirectories;
        private @Nullable Node node;

        Run(@NotNull Path root, @NotNull List<PathMatcher> nameMatchers,
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

        @NotNull List<ScanError> errors() {
            List<ScanError> sorted = new ArrayList<>(errors);
            sorted.sort(Comparator.comparing(error -> error.path().toString()));
            return List.copyOf(sorted);
        }

        @NotNull String reason() {
            List<ScanError> collected = errors();
            return collected.isEmpty() ? "not a readable directory" : collected.get(0).message();
        }

        boolean excluded(@NotNull Path path) {
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

        void recordError(@NotNull Path path, @NotNull Throwable failure) {
            errors.add(new ScanError(path, failure.toString()));
        }

        void progress(@NotNull Path current) {
            long seen = fileCount.incrementAndGet();
            if ((seen & PROGRESS_INTERVAL_MASK) == 0) {
                listener.onUpdate(seen, bytesSeen.get(), current);
            }
        }
    }

    /**
     * One directory's worth of work. Returns {@code null} only when the root itself cannot be
     * opened, so a failed scan is distinguishable from an empty directory.
     */
    private static final class DirectoryTask extends RecursiveTask<Node> {
        private final Path dir;
        private final Run run;

        DirectoryTask(@NotNull Path dir, @NotNull Run run) {
            this.dir = dir;
            this.run = run;
        }

        @Override
        protected @Nullable Node compute() {
            Node node = new Node();
            List<Path> subdirectories = new ArrayList<>();
            FileStat stat = new FileStat();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir);
                 StatReader.Cursor cursor = run.statReader.open()) {
                for (Path child : stream) {
                    visit(node, subdirectories, child, cursor, stat);
                }
            } catch (IOException | DirectoryIteratorException failure) {
                run.recordError(dir, failure instanceof DirectoryIteratorException iteratorFailure
                        && iteratorFailure.getCause() != null ? iteratorFailure.getCause() : failure);
                node.errorCount++;
                return dir.equals(run.root) ? null : node;
            }
            collect(node, subdirectories);
            return node;
        }

        private void visit(@NotNull Node node, @NotNull List<Path> subdirectories, @NotNull Path child,
                @NotNull StatReader.Cursor cursor, @NotNull FileStat stat) {
            if (run.excluded(child)) {
                return;
            }
            try {
                cursor.read(child, stat);
            } catch (IOException failure) {
                run.recordError(child, failure);
                node.errorCount++;
                return;
            }
            switch (stat.kind) {
                case SYMBOLIC_LINK -> {
                    return;
                }
                case DIRECTORY -> {
                    subdirectories.add(child);
                    return;
                }
                case OTHER -> {
                    return;
                }
                case REGULAR_FILE -> {
                }
            }
            boolean hardlinkTwin;
            if (stat.linkCount >= 0) {
                // A link count of one proves no other path can share the inode, so only
                // genuinely shared content occupies the set.
                hardlinkTwin = stat.linkCount > 1 && !run.seenHardlinks.add(stat.device, stat.inode);
            } else {
                hardlinkTwin = stat.fileKey != null && !run.seenFileKeys.add(stat.fileKey);
            }
            long size = hardlinkTwin ? 0 : stat.size;
            node.ownFileSize += size;
            node.ownFileCount++;
            node.size += size;
            run.bytesSeen.addAndGet(size);
            if (run.largestFiles != null && size > 0) {
                run.largestFiles.offer(child, size);
            }
            run.progress(child);
        }

        private void collect(@NotNull Node node, @NotNull List<Path> subdirectories) {
            if (subdirectories.isEmpty()) {
                return;
            }
            if (subdirectories.size() >= FORK_THRESHOLD) {
                List<DirectoryTask> tasks = new ArrayList<>(subdirectories.size());
                for (Path child : subdirectories) {
                    DirectoryTask task = new DirectoryTask(child, run);
                    task.fork();
                    tasks.add(task);
                }
                for (int i = 0; i < tasks.size(); i++) {
                    Node child = tasks.get(i).join();
                    if (child != null) {
                        merge(node, child, subdirectories.get(i));
                    }
                }
                return;
            }
            for (Path child : subdirectories) {
                Node result = new DirectoryTask(child, run).compute();
                if (result != null) {
                    merge(node, result, child);
                }
            }
        }

        private void merge(@NotNull Node parent, @NotNull Node child, @NotNull Path childPath) {
            parent.size += child.size;
            parent.subtreeFileCount += child.ownFileCount + child.subtreeFileCount;
            parent.subtreeDirCount += 1 + child.subtreeDirCount;
            parent.errorCount += child.errorCount;
            if (run.buildTree) {
                parent.children.add(child.toEntry(childPath));
            }
            if (run.largestDirectories != null && child.size > 0) {
                run.largestDirectories.offer(childPath, child.size);
            }
        }
    }

    private static final class Node {
        private final List<DirEntry> children = new ArrayList<>();
        private long size;
        private long ownFileSize;
        private long ownFileCount;
        private long subtreeFileCount;
        private long subtreeDirCount;
        private long errorCount;

        private @NotNull DirEntry toEntry(@NotNull Path dir) {
            children.sort(Comparator.comparingLong((DirEntry entry) -> entry.size())
                    .reversed()
                    .thenComparing(DirEntry::name));
            return new DirEntry(name(dir), size, ownFileCount + subtreeFileCount,
                    subtreeDirCount, errorCount, ownFileSize, ownFileCount, children);
        }

        private static String name(@NotNull Path path) {
            Path name = path.getFileName();
            return name != null ? name.toString() : path.toString();
        }
    }
}
