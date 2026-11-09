package io.github.lihuu.dukit.core.scan;

import io.github.lihuu.dukit.core.model.DirEntry;
import io.github.lihuu.dukit.core.model.FileEntry;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.RecursiveTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Walks a directory tree and aggregates apparent sizes. Two shapes are supported from the
 * same traversal:
 *
 * <ul>
 *   <li>{@link #scan} builds a tree of <em>directories only</em>; regular files are folded
 *       into their parent's counters. A caller that needs to show individual files (the
 *       interactive browser) passes a positive preview size, which retains that many largest
 *       direct files per directory without adding nodes to the tree.</li>
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
 *
 * <p>A filesystem that stops answering — a cloud-drive mount whose file provider daemon is
 * unresponsive, a stale network share — blocks directory listings in uninterruptible kernel
 * syscalls, which no Java timeout can cancel. The scan therefore bounds its waits: a forked
 * subdirectory whose work exceeds the directory budget is recorded as an error and skipped,
 * and a scan that makes no progress at all for one budget is aborted instead of hanging
 * forever. The abandoned thread drains whenever the kernel returns; it is never waited on.
 */
public final class DirectoryScanner {
    /** Forks only once a directory holds this many subdirectories. */
    private static final int FORK_THRESHOLD = 4;
    private static final long PROGRESS_INTERVAL_MASK = 0x3FF;
    /** Default budget for one directory's bounded wait (and for the no-progress watchdog). */
    private static final long DEFAULT_DIRECTORY_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);
    /** Watchdog poll cadence for the whole-scan root future. */
    private static final long ROOT_POLL_MILLIS = 200;

    private final List<String> excludePatterns;
    private final int parallelism;
    private final long directoryTimeoutNanos;

    public DirectoryScanner(@NotNull List<String> excludePatterns) {
        this(excludePatterns, 0);
    }

    /**
     * @param parallelism worker count; {@code 0} sizes the pool from the machine. {@code 1}
     *                    forces the serial path, which is what tests use when they assert
     *                    error ordering.
     */
    DirectoryScanner(@NotNull List<String> excludePatterns, int parallelism) {
        this(excludePatterns, parallelism, DEFAULT_DIRECTORY_TIMEOUT_NANOS);
    }

    /**
     * @param directoryTimeoutNanos budget for one directory's bounded wait and for the
     *                              no-progress watchdog; must be positive
     */
    DirectoryScanner(@NotNull List<String> excludePatterns, int parallelism, long directoryTimeoutNanos) {
        if (directoryTimeoutNanos <= 0) {
            throw new IllegalArgumentException("directoryTimeoutNanos must be positive: " + directoryTimeoutNanos);
        }
        this.excludePatterns = List.copyOf(excludePatterns);
        this.parallelism = parallelism;
        this.directoryTimeoutNanos = directoryTimeoutNanos;
    }

    public @NotNull ScanResult scan(@NotNull Path root, @NotNull ProgressListener listener) throws IOException {
        return scan(root, listener, 0);
    }

    /**
     * Scans and, when {@code topFiles} is positive, retains that many largest regular files
     * directly inside every directory for the interactive browser. The files never enter the
     * directory tree's children or the JSON report.
     *
     * @param topFiles per-directory file preview size; {@code 0} keeps no files
     */
    public @NotNull ScanResult scan(@NotNull Path root, @NotNull ProgressListener listener, int topFiles) throws IOException {
        if (topFiles < 0) {
            throw new IllegalArgumentException("topFiles must not be negative: " + topFiles);
        }
        Run run = execute(root, listener, true, 0, topFiles);
        if (run.node == null) {
            throw new IOException("cannot scan " + run.root + ": " + run.reason());
        }
        return new ScanResult(run.root, run.node.toEntry(run.root), run.errors());
    }

    public @NotNull TopResult top(@NotNull Path root, int limit, @NotNull ProgressListener listener) throws IOException {
        Run run = execute(root, listener, false, limit, 0);
        if (run.node == null) {
            throw new IOException("cannot scan " + run.root + ": " + run.reason());
        }
        return new TopResult(run.largestDirectories.result(), run.largestFiles.result(),
                run.node.diskSize, run.node.ownFileCount + run.node.subtreeFileCount, run.errors());
    }

    private @NotNull Run execute(@NotNull Path root, @NotNull ProgressListener listener,
            boolean buildTree, int topLimit, int topFiles) throws IOException {
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

        Run run = new Run(absolute, nameMatchers, relativeMatchers, listener, buildTree, topLimit, topFiles,
                directoryTimeoutNanos);
        if (!Files.isDirectory(absolute)) {
            return run;
        }
        int workers = parallelism > 0 ? parallelism : Math.max(1, Runtime.getRuntime().availableProcessors() * 2);
        if (workers == 1) {
            run.node = new DirectoryTask(absolute, run).compute();
        } else {
            ForkJoinPool pool = new ForkJoinPool(workers);
            try {
                run.node = awaitRoot(pool.submit(new DirectoryTask(absolute, run)), run);
            } finally {
                // Never waits: threads wedged in a kernel syscall must not hold the scan.
                pool.shutdownNow();
            }
        }
        listener.onUpdate(run.fileCount.get(), run.bytesSeen.get(), absolute);
        return run;
    }

    /**
     * Waits for the root task and aborts the scan when nothing has been read for one directory
     * budget — the case when the root's own listing, or every worker, is stuck in a filesystem
     * that never answers. Returns {@code null} when the scan is abandoned.
     */
    private @Nullable Node awaitRoot(@NotNull Future<Node> rootTask, @NotNull Run run) {
        try {
            while (true) {
                try {
                    return rootTask.get(ROOT_POLL_MILLIS, TimeUnit.MILLISECONDS);
                } catch (TimeoutException tick) {
                    long idle = System.nanoTime() - run.lastActivityNanos();
                    if (idle >= run.directoryTimeoutNanos()) {
                        run.recordError(run.root, new IOException("timed out after "
                                + describeSeconds(run.directoryTimeoutNanos()) + " without progress, scan aborted"));
                        return null;
                    }
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            run.recordError(run.root, interrupted);
            return null;
        } catch (ExecutionException failure) {
            run.recordError(run.root, failure.getCause() != null ? failure.getCause() : failure);
            return null;
        }
    }

    private static @NotNull String describeSeconds(long nanos) {
        return (nanos + 999_999_999) / 1_000_000_000 + "s";
    }

    private static final class Run {
        private final Path root;
        private final List<PathMatcher> nameMatchers;
        private final List<PathMatcher> relativeMatchers;
        private final ProgressListener listener;
        private final boolean buildTree;
        private final int topFiles;
        private final long directoryTimeoutNanos;
        private final StatReader statReader = StatReader.create();
        private final HardlinkSet seenHardlinks = new HardlinkSet();
        private final Set<Object> seenFileKeys = ConcurrentHashMap.newKeySet();
        private final ConcurrentLinkedQueue<ScanError> errors = new ConcurrentLinkedQueue<>();
        private final AtomicLong fileCount = new AtomicLong();
        private final AtomicLong bytesSeen = new AtomicLong();
        private final TopCollector largestFiles;
        private final TopCollector largestDirectories;
        private volatile long activityNanos = System.nanoTime();
        private @Nullable Node node;

        Run(@NotNull Path root, @NotNull List<PathMatcher> nameMatchers,
                @NotNull List<PathMatcher> relativeMatchers, @NotNull ProgressListener listener,
                boolean buildTree, int topLimit, int topFiles, long directoryTimeoutNanos) {
            this.root = root;
            this.nameMatchers = nameMatchers;
            this.relativeMatchers = relativeMatchers;
            this.listener = listener;
            this.buildTree = buildTree;
            this.topFiles = topFiles;
            this.directoryTimeoutNanos = directoryTimeoutNanos;
            this.largestFiles = topLimit > 0 ? new TopCollector(topLimit) : null;
            this.largestDirectories = topLimit > 0 ? new TopCollector(topLimit) : null;
        }

        long directoryTimeoutNanos() {
            return directoryTimeoutNanos;
        }

        long lastActivityNanos() {
            return activityNanos;
        }

        /** Marks the scan as making progress; the root watchdog resets on it. */
        void touch() {
            activityNanos = System.nanoTime();
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
            touch();
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
            run.touch();
            Node node = new Node(run.topFiles);
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
            long disk = hardlinkTwin ? 0 : (stat.blocks >= 0 ? stat.blocks * 512 : stat.size);
            node.ownFileSize += size;
            node.ownFileCount++;
            node.size += size;
            node.diskSize += disk;
            if (size > 0) {
                node.offerFile(child, size, disk);
            }
            run.bytesSeen.addAndGet(disk);
            if (run.largestFiles != null && disk > 0) {
                run.largestFiles.offer(child, size, disk);
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
                    Node child = tasks.get(i).joinBounded(node, subdirectories.get(i));
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

        /**
         * Joins this task, but stops waiting after one directory budget: a listing stuck in a
         * filesystem that never answers (cloud-drive mount, stale network share) would
         * otherwise hold the whole scan forever. The wedged task is abandoned on its worker
         * thread; the scan records the directory as an error and keeps its siblings.
         */
        private @Nullable Node joinBounded(@NotNull Node parent, @NotNull Path path) {
            if (isDone()) {
                return join();
            }
            try {
                return get(run.directoryTimeoutNanos(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException timedOut) {
                run.recordError(path, new IOException("timed out after "
                        + describeSeconds(run.directoryTimeoutNanos()) + ", skipped"));
                parent.errorCount++;
                return null;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                run.recordError(path, interrupted);
                parent.errorCount++;
                return null;
            } catch (ExecutionException failure) {
                run.recordError(path, failure.getCause() != null ? failure.getCause() : failure);
                parent.errorCount++;
                return null;
            }
        }

        private void merge(@NotNull Node parent, @NotNull Node child, @NotNull Path childPath) {
            parent.size += child.size;
            parent.diskSize += child.diskSize;
            parent.subtreeFileCount += child.ownFileCount + child.subtreeFileCount;
            parent.subtreeDirCount += 1 + child.subtreeDirCount;
            parent.errorCount += child.errorCount;
            if (run.buildTree) {
                parent.children.add(child.toEntry(childPath));
            }
            if (run.largestDirectories != null && child.diskSize > 0) {
                run.largestDirectories.offer(childPath, child.size, child.diskSize);
            }
        }
    }

    private static final class Node {
        private static final Comparator<FileEntry> FILE_ORDER =
                Comparator.comparingLong(FileEntry::diskSize).reversed().thenComparing(FileEntry::name);

        private final List<DirEntry> children = new ArrayList<>();
        private final int fileLimit;
        private @Nullable List<FileEntry> topFiles;
        private long size;
        private long diskSize;
        private long ownFileSize;
        private long ownFileCount;
        private long subtreeFileCount;
        private long subtreeDirCount;
        private long errorCount;

        private Node(int fileLimit) {
            this.fileLimit = fileLimit;
        }

        /**
         * Inserts one regular file into the bounded, size-descending preview. The tiny list is
         * kept sorted in place: the limit is single digits, so an insertion costs less than
         * allocating a collector per directory.
         */
        private void offerFile(@NotNull Path path, long size, long disk) {
            if (fileLimit == 0 || size <= 0) {
                return;
            }
            if (topFiles == null) {
                topFiles = new ArrayList<>(Math.min(fileLimit, 8));
            }
            if (topFiles.size() == fileLimit && disk < topFiles.get(topFiles.size() - 1).diskSize()) {
                return;
            }
            FileEntry candidate = new FileEntry(name(path), size, disk);
            int index = 0;
            while (index < topFiles.size() && FILE_ORDER.compare(topFiles.get(index), candidate) <= 0) {
                index++;
            }
            if (index == topFiles.size() && topFiles.size() == fileLimit) {
                return;
            }
            topFiles.add(index, candidate);
            if (topFiles.size() > fileLimit) {
                topFiles.remove(topFiles.size() - 1);
            }
        }

        private @NotNull DirEntry toEntry(@NotNull Path dir) {
            children.sort(Comparator.comparingLong((DirEntry entry) -> entry.diskSize())
                    .reversed()
                    .thenComparing(DirEntry::name));
            return new DirEntry(name(dir), diskSize, size, ownFileCount + subtreeFileCount,
                    subtreeDirCount, errorCount, ownFileSize, ownFileCount,
                    topFiles == null ? List.of() : topFiles, children);
        }

        private static String name(@NotNull Path path) {
            Path name = path.getFileName();
            return name != null ? name.toString() : path.toString();
        }
    }
}
