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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
 * <p>Directories are listed by a pool of workers, but <em>no worker ever waits for another
 * directory</em>. Every directory is a job that reports its own completion, and a parent's
 * node is finished by whichever job accounts for the last outstanding child. That keeps a
 * slow or wedged directory from making a worker spend its time waiting, and it keeps the
 * thread that notices a stall from being the one blocked by it.
 *
 * <p>Symlinks and other reparse points (Windows junctions) are never followed. Unreadable
 * entries are collected as errors and the walk continues; only the root failing to be a
 * readable directory aborts. Hardlinked regular files count their sizes once per scan (first
 * occurrence wins) while still counting toward the file totals. Directories contribute only
 * their contents, never their own inode size.
 *
 * <p>A filesystem that stops answering — a cloud-drive mount whose file provider daemon is
 * unresponsive, a stale network share — blocks directory listings in uninterruptible kernel
 * syscalls, which no Java timeout can cancel. The scan therefore watches for <em>silence</em>,
 * not for elapsed time: a directory that has started listing and then yields no entry at all
 * for one budget is recorded as an error and abandoned, while a directory that keeps producing
 * entries is walked to the end no matter how long it takes. Directories whose turn has not
 * come yet are never judged, because a queue is not a stall. The watcher is the calling
 * thread, which touches no filesystem, so it can always decide and the scan always returns;
 * the abandoned thread drains whenever the kernel releases it and is never waited on.
 */
public final class DirectoryScanner {
    private static final long PROGRESS_INTERVAL_MASK = 0x3FF;
    /** Default silence tolerated from a started listing before it counts as stalled. */
    private static final long DEFAULT_STALL_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(2);
    /** How often the watcher re-reads the stamps and checks the queue. */
    private static final long POLL_MILLIS = 100;
    /** Ceiling for replacing workers written off to a stalled syscall. */
    private static final int MAX_EXTRA_WORKERS = 64;

    private final List<String> excludePatterns;
    private final int parallelism;
    private final long stallBudgetNanos;

    public DirectoryScanner(@NotNull List<String> excludePatterns) {
        this(excludePatterns, 0);
    }

    /**
     * @param parallelism worker count; {@code 0} sizes the pool from the machine. {@code 1}
     *                    forces the serial path, which is what tests use when they assert
     *                    error ordering.
     */
    DirectoryScanner(@NotNull List<String> excludePatterns, int parallelism) {
        this(excludePatterns, parallelism, DEFAULT_STALL_BUDGET_NANOS);
    }

    /**
     * @param stallBudgetNanos silence tolerated from a started listing before it is abandoned;
     *                         must be positive
     */
    DirectoryScanner(@NotNull List<String> excludePatterns, int parallelism, long stallBudgetNanos) {
        if (stallBudgetNanos <= 0) {
            throw new IllegalArgumentException("stallBudgetNanos must be positive: " + stallBudgetNanos);
        }
        this.excludePatterns = List.copyOf(excludePatterns);
        this.parallelism = parallelism;
        this.stallBudgetNanos = stallBudgetNanos;
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
            boolean buildTree, int topLimit, int topFiles) {
        Path absolute = root.toAbsolutePath().normalize();
        List<PathMatcher> nameMatchers = new ArrayList<>();
        List<PathMatcher> relativeMatchers = new ArrayList<>();
        FileSystem fileSystem = absolute.getFileSystem();
        for (String pattern : excludePatterns) {
            PathMatcher matcher = fileSystem.getPathMatcher("glob:" + pattern);
            if (pattern.indexOf('/') >= 0) {
                relativeMatchers.add(matcher);
            } else {
                nameMatchers.add(matcher);
            }
        }

        Run run = new Run(absolute, nameMatchers, relativeMatchers, listener, buildTree, topLimit, topFiles,
                stallBudgetNanos);
        int workers = parallelism > 0 ? parallelism : Math.max(1, Runtime.getRuntime().availableProcessors() * 2);
        run.start(workers);
        listener.onUpdate(run.fileCount.get(), run.bytesSeen.get(), absolute);
        return run;
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
        private final long stallBudgetNanos;
        private final StatReader statReader = StatReader.create();
        private final HardlinkSet seenHardlinks = new HardlinkSet();
        private final Set<Object> seenFileKeys = ConcurrentHashMap.newKeySet();
        private final ConcurrentLinkedQueue<ScanError> errors = new ConcurrentLinkedQueue<>();
        private final AtomicLong fileCount = new AtomicLong();
        private final AtomicLong bytesSeen = new AtomicLong();
        private final TopCollector largestFiles;
        private final TopCollector largestDirectories;
        /** Directory jobs whose listing is in progress right now; a queue is not a stall. */
        private final Set<ScanJob> active = ConcurrentHashMap.newKeySet();
        private final CountDownLatch done = new CountDownLatch(1);
        private @Nullable ThreadPoolExecutor pool;
        private @Nullable ScanJob rootJob;
        private int workers;
        private volatile boolean rootAbandoned;
        private volatile boolean rootUnreadable;
        private @Nullable Node node;

        Run(@NotNull Path root, @NotNull List<PathMatcher> nameMatchers,
                @NotNull List<PathMatcher> relativeMatchers, @NotNull ProgressListener listener,
                boolean buildTree, int topLimit, int topFiles, long stallBudgetNanos) {
            this.root = root;
            this.nameMatchers = nameMatchers;
            this.relativeMatchers = relativeMatchers;
            this.listener = listener;
            this.buildTree = buildTree;
            this.topFiles = topFiles;
            this.stallBudgetNanos = stallBudgetNanos;
            this.largestFiles = topLimit > 0 ? new TopCollector(topLimit) : null;
            this.largestDirectories = topLimit > 0 ? new TopCollector(topLimit) : null;
        }

        private void start(int workers) {
            this.workers = workers;
            ThreadPoolExecutor executor = new ThreadPoolExecutor(workers, workers,
                    0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), new WorkerFactory());
            this.pool = executor;
            ScanJob job = new ScanJob(root, null, this);
            this.rootJob = job;
            executor.execute(job);
            awaitRoot();
            // Never waits: a thread wedged in a kernel syscall cannot be reclaimed here.
            executor.shutdownNow();
            this.node = rootAbandoned || rootUnreadable ? null : job.node();
        }

