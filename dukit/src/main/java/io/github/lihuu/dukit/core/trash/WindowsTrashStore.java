package io.github.lihuu.dukit.core.trash;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.EnumSet;

/**
 * The Windows Recycle Bin, requested through {@code shell32!SHFileOperationW}.
 *
 * <p>The Recycle Bin is per volume and the shell chooses the final name, so a prepared target
 * has no destination path: {@link TrashTarget#destination()} is {@code null} and the
 * confirmation page shows the volume. The shell may still permanently delete when a volume's
 * Recycle Bin is full or disabled; {@code FOF_WANTNUKEWARNING} asks it to warn first, and this
 * adapter never lowers that requirement.
 *
 * <p>Everything that can be checked before the shell call is checked twice: once in
 * {@link #prepare} for the preview and once in {@link #move}. A non-zero return or the
 * aborted flag is reported as {@link TrashException.Outcome#UNKNOWN}, because a shell failure
 * can leave a partially removed tree.
 */
public final class WindowsTrashStore implements TrashStore {
    /** {@code MAX_PATH}, including the terminating NUL. */
    static final int MAX_PATH = 260;

    private static final int VOLUME_BUFFER_CHARS = 1024;
    private static final int FO_DELETE = 3;

    private static final short FOF_SILENT = 0x0004;
    private static final short FOF_NOCONFIRMATION = 0x0010;
    private static final short FOF_ALLOWUNDO = 0x0040;
    private static final short FOF_NOERRORUI = 0x0400;
    private static final short FOF_NO_CONNECTED_ELEMENTS = 0x2000;
    private static final short FOF_WANTNUKEWARNING = 0x4000;
    static final short FOF_FLAGS = (short) (FOF_ALLOWUNDO | FOF_NOCONFIRMATION | FOF_SILENT
            | FOF_NOERRORUI | FOF_WANTNUKEWARNING | FOF_NO_CONNECTED_ELEMENTS);

    /**
     * The 64-bit {@code SHFILEOPSTRUCTW} layout. Windows x64 and arm64 are both LLP64, so the
     * offsets are the same. The test pins every offset and the total size.
     */
    static final MemoryLayout SH_FILE_OPERATION_STRUCT = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("hwnd"),
            ValueLayout.JAVA_INT.withName("wFunc"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("pFrom"),
            ValueLayout.ADDRESS.withName("pTo"),
            ValueLayout.JAVA_SHORT.withName("fFlags"),
            MemoryLayout.paddingLayout(2),
            ValueLayout.JAVA_INT.withName("fAnyOperationsAborted"),
            ValueLayout.ADDRESS.withName("hNameMappings"),
            ValueLayout.ADDRESS.withName("lpszProgressTitle"));

    private static final long HWND = offset("hwnd");
    private static final long W_FUNC = offset("wFunc");
    private static final long P_FROM = offset("pFrom");
    private static final long P_TO = offset("pTo");
    private static final long FLAGS = offset("fFlags");
    private static final long ANY_OPERATIONS_ABORTED = offset("fAnyOperationsAborted");
    private static final long NAME_MAPPINGS = offset("hNameMappings");
    private static final long PROGRESS_TITLE = offset("lpszProgressTitle");

    @Override
    public @NotNull TrashTarget prepare(@NotNull Path target) throws TrashException {
        requireWindows();
        Path source = target.toAbsolutePath().normalize();
        requireShellPath(source);
        Path volumeRoot = volumeRootOf(source);
        requireDeletableVolume(source, volumeRoot);
        Path trashRoot = recycleBinRoot(volumeRoot);
        if (source.startsWith(trashRoot)) {
            throw new TrashException(TrashException.Reason.TARGET_IS_TRASH, TrashException.Outcome.NOT_STARTED,
                    "target is inside the Recycle Bin: " + source);
        }
        requireDirectory(attributesOf(source), source);
        requireNoReparsePointInTree(source);
        return new TrashTarget(source, null, trashRoot, "Recycle Bin (" + volumeRoot + ")");
    }

