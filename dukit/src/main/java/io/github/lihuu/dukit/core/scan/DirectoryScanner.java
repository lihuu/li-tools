package io.github.lihuu.dukit.core.scan;

import io.github.lihuu.dukit.core.model.DirEntry;
import io.github.lihuu.dukit.core.model.ScanError;
import io.github.lihuu.dukit.core.model.ScanResult;
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
 * Walks a directory tree once and aggregates apparent sizes into an immutable
 * {@link DirEntry} tree. Symlinks are never followed. Unreadable entries are collected as
 * errors; the scan continues past them. Only the scan root failing to be a readable
 * directory aborts the scan.
 *
 * <p>Directories contribute their own size to the total, matching du. Hardlinked regular
 * files count their sizes once per scan (first occurrence wins); every hardlink still
 * counts as a file.
 */
public final class DirectoryScanner {
    private static final long PROGRESS_INTERVAL_MASK = 0x3FF;

    private final List<String> excludePatterns;

    public DirectoryScanner(@NotNull List<String> excludePatterns) {
        this.excludePatterns = List.copyOf(excludePatterns);
    }

    public @NotNull ScanResult scan(@NotNull Path root, @NotNull ProgressListener listener) throws IOException {
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

        Visitor visitor = new Visitor(absolute, nameMatchers, relativeMatchers, listener);
        Files.walkFileTree(absolute, Set.of(), Integer.MAX_VALUE, visitor);
        if (visitor.rootEntry == null) {
            String reason = visitor.errors.isEmpty() ? "not a readable directory" : visitor.errors.get(0).message();
            throw new IOException("cannot scan " + absolute + ": " + reason);
        }
        listener.onUpdate(visitor.fileCount, visitor.bytesSeen, absolute);
        return new ScanResult(visitor.rootEntry, visitor.errors);
    }

    private static final class Visitor extends SimpleFileVisitor<Path> {
        private final Path root;
        private final List<PathMatcher> nameMatchers;
        private final List<PathMatcher> relativeMatchers;
        private final ProgressListener listener;
        private final Deque<Accumulator> stack = new ArrayDeque<>();
        private final Set<Object> seenFileKeys = new HashSet<>();
        private final List<ScanError> errors = new ArrayList<>();
        private long fileCount;
        private long bytesSeen;
        private @Nullable DirEntry rootEntry;

        Visitor(@NotNull Path root, @NotNull List<PathMatcher> nameMatchers,
                @NotNull List<PathMatcher> relativeMatchers, @NotNull ProgressListener listener) {
            this.root = root;
            this.nameMatchers = nameMatchers;
            this.relativeMatchers = relativeMatchers;
            this.listener = listener;
        }

        @Override
        public FileVisitResult preVisitDirectory(@NotNull Path dir, @NotNull BasicFileAttributes attrs) {
            if (excluded(dir)) {
                return FileVisitResult.SKIP_SUBTREE;
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
            bytesSeen += attrs.size();
            Accumulator current = stack.peek();
            if (current != null) {
                current.children.add(new DirEntry(file.getFileName().toString(), file, false, size, 1, 0, 0, List.of()));
                progress();
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(@NotNull Path path, @NotNull IOException failure) {
            Accumulator current = stack.peek();
            if (current != null && path.equals(current.dir)) {
                // The directory failed to open, so its accumulator never receives a
                // postVisit; drop it here and report against the parent instead.
                stack.pop();
                recordError(path, failure);
            } else if (Files.isSymbolicLink(path)) {
                // Broken symlinks are expected with NOFOLLOW_LINKS; not an error.
            } else {
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
            DirEntry entry = finished.toEntry();
            if (dir.equals(root)) {
                rootEntry = entry;
            } else {
                Accumulator parent = stack.peek();
                if (parent != null) {
                    parent.children.add(entry);
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
                    listener.onUpdate(fileCount, bytesSeen, current.dir);
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
        private long errorCount;

        Accumulator(@NotNull Path dir) {
            this.dir = dir;
        }

        private DirEntry toEntry() {
            long size = 0;
            long files = 0;
            long dirs = 0;
            long subtreeErrors = errorCount;
            for (DirEntry child : children) {
                size += child.size();
                files += child.fileCount();
                dirs += child.dirCount() + (child.directory() ? 1 : 0);
                subtreeErrors += child.errorCount();
            }
            children.sort(Comparator.comparingLong((DirEntry entry) -> entry.size())
                    .reversed()
                    .thenComparing(DirEntry::name));
            return new DirEntry(name(dir), dir, true, size, files, dirs, subtreeErrors, children);
        }

        private static String name(@NotNull Path path) {
            Path name = path.getFileName();
            return name != null ? name.toString() : path.toString();
        }
    }
}
