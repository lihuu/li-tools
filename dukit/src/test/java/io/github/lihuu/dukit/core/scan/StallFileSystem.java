package io.github.lihuu.dukit.core.scan;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.Set;

/**
 * The {@code stall:} file system: a thin shell over the default file system. Every path is a
 * {@link StallPath} over a real path, so scanners traverse a real tree while chosen
 * directories can be made to stall.
 */
final class StallFileSystem extends FileSystem {

    private final StallFileSystemProvider provider;
    private final FileSystem real;

    StallFileSystem(@NotNull StallFileSystemProvider provider, @NotNull FileSystem real) {
        this.provider = provider;
        this.real = real;
    }

    @NotNull Path wrap(@NotNull Path realPath) {
        return realPath instanceof StallPath ? realPath : new StallPath(this, realPath);
    }

    @Override
    public @NotNull StallFileSystemProvider provider() {
        return provider;
    }

    @Override
    public @NotNull String getSeparator() {
        return real.getSeparator();
    }

    @Override
    public @NotNull Path getPath(@NotNull String first, @NotNull String... more) {
        return wrap(real.getPath(first, more));
    }

    @Override
    public @NotNull PathMatcher getPathMatcher(@NotNull String syntaxAndPattern) {
        PathMatcher inner = real.getPathMatcher(syntaxAndPattern);
        return path -> inner.matches(unwrap(path));
    }

    private static @NotNull Path unwrap(@NotNull Path path) {
        return ((StallPath) path).real();
    }

    @Override
    public @NotNull Iterable<Path> getRootDirectories() {
        return real.getRootDirectories();
    }

    @Override
    public @NotNull Iterable<FileStore> getFileStores() {
        return real.getFileStores();
    }

    @Override
    public @NotNull Set<String> supportedFileAttributeViews() {
        return real.supportedFileAttributeViews();
    }

    @Override
    public @NotNull UserPrincipalLookupService getUserPrincipalLookupService() {
        return real.getUserPrincipalLookupService();
    }

    @Override
    public boolean isOpen() {
        return real.isOpen();
    }

    @Override
    public boolean isReadOnly() {
        return real.isReadOnly();
    }

    @Override
    public @NotNull WatchService newWatchService() throws IOException {
        return real.newWatchService();
    }

    @Override
    public void close() {
        // Deliberately not closed: closing would have to forward to the default file system.
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof StallFileSystem stall && real.equals(stall.real);
    }

    @Override
    public int hashCode() {
        return real.hashCode();
    }

    @Override
    public @NotNull String toString() {
        return "stall:" + real;
    }
}
