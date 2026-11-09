package io.github.lihuu.dukit.core.scan;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchService;
import java.nio.file.WatchKey;
import java.nio.file.Watchable;
import java.util.Iterator;

/**
 * A path in the {@code stall:} file system. All navigation delegates to a real path of the
 * default file system and re-wraps the results, so absolute-path strings handed to native
 * stat readers still denote the real files.
 */
final class StallPath implements Path, Watchable {

    private final StallFileSystem fileSystem;
    private final Path real;

    StallPath(@NotNull StallFileSystem fileSystem, @NotNull Path real) {
        this.fileSystem = fileSystem;
        this.real = real;
    }

    @NotNull Path real() {
        return real;
    }

    private @NotNull Path wrap(@NotNull Path path) {
        return fileSystem.wrap(path);
    }

    @Override
    public @NotNull FileSystem getFileSystem() {
        return fileSystem;
    }

    @Override
    public boolean isAbsolute() {
        return real.isAbsolute();
    }

    @Override
    public @Nullable Path getRoot() {
        Path root = real.getRoot();
        return root == null ? null : wrap(root);
    }

    @Override
    public @Nullable Path getFileName() {
        Path name = real.getFileName();
        return name == null ? null : wrap(name);
    }

    @Override
    public @Nullable Path getParent() {
        Path parent = real.getParent();
        return parent == null ? null : wrap(parent);
    }

    @Override
    public int getNameCount() {
        return real.getNameCount();
    }

    @Override
    public @NotNull Path getName(int index) {
        return wrap(real.getName(index));
    }

    @Override
    public @NotNull Path subpath(int beginIndex, int endIndex) {
        return wrap(real.subpath(beginIndex, endIndex));
    }

    @Override
    public boolean startsWith(@NotNull Path other) {
        return real.startsWith(unwrap(other));
    }

    @Override
    public boolean startsWith(@NotNull String other) {
        return real.startsWith(other);
    }

    @Override
    public boolean endsWith(@NotNull Path other) {
        return real.endsWith(unwrap(other));
    }

    @Override
    public boolean endsWith(@NotNull String other) {
        return real.endsWith(other);
    }

    @Override
    public @NotNull Path normalize() {
        return wrap(real.normalize());
    }

    @Override
    public @NotNull Path resolve(@NotNull Path other) {
        return wrap(real.resolve(unwrap(other)));
    }

    @Override
    public @NotNull Path resolve(@NotNull String other) {
        return wrap(real.resolve(other));
    }

    @Override
    public @NotNull Path resolveSibling(@NotNull Path other) {
        return wrap(real.resolveSibling(unwrap(other)));
    }

    @Override
    public @NotNull Path resolveSibling(@NotNull String other) {
        return wrap(real.resolveSibling(other));
    }

    @Override
    public @NotNull Path relativize(@NotNull Path other) {
        return wrap(real.relativize(unwrap(other)));
    }

    @Override
    public @NotNull Path toAbsolutePath() {
        return wrap(real.toAbsolutePath());
    }

    @Override
    public @NotNull Path toRealPath(@NotNull LinkOption... options) throws IOException {
        return wrap(real.toRealPath(options));
    }

    @Override
    public @NotNull File toFile() {
        return real.toFile();
    }

    @Override
    public @NotNull URI toUri() {
        throw new UnsupportedOperationException("stall paths have no URI form");
    }

    @Override
    public @NotNull Iterator<Path> iterator() {
        Iterator<Path> source = real.iterator();
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return source.hasNext();
            }

            @Override
            public Path next() {
                return wrap(source.next());
            }
        };
    }

    @Override
    public int compareTo(@NotNull Path other) {
        return real.compareTo(unwrap(other));
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof StallPath stall && real.equals(stall.real);
    }

    @Override
    public int hashCode() {
        return real.hashCode();
    }

    @Override
    public @NotNull String toString() {
        return real.toString();
    }

    private static @NotNull Path unwrap(@NotNull Path path) {
        return path instanceof StallPath stall ? stall.real : path;
    }

    @Override
    public @NotNull WatchKey register(@NotNull WatchService watcher, @NotNull WatchEvent.Kind<?>... events)
            throws IOException {
        return real.register(watcher, events);
    }

    @Override
    public @NotNull WatchKey register(@NotNull WatchService watcher,
            @NotNull WatchEvent.Kind<?>[] events, @NotNull WatchEvent.Modifier... modifiers) throws IOException {
        return real.register(watcher, events, modifiers);
    }
}
