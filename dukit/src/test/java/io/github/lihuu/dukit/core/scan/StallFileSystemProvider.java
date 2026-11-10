package io.github.lihuu.dukit.core.scan;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URI;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.FileStore;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.nio.channels.SeekableByteChannel;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Test-only NIO file system that delegates to the default file system but lets the test
 * make chosen directories behave like a stalled cloud-drive mount: opening their directory
 * listing blocks for {@link #STALL_MILLIS} and then fails. This reproduces, deterministically
 * and inside the JVM, what OneDrive or Google Drive mounts do when their file provider
 * daemon stops answering.
 *
 * <p>Registered through {@code META-INF/services}, so {@link #instance()} finds it in every
 * test JVM. The scan root is a {@code stall:} path over a real directory; the scanner's
 * {@code Files.newDirectoryStream} and attribute reads route through this provider, while
 * the native stat readers still see the real paths behind them.
 */
public final class StallFileSystemProvider extends FileSystemProvider {

    /** How long a "stalled" listing blocks before giving up with an error. */
    private static final long STALL_MILLIS = TimeUnit.SECONDS.toMillis(3);

    private static final Set<String> STALLED_DIRECTORIES = ConcurrentHashMap.newKeySet();
    private static final Set<String> STALLED_ATTRIBUTES = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> TRICKLING_DIRECTORIES = new ConcurrentHashMap<>();
    private static final Map<String, StallFileSystem> FILE_SYSTEMS = new HashMap<>();
    private static final String SCHEME = "stall";

    public StallFileSystemProvider() {
    }

    static @NotNull StallFileSystem mount() throws IOException {
        URI uri = URI.create(SCHEME + ":/");
        try {
            return (StallFileSystem) FileSystems.newFileSystem(uri, Map.of());
        } catch (FileSystemAlreadyExistsException existing) {
            return (StallFileSystem) FileSystems.getFileSystem(uri);
        }
    }

    static @NotNull StallFileSystemProvider instance() {
        for (FileSystemProvider provider : FileSystemProvider.installedProviders()) {
            if (provider instanceof StallFileSystemProvider stall) {
                return stall;
            }
        }
        throw new IllegalStateException("StallFileSystemProvider is not registered");
    }

    /** Marks a {@code stall:} path so that listing it blocks like an unresponsive cloud mount. */
    static void stallDirectory(@NotNull Path stallPath) {
        STALLED_DIRECTORIES.add(stallPath.toString());
    }

    /**
     * Marks a {@code stall:} path so that reading its attributes ({@code lstat} /
     * {@code Files.readAttributes}) blocks like a dead mount point inside a healthy parent.
     */
    static void stallAttributes(@NotNull Path stallPath) {
        STALLED_ATTRIBUTES.add(stallPath.toString());
    }

    /**
     * Makes a {@code stall:} path list its real entries with a pause before each one — a slow
     * mount that keeps making progress, which must never be mistaken for a stalled one.
     */
    static void trickleDirectory(@NotNull Path stallPath, long perEntryMillis) {
        TRICKLING_DIRECTORIES.put(stallPath.toString(), perEntryMillis);
    }

    static void unstallAll() {
        STALLED_DIRECTORIES.clear();
        STALLED_ATTRIBUTES.clear();
        TRICKLING_DIRECTORIES.clear();
    }

    @Override
    public @NotNull String getScheme() {
        return SCHEME;
    }

    @Override
    public @NotNull FileSystem newFileSystem(@NotNull URI uri, @NotNull Map<String, ?> environment)
            throws FileSystemAlreadyExistsException {
        synchronized (FILE_SYSTEMS) {
            StallFileSystem existing = FILE_SYSTEMS.get(uri.toString());
            if (existing != null) {
                throw new FileSystemAlreadyExistsException(uri.toString());
            }
            StallFileSystem created = new StallFileSystem(this, FileSystems.getDefault());
            FILE_SYSTEMS.put(uri.toString(), created);
            return created;
        }
    }

    @Override
    public @NotNull FileSystem getFileSystem(@NotNull URI uri) {
        synchronized (FILE_SYSTEMS) {
            StallFileSystem existing = FILE_SYSTEMS.get(uri.toString());
            if (existing == null) {
                throw new FileSystemNotFoundException(uri.toString());
            }
            return existing;
        }
    }

    @Override
    public @NotNull Path getPath(@NotNull URI uri) {
        throw new UnsupportedOperationException("tests create stall paths through StallFileSystem.getPath");
    }

    @Override
    public @NotNull DirectoryStream<Path> newDirectoryStream(@NotNull Path dir,
            @NotNull DirectoryStream.Filter<? super Path> filter) throws IOException {
        if (STALLED_DIRECTORIES.contains(dir.toString())) {
            blockLikeAStalledMount();
            throw new IOException("simulated stalled directory listing: " + dir);
        }
        DirectoryStream<Path> inner = FileSystems.getDefault().provider()
                .newDirectoryStream(unwrap(dir), filter);
        StallFileSystem fileSystem = (StallFileSystem) dir.getFileSystem();
        Long perEntryMillis = TRICKLING_DIRECTORIES.get(dir.toString());
        if (perEntryMillis != null) {
            return new TricklingDirectoryStream(fileSystem, inner, perEntryMillis);
        }
        return new WrappingDirectoryStream(fileSystem, inner);
    }

    /**
     * Blocks for the whole stall window even when interrupted: a thread inside an
     * uninterruptible kernel syscall cannot be woken, which is exactly why the scan cannot
     * cancel it and has to abandon it instead.
     */
    private static void blockLikeAStalledMount() {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STALL_MILLIS);
        long remaining = deadline - System.nanoTime();
        while (remaining > 0) {
            try {
                TimeUnit.NANOSECONDS.sleep(remaining);
            } catch (InterruptedException uncancellable) {
                // Deliberate: the caller models a syscall that no signal can break out of.
            }
            remaining = deadline - System.nanoTime();
        }
    }

    @Override
    public <A extends java.nio.file.attribute.BasicFileAttributes> A readAttributes(@NotNull Path path,
            @NotNull Class<A> type, @NotNull LinkOption... options) throws IOException {
        if (STALLED_ATTRIBUTES.contains(path.toString())) {
            blockLikeAStalledMount();
            throw new IOException("simulated stalled attribute read: " + path);
        }
        return FileSystems.getDefault().provider().readAttributes(unwrap(path), type, options);
    }

    @Override
    public <V extends java.nio.file.attribute.FileAttributeView> V getFileAttributeView(@NotNull Path path,
            @NotNull Class<V> type, @NotNull LinkOption... options) {
        return FileSystems.getDefault().provider().getFileAttributeView(unwrap(path), type, options);
    }

    @Override
    public void checkAccess(@NotNull Path path, @NotNull java.nio.file.AccessMode... modes) throws IOException {
        FileSystems.getDefault().provider().checkAccess(unwrap(path), modes);
    }

    @Override
    public void createDirectory(@NotNull Path dir, @NotNull FileAttribute<?>... attributes) throws IOException {
        FileSystems.getDefault().provider().createDirectory(unwrap(dir), attributes);
    }

    @Override
    public void delete(@NotNull Path path) throws IOException {
        FileSystems.getDefault().provider().delete(unwrap(path));
    }

    @Override
    public void copy(@NotNull Path source, @NotNull Path target, @NotNull CopyOption... options) throws IOException {
        FileSystems.getDefault().provider().copy(unwrap(source), unwrap(target), options);
    }

    @Override
    public void move(@NotNull Path source, @NotNull Path target, @NotNull CopyOption... options) throws IOException {
        FileSystems.getDefault().provider().move(unwrap(source), unwrap(target), options);
    }

    @Override
    public boolean isSameFile(@NotNull Path path, @NotNull Path path2) throws IOException {
        return FileSystems.getDefault().provider().isSameFile(unwrap(path), unwrap(path2));
    }

    @Override
    public boolean isHidden(@NotNull Path path) throws IOException {
        return FileSystems.getDefault().provider().isHidden(unwrap(path));
    }

    @Override
    public @NotNull FileStore getFileStore(@NotNull Path path) throws IOException {
        return FileSystems.getDefault().provider().getFileStore(unwrap(path));
    }

    @Override
    public @NotNull SeekableByteChannel newByteChannel(@NotNull Path path,
            @NotNull Set<? extends OpenOption> options, @NotNull FileAttribute<?>... attributes) throws IOException {
        return FileSystems.getDefault().provider().newByteChannel(unwrap(path), options, attributes);
    }

    @Override
    public @NotNull Map<String, Object> readAttributes(@NotNull Path path, @NotNull String attributes,
            @NotNull LinkOption... options) throws IOException {
        return FileSystems.getDefault().provider().readAttributes(unwrap(path), attributes, options);
    }

    @Override
    public void setAttribute(@NotNull Path path, @NotNull String attribute, @NotNull Object value,
            @NotNull LinkOption... options) throws IOException {
        FileSystems.getDefault().provider().setAttribute(unwrap(path), attribute, value, options);
    }

    private static @NotNull Path unwrap(@NotNull Path stallPath) {
        return ((StallPath) stallPath).real();
    }

    /** Wraps listed entries back into {@code stall:} paths so recursion stays inside this provider. */
    private record WrappingDirectoryStream(@NotNull StallFileSystem fileSystem,
            @NotNull DirectoryStream<Path> inner) implements DirectoryStream<Path> {

        @Override
        public @NotNull Iterator<Path> iterator() {
            Iterator<Path> source = inner.iterator();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return source.hasNext();
                }

                @Override
                public Path next() {
                    return fileSystem.wrap(source.next());
                }
            };
        }

        @Override
        public void close() throws IOException {
            inner.close();
        }
    }

    /**
     * Lists real entries with a pause before each one: total listing time grows with the entry
     * count, but the stream never goes silent for longer than one pause.
     */
    private record TricklingDirectoryStream(@NotNull StallFileSystem fileSystem,
            @NotNull DirectoryStream<Path> inner, long perEntryMillis) implements DirectoryStream<Path> {

        @Override
        public @NotNull Iterator<Path> iterator() {
            Iterator<Path> source = inner.iterator();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return source.hasNext();
                }

                @Override
                public Path next() {
                    try {
                        TimeUnit.MILLISECONDS.sleep(perEntryMillis);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return fileSystem.wrap(source.next());
                }
            };
        }

        @Override
        public void close() throws IOException {
            inner.close();
        }
    }
}
