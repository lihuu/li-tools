package io.github.lihuu.dukit.core.scan;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Reads entry attributes with one {@code lstat} call through the Foreign Function &amp; Memory
 * API. The JDK's attribute objects expose neither a link count nor an exact inode identity,
 * and asking NIO for them would stat every file twice.
 *
 * <p>Supported only where the C {@code struct stat} layout is known: macOS and glibc-based
 * Linux. Everywhere else {@link NioStatReader} remains the fallback. The layouts are
 * pinned by {@code NativeStatReaderTest}, which compares them with the JDK's own view of
 * the same entries.
 */
final class NativeStatReader implements StatReader {
    private static final int MODE_TYPE_MASK = 0170000;
    private static final int MODE_DIRECTORY = 0040000;
    private static final int MODE_REGULAR = 0100000;
    private static final int MODE_SYMBOLIC_LINK = 0120000;
    private static final int PATH_BUFFER_SIZE = 4096;

    private final Layout layout;
    private final MethodHandle lstat;

    private NativeStatReader(@NotNull Layout layout, @NotNull MethodHandle lstat) {
        this.layout = layout;
        this.lstat = lstat;
    }

    static @Nullable NativeStatReader forCurrentPlatform() {
        String os = System.getProperty("os.name", "");
        if ("Mac OS X".equals(os)) {
            return create(Layout.DARWIN);
        }
        if ("Linux".equals(os)) {
            String arch = System.getProperty("os.arch", "");
            if ("amd64".equals(arch) || "x86_64".equals(arch)) {
                return create(Layout.LINUX_X86_64);
            }
            if ("aarch64".equals(arch)) {
                return create(Layout.LINUX_AARCH64);
            }
        }
        return null;
    }

    private static @NotNull NativeStatReader create(@NotNull Layout layout) {
        Linker linker = Linker.nativeLinker();
        MethodHandle lstat = linker.downcallHandle(
                linker.defaultLookup().find("lstat").orElseThrow(
                        () -> new IllegalStateException("lstat is unavailable on this platform")),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        return new NativeStatReader(layout, lstat);
    }

    @Override
    public @NotNull Cursor open() {
        return new Cursor();
    }

    private final class Cursor implements StatReader.Cursor {
        private final Arena arena = Arena.ofConfined();
        private final MemorySegment statBuffer = arena.allocate(layout.statSize());
        private MemorySegment pathBuffer = arena.allocate(PATH_BUFFER_SIZE);

        @Override
        public void read(@NotNull Path path, @NotNull FileStat stat) throws IOException {
            byte[] encoded = path.toString().getBytes(StandardCharsets.UTF_8);
            int required = encoded.length + 1;
            if (required > pathBuffer.byteSize()) {
                pathBuffer = arena.allocate(required);
            }
            MemorySegment.copy(encoded, 0, pathBuffer, ValueLayout.JAVA_BYTE, 0, encoded.length);
            pathBuffer.set(ValueLayout.JAVA_BYTE, encoded.length, (byte) 0);

            int result;
            try {
                result = (int) lstat.invokeExact(pathBuffer, statBuffer);
            } catch (Throwable failure) {
                throw new IOException("cannot stat " + path, failure);
            }
            if (result != 0) {
                // A path whose bytes do not round-trip through Path.toString() reaches
                // lstat under a different name. Let NIO read it through the raw path bytes
                // instead; if it really is gone, NIO throws the exception the rest of the
                // tool already reports.
                BasicFileAttributes attributes = Files.readAttributes(
                        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                NioStatReader.fill(stat, attributes);
                return;
            }

            int mode = layout.mode(statBuffer);
            stat.kind = switch (mode & MODE_TYPE_MASK) {
                case MODE_DIRECTORY -> FileStat.Kind.DIRECTORY;
                case MODE_REGULAR -> FileStat.Kind.REGULAR_FILE;
                case MODE_SYMBOLIC_LINK -> FileStat.Kind.SYMBOLIC_LINK;
                default -> FileStat.Kind.OTHER;
            };
            stat.size = layout.size(statBuffer);
            stat.linkCount = layout.linkCount(statBuffer);
            stat.device = layout.device(statBuffer);
            stat.inode = layout.inode(statBuffer);
            stat.fileKey = null;
        }

        @Override
        public void close() {
            arena.close();
        }
    }

    /**
     * Field offsets of the platform's {@code struct stat}. The layouts are fixed ABI for
     * the supported platforms; reading a wider field than the C type is never done, so
     * the unsigned values keep their meaning.
     */
    private enum Layout {
        DARWIN(144) {
            @Override
            int mode(@NotNull MemorySegment stat) {
                return Short.toUnsignedInt(stat.get(ValueLayout.JAVA_SHORT, 4));
            }

            @Override
            long linkCount(@NotNull MemorySegment stat) {
                return Short.toUnsignedLong(stat.get(ValueLayout.JAVA_SHORT, 6));
            }

            @Override
            long device(@NotNull MemorySegment stat) {
                return Integer.toUnsignedLong(stat.get(ValueLayout.JAVA_INT, 0));
            }

            @Override
            long inode(@NotNull MemorySegment stat) {
                return stat.get(ValueLayout.JAVA_LONG, 8);
            }

            @Override
            long size(@NotNull MemorySegment stat) {
                return stat.get(ValueLayout.JAVA_LONG, 96);
            }
        },
        LINUX_X86_64(144) {
            @Override
            int mode(@NotNull MemorySegment stat) {
                return stat.get(ValueLayout.JAVA_INT, 24);
            }

            @Override
            long linkCount(@NotNull MemorySegment stat) {
                return stat.get(ValueLayout.JAVA_LONG, 16);
            }

            @Override
            long device(@NotNull MemorySegment stat) {
                return stat.get(ValueLayout.JAVA_LONG, 0);
            }

            @Override
            long inode(@NotNull MemorySegment stat) {
                return stat.get(ValueLayout.JAVA_LONG, 8);
            }

            @Override
            long size(@NotNull MemorySegment stat) {
                return stat.get(ValueLayout.JAVA_LONG, 48);
            }
        },
        LINUX_AARCH64(128) {
            @Override
            int mode(@NotNull MemorySegment stat) {
                return stat.get(ValueLayout.JAVA_INT, 16);
            }

            @Override
            long linkCount(@NotNull MemorySegment stat) {
                return Integer.toUnsignedLong(stat.get(ValueLayout.JAVA_INT, 20));
            }

            @Override
            long device(@NotNull MemorySegment stat) {
                return stat.get(ValueLayout.JAVA_LONG, 0);
            }

            @Override
            long inode(@NotNull MemorySegment stat) {
                return stat.get(ValueLayout.JAVA_LONG, 8);
            }

            @Override
            long size(@NotNull MemorySegment stat) {
                return stat.get(ValueLayout.JAVA_LONG, 48);
            }
        };

        private final int statSize;

        Layout(int statSize) {
            this.statSize = statSize;
        }

        int statSize() {
            return statSize;
        }

        abstract int mode(@NotNull MemorySegment stat);

        abstract long linkCount(@NotNull MemorySegment stat);

        abstract long device(@NotNull MemorySegment stat);

        abstract long inode(@NotNull MemorySegment stat);

        abstract long size(@NotNull MemorySegment stat);
    }
}