    @Override
    public @NotNull TrashReceipt move(@NotNull TrashTarget target) throws TrashException {
        requireWindows();
        Path source = target.source().toAbsolutePath().normalize();
        requireShellPath(source);
        Path volumeRoot = volumeRootOf(source);
        requireDeletableVolume(source, volumeRoot);
        Path trashRoot = recycleBinRoot(volumeRoot);
        if (source.startsWith(trashRoot)) {
            throw new TrashException(TrashException.Reason.TARGET_IS_TRASH, TrashException.Outcome.NOT_STARTED,
                    "target is inside the Recycle Bin: " + source);
        }
        requireDirectory(attributesOf(source), source);

        try (Arena arena = Arena.ofConfined()) {
            MethodHandle shellOperation = lookup(arena, "shell32", "SHFileOperationW",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            MemorySegment from = widePath(arena, source.toString());
            MemorySegment operation = arena.allocate(SH_FILE_OPERATION_STRUCT);
            operation.fill((byte) 0);
            operation.set(ValueLayout.ADDRESS, HWND, MemorySegment.NULL);
            operation.set(ValueLayout.JAVA_INT, W_FUNC, FO_DELETE);
            operation.set(ValueLayout.ADDRESS, P_FROM, from);
            operation.set(ValueLayout.ADDRESS, P_TO, MemorySegment.NULL);
            operation.set(ValueLayout.JAVA_SHORT, FLAGS, FOF_FLAGS);
            operation.set(ValueLayout.JAVA_INT, ANY_OPERATIONS_ABORTED, 0);
            operation.set(ValueLayout.ADDRESS, NAME_MAPPINGS, MemorySegment.NULL);
            operation.set(ValueLayout.ADDRESS, PROGRESS_TITLE, MemorySegment.NULL);

            int result = invoke(shellOperation, operation);
            int aborted = operation.get(ValueLayout.JAVA_INT, ANY_OPERATIONS_ABORTED);
            if (result != 0) {
                throw new TrashException(reasonFor(result), TrashException.Outcome.UNKNOWN,
                        "SHFileOperationW failed: 0x" + Integer.toHexString(result));
            }
            if (aborted != 0) {
                throw new TrashException(TrashException.Reason.ABORTED, TrashException.Outcome.UNKNOWN,
                        "the shell aborted moving to the Recycle Bin: " + source);
            }
            if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new TrashException(TrashException.Reason.IO_ERROR, TrashException.Outcome.UNKNOWN,
                        "SHFileOperationW reported success but the target still exists: " + source);
            }
            return new TrashReceipt(source, null);
        }
    }