        /**
         * The only place that decides a directory has stalled. It reads stamps and queue depth
         * and touches no filesystem, so it cannot be blocked by the very mount it is watching.
         */
        private void awaitRoot() {
            long idleSince = 0L;
            while (true) {
                try {
                    if (done.await(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                        return;
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    rootUnreadable = true;
                    recordError(root, interrupted);
                    return;
                }
                long now = System.nanoTime();
                if (abandonSilentJobs(now)) {
                    idleSince = 0L;
                    continue;
                }
                ThreadPoolExecutor executor = pool;
                boolean noFreeWorker = executor == null
                        || executor.getActiveCount() >= executor.getMaximumPoolSize();
                boolean nothingLeftToRun = active.isEmpty()
                        && (executor == null || executor.getQueue().isEmpty() || noFreeWorker);
                if (!nothingLeftToRun) {
                    idleSince = 0L;
                } else if (idleSince == 0L) {
                    idleSince = now;
                } else if (now - idleSince >= stallBudgetNanos) {
                    // Every worker including the replacement ceiling is wedged: drain any
                    // remaining queued directories as skipped so already-scanned subtrees still
                    // finish, and abort the root only if it still cannot complete.
                    if (executor != null) {
                        Runnable queued;
                        while ((queued = executor.getQueue().poll()) != null) {
                            if (queued instanceof ScanJob job) {
                                abandonDirectory(job);
                            }
                        }
                    }
                    if (done.getCount() > 0) {
                        abandonDirectory(rootJob);
                    }
                    return;
                }
            }
        }

        private boolean abandonSilentJobs(long now) {
            boolean abandoned = false;
            for (ScanJob job : active) {
                if (job.isFinished() || now - job.stamp() < stallBudgetNanos) {
                    continue;
                }
                if (job.onSilence(now)) {
                    abandoned = true;
                }
            }
            return abandoned;
        }

        private void abandonDirectory(@Nullable ScanJob job) {
            if (job == null) {
                return;
            }
            boolean isRoot = job.parent() == null;
            String outcome = isRoot ? ", scan aborted" : ", skipped";
            recordError(job.dir(), new IOException("stalled after "
                    + describeSeconds(stallBudgetNanos) + " without a single entry"
                    + job.lastVisitedSuffix() + outcome));
            job.abandon();
            if (isRoot) {
                rootAbandoned = true;
            } else {
                replaceLostWorker();
            }
        }

        /**
         * A worker written off to a syscall never returns, so the pool permanently loses that
         * thread. Add one so the remaining work still has somewhere to run.
         */
        private void replaceLostWorker() {
            ThreadPoolExecutor executor = pool;
            if (executor == null) {
                return;
            }
            int maximum = executor.getMaximumPoolSize();
            if (maximum >= workers + MAX_EXTRA_WORKERS) {
                return;
            }
            executor.setMaximumPoolSize(maximum + 1);
            executor.setCorePoolSize(maximum + 1);
        }

        private void submit(@NotNull ScanJob job) {
            ThreadPoolExecutor executor = pool;
            if (executor == null) {
                throw new IllegalStateException("scan pool is not running");
            }
            executor.execute(job);
        }

        void rootFinished() {
            done.countDown();
        }

        void rootUnreadable() {
            rootUnreadable = true;
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

        int topFiles() {
            return topFiles;
        }

        boolean buildTree() {
            return buildTree;
        }

        private static final class WorkerFactory implements ThreadFactory {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public @NotNull Thread newThread(@NotNull Runnable task) {
                Thread thread = new Thread(task, "dukit-scan-" + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        }
    }

    /**
     * One directory's listing. A job never waits for another job: it submits the child
     * directories it finds and reports its own completion, and the last outstanding completion
     * finishes the parent. That is what lets the watcher (a thread that never lists anything)
     * be the only one deciding that a directory stopped answering.
     */
    private static final class ScanJob implements Runnable {
        private final Path dir;
        private final @Nullable ScanJob parent;
        private final Run run;
        private final Node node;
        /** Guards the node against merges that land while this listing is still running. */
        private final Object lock = new Object();
        /** 1 for this listing plus one per submitted child. */
        private final AtomicInteger pending = new AtomicInteger(1);
        private final AtomicBoolean finished = new AtomicBoolean();
        /** When the last entry arrived, judged only while this listing is in progress. */
        private final AtomicLong stamp = new AtomicLong();
        private volatile @Nullable Path lastVisited;
        private int activeAttempt;
        private @Nullable List<Path> entries;
        private int nextEntry;
        private @Nullable Path stattingChild;
        private boolean listingDone;
        private @Nullable List<ScanJob> deferred;
        private int deferredErrors;

        ScanJob(@NotNull Path dir, @Nullable ScanJob parent, @NotNull Run run) {
            this.dir = dir;
            this.parent = parent;
            this.run = run;
            this.node = new Node(run.topFiles());
            this.stamp.set(System.nanoTime());
        }

        @Override
        public void run() {
            int attempt;
            List<Path> listed;
            synchronized (lock) {
                if (finished.get()) {
                    return;
                }
                attempt = ++activeAttempt;
                listed = entries;
                stattingChild = null;
                stamp.set(System.nanoTime());
                run.active.add(this);
            }
            if (listed == null) {
                listed = readListing(attempt);
                if (listed == null) {
                    return;
                }
            }
            statEntries(attempt, listed);
        }

        /**
         * Reads the directory stream into memory before statting any entry. A stall here means
         * {@link #dir} itself stopped answering; a stall later during {@link #statEntries}
         * blames only the specific child entry whose attribute read blocked.
         */
        private @Nullable List<Path> readListing(int attempt) {
            List<Path> discovered = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path child : stream) {
                    if (!isCurrentAttempt(attempt)) {
                        return null;
                    }
                    if (run.excluded(child)) {
                        stamp.set(System.nanoTime());
                        continue;
                    }
                    stamp.set(System.nanoTime());
                    lastVisited = child;
                    discovered.add(child);
                }
            } catch (IOException | DirectoryIteratorException failure) {
                synchronized (lock) {
                    if (attempt != activeAttempt || finished.get()) {
                        return null;
                    }
                    run.active.remove(this);
                    run.recordError(dir, failure instanceof DirectoryIteratorException iteratorFailure
                            && iteratorFailure.getCause() != null ? iteratorFailure.getCause() : failure);
                    node.errorCount++;
                    if (parent == null) {
                        run.rootUnreadable();
                    }
                    completeOwnListingLocked();
                }
                ownListingDone();
                return null;
            }
            synchronized (lock) {
                if (attempt != activeAttempt || finished.get()) {
                    return null;
                }
                entries = discovered;
                stamp.set(System.nanoTime());
                return discovered;
            }
        }

        private void statEntries(int attempt, @NotNull List<Path> listed) {
            FileStat stat = new FileStat();
            try (StatReader.Cursor cursor = run.statReader.open()) {
                while (true) {
                    Path child;
                    synchronized (lock) {
                        if (attempt != activeAttempt || finished.get()) {
                            return;
                        }
                        if (nextEntry >= listed.size()) {
                            stattingChild = null;
                            run.active.remove(this);
                            completeOwnListingLocked();
                            break;
                        }
                        child = listed.get(nextEntry++);
                        stattingChild = child;
                        lastVisited = child;
                        stamp.set(System.nanoTime());
                    }
                    IOException statFailure = null;
                    try {
                        cursor.read(child, stat);
                    } catch (IOException failure) {
                        statFailure = failure;
                    }
                    Path childDirectory = null;
                    synchronized (lock) {
                        if (attempt != activeAttempt || finished.get()) {
                            return;
                        }
                        stattingChild = null;
                        stamp.set(System.nanoTime());
                        if (statFailure != null) {
                            run.recordError(child, statFailure);
                            node.errorCount++;
                            continue;
                        }
                        childDirectory = applyStatLocked(child, stat);
                    }
                    if (childDirectory != null) {
                        submitChild(childDirectory);
                    }
                }
            }
            ownListingDone();
        }

        private @Nullable Path applyStatLocked(@NotNull Path child, @NotNull FileStat stat) {
            switch (stat.kind) {
                case SYMBOLIC_LINK, OTHER -> {
                    return null;
                }
                case DIRECTORY -> {
                    return child;
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
            return null;
        }

        private void completeOwnListingLocked() {
            listingDone = true;
            node.errorCount += deferredErrors;
            if (deferred != null) {
                for (ScanJob child : deferred) {
                    mergeLocked(child);
                }
                deferred = null;
            }
        }

        private boolean isCurrentAttempt(int attempt) {
            synchronized (lock) {
                return attempt == activeAttempt && !finished.get();
            }
        }

        /**
         * Invoked by the watcher when this job has stayed silent for one budget. If the job was
         * already statting an individual child entry, only that child is abandoned and a
         * replacement worker resumes the remaining entries; otherwise the directory listing
         * itself is abandoned.
         */
        boolean onSilence(long now) {
            Path stalledChild;
            synchronized (lock) {
                if (finished.get() || now - stamp.get() < run.stallBudgetNanos) {
                    return false;
                }
                activeAttempt++;
                stalledChild = stattingChild;
                stattingChild = null;
                run.active.remove(this);
                if (entries != null && stalledChild != null) {
                    node.errorCount++;
                    stamp.set(now);
                }
            }
            if (stalledChild == null) {
                run.abandonDirectory(this);
                return true;
            }
            run.recordError(stalledChild, new IOException("stalled after "
                    + describeSeconds(run.stallBudgetNanos) + " without a single entry, skipped"));
            run.replaceLostWorker();
            try {
                run.submit(this);
            } catch (RejectedExecutionException scanOver) {
                abandon();
            }
            return true;
        }

        private void submitChild(@NotNull Path child) {
            pending.incrementAndGet();
            try {
                run.submit(new ScanJob(child, this, run));
            } catch (RejectedExecutionException scanOver) {
                // The scan already concluded while this thread was asleep in a syscall; its
                // result is discarded, so account the child and let the job wind down.
                childFinished();
            }
        }

        private void ownListingDone() {
            if (pending.decrementAndGet() == 0) {
                finish(false);
            }
        }

        /**
         * Called by this job when its listing and every child are accounted for, and by the
         * watcher when it gives up on this listing. Exactly one of the two wins.
         */
        void abandon() {
            finish(true);
        }

        private void finish(boolean abandoned) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            run.active.remove(this);
            ScanJob enclosing = parent;
            if (enclosing == null) {
                run.rootFinished();
                return;
            }
            if (!abandoned) {
                synchronized (enclosing.lock) {
                    if (enclosing.listingDone) {
                        enclosing.mergeLocked(this);
                    } else {
                        if (enclosing.deferred == null) {
                            enclosing.deferred = new ArrayList<>();
                        }
                        enclosing.deferred.add(this);
                    }
                }
            } else {
                // The parent's tree counts an abandoned child as an error, exactly like an
                // unreadable one; nothing of its contents is merged.
                synchronized (enclosing.lock) {
                    if (enclosing.listingDone) {
                        enclosing.node.errorCount++;
                    } else {
                        enclosing.deferredErrors++;
                    }
                }
            }
            enclosing.childFinished();
        }

        private void childFinished() {
            if (pending.decrementAndGet() == 0) {
                finish(false);
            }
        }

        private void mergeLocked(@NotNull ScanJob child) {
            node.size += child.node.size;
            node.diskSize += child.node.diskSize;
            node.subtreeFileCount += child.node.ownFileCount + child.node.subtreeFileCount;
            node.subtreeDirCount += 1 + child.node.subtreeDirCount;
            node.errorCount += child.node.errorCount;
            if (run.buildTree()) {
                node.children.add(child.node.toEntry(child.dir));
            }
            if (run.largestDirectories != null && child.node.diskSize > 0) {
                run.largestDirectories.offer(child.dir, child.node.size, child.node.diskSize);
            }
        }

        @NotNull Node node() {
            return node;
        }

        @NotNull Path dir() {
            return dir;
        }

        @Nullable ScanJob parent() {
            return parent;
        }

        long stamp() {
            return stamp.get();
        }

        boolean isFinished() {
            return finished.get();
        }

        @NotNull String lastVisitedSuffix() {
            Path visited = lastVisited;
            return visited == null || visited.equals(dir) ? "" : " (last entry seen: " + visited + ")";
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