    private static void requireWindows() throws TrashException {
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            throw new TrashException(TrashException.Reason.UNSUPPORTED_PLATFORM,
                    TrashException.Outcome.NOT_STARTED, "the Windows Recycle Bin is only available on Windows");
        }
    }

    /**
     * {@code SHFileOperationW} takes a Win32 path, not a {@code \\?\} path, and fails on paths
     * that reach {@code MAX_PATH}. UNC paths have no Recycle Bin and are refused by name
     * before the volume lookup touches the network.
     *
     * <p>{@link Path} already strips the {@code \\?\} prefix (and turns
     * {@code \\?\UNC\...} into a UNC path), so the string that reaches the shell can never
     * carry it; a path that was extended only to exceed {@code MAX_PATH} is still caught by
     * the length check.
     */
    private static void requireShellPath(@NotNull Path source) throws TrashException {
        String text = source.toString();
        if (text.startsWith("\\\\")) {
            throw new TrashException(TrashException.Reason.NETWORK_VOLUME, TrashException.Outcome.NOT_STARTED,
                    "UNC paths have no Recycle Bin: " + text);
        }
        if (text.length() >= MAX_PATH) {
            throw new TrashException(TrashException.Reason.PATH_TOO_LONG, TrashException.Outcome.NOT_STARTED,
                    "path reaches MAX_PATH: " + text);
        }
        if (!source.isAbsolute()) {
            throw new TrashException(TrashException.Reason.IO_ERROR, TrashException.Outcome.NOT_STARTED,
                    "target is not absolute: " + text);
        }
    }

    /**
     * A directory that is itself a volume mount point has a different volume path from its
     * parent. Moving it would move the mount, not the data, so it is refused.
     */
    private static void requireDeletableVolume(@NotNull Path source, @NotNull Path volumeRoot) throws TrashException {
        if (source.equals(volumeRoot)) {
            throw new TrashException(TrashException.Reason.MOUNT_POINT, TrashException.Outcome.NOT_STARTED,
                    "a volume root cannot be deleted: " + source);
        }
        Path parent = source.getParent();
        if (parent == null) {
            throw new TrashException(TrashException.Reason.MOUNT_POINT, TrashException.Outcome.NOT_STARTED,
                    "target has no parent: " + source);
        }
        if (!volumeRootOf(parent).equals(volumeRoot)) {
            throw new TrashException(TrashException.Reason.MOUNT_POINT, TrashException.Outcome.NOT_STARTED,
                    "target is a volume mount point: " + source);
        }
        int type = driveTypeOf(volumeRoot);
        switch (type) {
            case 4 -> throw new TrashException(TrashException.Reason.NETWORK_VOLUME,
                    TrashException.Outcome.NOT_STARTED, "network volume has no Recycle Bin: " + volumeRoot);
            case 2, 3, 6 -> {
                // DRIVE_REMOVABLE, DRIVE_FIXED, DRIVE_RAMDISK: the shell owns the Recycle Bin.
            }
            default -> throw new TrashException(TrashException.Reason.NO_TRASH,
                    TrashException.Outcome.NOT_STARTED,
                    "volume type " + type + " has no Recycle Bin: " + volumeRoot);
        }
    }

    /**
     * Refuses a target that is, or contains, a reparse point. {@code SHFileOperationW} would
     * otherwise be free to follow a junction or a symbolic link outside the scanned tree.
     */
    private static void requireNoReparsePointInTree(@NotNull Path source) throws TrashException {
        Path[] reparsePoint = new Path[1];
        try {
            Files.walkFileTree(source, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE,
                    new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                            if (isReparsePoint(attributes)) {
                                reparsePoint[0] = directory;
                                return FileVisitResult.TERMINATE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                            if (isReparsePoint(attributes)) {
                                reparsePoint[0] = file;
                                return FileVisitResult.TERMINATE;
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (AccessDeniedException denied) {
            throw new TrashException(TrashException.Reason.PERMISSION_DENIED, TrashException.Outcome.NOT_STARTED,
                    "cannot inspect the target tree: " + source, denied);
        } catch (IOException failure) {
            throw new TrashException(TrashException.Reason.IO_ERROR, TrashException.Outcome.NOT_STARTED,
                    "cannot inspect the target tree: " + source, failure);
        }
        if (reparsePoint[0] != null) {
            throw new TrashException(TrashException.Reason.REPARSE_POINT, TrashException.Outcome.NOT_STARTED,
                    "refusing to recycle a tree containing a reparse point: " + reparsePoint[0]);
        }
    }

    private static boolean isReparsePoint(@NotNull BasicFileAttributes attributes) {
        return attributes.isSymbolicLink() || attributes.isOther();
    }

    private static void requireDirectory(@NotNull BasicFileAttributes attributes, @NotNull Path source)
            throws TrashException {
        if (isReparsePoint(attributes)) {
            throw new TrashException(TrashException.Reason.REPARSE_POINT, TrashException.Outcome.NOT_STARTED,
                    "refusing to recycle a reparse point: " + source);
        }
        if (!attributes.isDirectory()) {
            throw new TrashException(TrashException.Reason.IO_ERROR, TrashException.Outcome.NOT_STARTED,
                    "only directories can be moved to the Recycle Bin: " + source);
        }
    }

    private static @NotNull BasicFileAttributes attributesOf(@NotNull Path path) throws TrashException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            throw new TrashException(TrashException.Reason.NOT_FOUND, TrashException.Outcome.NOT_STARTED,
                    "target no longer exists: " + path, missing);
        } catch (AccessDeniedException denied) {
            throw new TrashException(TrashException.Reason.PERMISSION_DENIED, TrashException.Outcome.NOT_STARTED,
                    "cannot inspect target: " + path, denied);
        } catch (IOException failure) {
            throw new TrashException(TrashException.Reason.IO_ERROR, TrashException.Outcome.NOT_STARTED,
                    "cannot inspect target: " + path, failure);
        }
    }

    private static @NotNull Path recycleBinRoot(@NotNull Path volumeRoot) {
        return volumeRoot.resolve("$Recycle.Bin");
    }

    /**
     * Resolves the volume path for {@code path}; for a folder mount point this returns the
     * mount point itself, which is how {@link #requireDeletableVolume} detects one.
     */
    private static @NotNull Path volumeRootOf(@NotNull Path path) throws TrashException {
        try (Arena arena = Arena.ofConfined()) {
            MethodHandle volumePathName = lookup(arena, "kernel32", "GetVolumePathNameW",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT));
            MemorySegment source = widePath(arena, path.toString());
            MemorySegment buffer = arena.allocate(VOLUME_BUFFER_CHARS * 2L);
            int result = invoke3(volumePathName, source, buffer, VOLUME_BUFFER_CHARS);
            if (result == 0) {
                throw new TrashException(TrashException.Reason.IO_ERROR, TrashException.Outcome.NOT_STARTED,
                        "GetVolumePathNameW failed for " + path);
            }
            return Path.of(readWide(buffer, VOLUME_BUFFER_CHARS)).toAbsolutePath().normalize();
        }
    }

    private static int driveTypeOf(@NotNull Path volumeRoot) throws TrashException {
        try (Arena arena = Arena.ofConfined()) {
            MethodHandle driveType = lookup(arena, "kernel32", "GetDriveTypeW",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            String root = volumeRoot.toString();
            if (!root.endsWith("\\")) {
                root = root + "\\";
            }
            return invoke(driveType, widePath(arena, root));
        }
    }

    private static @NotNull MethodHandle lookup(@NotNull Arena arena, @NotNull String library,
            @NotNull String symbol, @NotNull FunctionDescriptor descriptor) throws TrashException {
        MemorySegment function;
        try {
            function = SymbolLookup.libraryLookup(library, arena).find(symbol)
                    .orElseThrow(() -> new TrashException(TrashException.Reason.UNSUPPORTED_PLATFORM,
                            TrashException.Outcome.NOT_STARTED,
                            "missing native symbol " + library + "!" + symbol));
        } catch (IllegalArgumentException failure) {
            throw new TrashException(TrashException.Reason.IO_ERROR, TrashException.Outcome.NOT_STARTED,
                    "cannot load " + library + " for " + symbol, failure);
        }
        try {
            return Linker.nativeLinker().downcallHandle(function, descriptor);
        } catch (IllegalArgumentException failure) {
            throw new TrashException(TrashException.Reason.IO_ERROR, TrashException.Outcome.NOT_STARTED,
                    "cannot link " + symbol, failure);
        }
    }

    private static int invoke(@NotNull MethodHandle handle, @NotNull MemorySegment argument) throws TrashException {
        try {
            return (int) handle.invokeExact(argument);
        } catch (RuntimeException failure) {
            throw new TrashException(TrashException.Reason.IO_ERROR, TrashException.Outcome.NOT_STARTED,
                    "cannot invoke " + handle, failure);
        } catch (Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new TrashException(TrashException.Reason.IO_ERROR, TrashException.Outcome.NOT_STARTED,
                    "cannot invoke " + handle, failure);
        }
    }

    private static int invoke3(@NotNull MethodHandle handle, @NotNull MemorySegment first,
            @NotNull MemorySegment second, int third) throws TrashException {
        try {
            return (int) handle.invokeExact(first, second, third);
        } catch (RuntimeException failure) {
            throw new TrashException(TrashException.Reason.IO_ERROR, TrashException.Outcome.NOT_STARTED,
                    "cannot invoke " + handle, failure);
        } catch (Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new TrashException(TrashException.Reason.IO_ERROR, TrashException.Outcome.NOT_STARTED,
                    "cannot invoke " + handle, failure);
        }
    }

    private static @NotNull MemorySegment widePath(@NotNull Arena arena, @NotNull String path) {
        byte[] bytes = widePathBytes(path);
        MemorySegment segment = arena.allocate(bytes.length);
        MemorySegment.copy(bytes, 0, segment, ValueLayout.JAVA_BYTE, 0, bytes.length);
        return segment;
    }

    /**
     * UTF-16LE with the extra NUL that {@code SHFileOperationW} requires for {@code pFrom}.
     */
    static byte @NotNull [] widePathBytes(@NotNull String path) {
        byte[] text = path.getBytes(StandardCharsets.UTF_16LE);
        return Arrays.copyOf(text, text.length + 4);
    }

    private static @NotNull String readWide(@NotNull MemorySegment buffer, int capacity) {
        StringBuilder text = new StringBuilder(capacity);
        for (int index = 0; index < capacity; index++) {
            char value = buffer.get(ValueLayout.JAVA_CHAR_UNALIGNED, index * 2L);
            if (value == 0) {
                return text.toString();
            }
            text.append(value);
        }
        return text.toString();
    }

    static @NotNull TrashException.Reason reasonFor(int code) {
        return switch (code) {
            case 2, 3 -> TrashException.Reason.NOT_FOUND;
            case 5, 0x13, 0x78 -> TrashException.Reason.PERMISSION_DENIED;
            case 0x75, 0x8C -> TrashException.Reason.ABORTED;
            case 0x79, 0x81, 0xCE -> TrashException.Reason.PATH_TOO_LONG;
            case 0x87, 0x88, 0xDF -> TrashException.Reason.NO_TRASH;
            default -> TrashException.Reason.IO_ERROR;
        };
    }

    private static long offset(@NotNull String field) {
        return SH_FILE_OPERATION_STRUCT.byteOffset(PathElement.groupElement(field));
    }
}
