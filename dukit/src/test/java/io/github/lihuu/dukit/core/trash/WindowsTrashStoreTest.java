package io.github.lihuu.dukit.core.trash;

import io.github.lihuu.dukit.core.delete.DeleteException;
import io.github.lihuu.dukit.core.delete.DeleteGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.MemoryLayout.PathElement;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Windows-only: layout and rejection tests run in the ordinary suite; the real Recycle Bin
 * test is opt-in because it leaves an entry in the developer's Recycle Bin.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsTrashStoreTest {
    private static final int MAX_PATH = 260;

    @TempDir Path temporary;
    private final WindowsTrashStore store = new WindowsTrashStore();

    @Test
    void structLayoutMatchesTheWin64Abi() {
        assertEquals(0, offset("hwnd"));
        assertEquals(8, offset("wFunc"));
        assertEquals(16, offset("pFrom"));
        assertEquals(24, offset("pTo"));
        assertEquals(32, offset("fFlags"));
        assertEquals(36, offset("fAnyOperationsAborted"));
        assertEquals(40, offset("hNameMappings"));
        assertEquals(48, offset("lpszProgressTitle"));
        assertEquals(56, WindowsTrashStore.SH_FILE_OPERATION_STRUCT.byteSize());
    }

    @Test
    void fromPathIsDoubleNullTerminatedUtf16() {
        String path = "C:\\tmp\\dukit";

        byte[] bytes = WindowsTrashStore.widePathBytes(path);
        byte[] text = path.getBytes(StandardCharsets.UTF_16LE);

        assertArrayEquals(text, Arrays.copyOf(bytes, text.length));
        assertArrayEquals(new byte[4], Arrays.copyOfRange(bytes, text.length, bytes.length),
                "SHFileOperationW requires a second NUL after the path");
    }

    @Test
    void refusesUncPaths() {
        TrashException failure = assertThrows(TrashException.class,
                () -> store.prepare(Path.of("\\\\server\\share\\directory")));
        assertEquals(TrashException.Reason.NETWORK_VOLUME, failure.reason());
        assertEquals(TrashException.Outcome.NOT_STARTED, failure.outcome());

        TrashException extended = assertThrows(TrashException.class,
                () -> store.prepare(Path.of("\\\\?\\UNC\\server\\share\\directory")));
        assertEquals(TrashException.Reason.NETWORK_VOLUME, extended.reason(),
                "Path strips \\\\?\\ and leaves a UNC path");
    }

    @Test
    void refusesPathsAtMaxPath() {
        Path longPath = Path.of(temporary.toAbsolutePath() + "\\" + "x".repeat(MAX_PATH));

        TrashException failure = assertThrows(TrashException.class, () -> store.prepare(longPath));
        assertEquals(TrashException.Reason.PATH_TOO_LONG, failure.reason());
    }

    @Test
    void refusesTheRecycleBinItself() {
        Path recycleBin = Path.of(System.getenv("SystemDrive") + "\\$Recycle.Bin\\dukit-probe");

        TrashException failure = assertThrows(TrashException.class, () -> store.prepare(recycleBin));
        assertEquals(TrashException.Reason.TARGET_IS_TRASH, failure.reason());
    }

    @Test
    void refusesAJunctionTarget() throws Exception {
        Path target = Files.createDirectory(temporary.resolve("target"));
        Path junction = temporary.resolve("junction");
        createJunction(junction, target);
        try {
            TrashException failure = assertThrows(TrashException.class, () -> store.prepare(junction));
            assertEquals(TrashException.Reason.REPARSE_POINT, failure.reason());
        } finally {
            Files.deleteIfExists(junction);
        }
    }

    @Test
    void refusesADirectoryContainingAJunction() throws Exception {
        Path target = Files.createDirectory(temporary.resolve("target"));
        Files.createDirectory(target.resolve("nested"));
        Path junction = target.resolve("junction");
        createJunction(junction, temporary);
        try {
            TrashException failure = assertThrows(TrashException.class, () -> store.prepare(target));
            assertEquals(TrashException.Reason.REPARSE_POINT, failure.reason());
        } finally {
            Files.deleteIfExists(junction);
        }
    }

    @Test
    void deleteGuardRefusesJunctionTargets() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root"));
        Path real = Files.createDirectory(root.resolve("real"));
        Path junction = root.resolve("junction");
        createJunction(junction, real);
        try {
            DeleteException refusal = assertThrows(DeleteException.class,
                    () -> DeleteGuard.validate(root, junction));
            assertEquals(DeleteException.Reason.REPARSE_POINT, refusal.reason());
        } finally {
            Files.deleteIfExists(junction);
        }
    }

    @Test
    void deleteGuardRefusesJunctionAncestors() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root"));
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.createDirectory(outside.resolve("nested"));
        Path junction = root.resolve("junction");
        createJunction(junction, outside);
        try {
            DeleteException refusal = assertThrows(DeleteException.class,
                    () -> DeleteGuard.validate(root, junction.resolve("nested")));
            assertEquals(DeleteException.Reason.REPARSE_POINT, refusal.reason());
        } finally {
            Files.deleteIfExists(junction);
        }
    }

    @Test
    void selectsTheWindowsStoreOnWindows() {
        TrashStore selected = TrashStores.forCurrentPlatform(
                Path.of(System.getProperty("user.home")), Map.of(), Clock.systemUTC());

        assertInstanceOf(WindowsTrashStore.class, selected);
    }

    @Test
    void mapsShellErrorCodes() {
        assertEquals(TrashException.Reason.NOT_FOUND, WindowsTrashStore.reasonFor(2));
        assertEquals(TrashException.Reason.NOT_FOUND, WindowsTrashStore.reasonFor(3));
        assertEquals(TrashException.Reason.PERMISSION_DENIED, WindowsTrashStore.reasonFor(5));
        assertEquals(TrashException.Reason.PERMISSION_DENIED, WindowsTrashStore.reasonFor(0x78));
        assertEquals(TrashException.Reason.ABORTED, WindowsTrashStore.reasonFor(0x75));
        assertEquals(TrashException.Reason.ABORTED, WindowsTrashStore.reasonFor(0x8C));
        assertEquals(TrashException.Reason.PATH_TOO_LONG, WindowsTrashStore.reasonFor(0x79));
        assertEquals(TrashException.Reason.PATH_TOO_LONG, WindowsTrashStore.reasonFor(0xCE));
        assertEquals(TrashException.Reason.NO_TRASH, WindowsTrashStore.reasonFor(0xDF));
        assertEquals(TrashException.Reason.IO_ERROR, WindowsTrashStore.reasonFor(0x1234));
    }

    @Test
    void movesADirectoryIntoTheRecycleBinWhenOptedIn() throws Exception {
        assumeTrue("1".equals(System.getenv("DUKIT_WINDOWS_TRASH_TEST")),
                "set DUKIT_WINDOWS_TRASH_TEST=1 to exercise the real Recycle Bin");
        Path target = Files.createDirectory(temporary.resolve("dukit-recycle-" + System.nanoTime()));
        Files.writeString(target.resolve("payload.txt"), "dukit");

        TrashStore.TrashTarget prepared = store.prepare(target);
        assertNull(prepared.destination(), "the Recycle Bin chooses the final name");
        assertTrue(prepared.display().startsWith("Recycle Bin ("), prepared.display());

        store.move(prepared);

        assertFalse(Files.exists(target, LinkOption.NOFOLLOW_LINKS));
    }

    private static long offset(String field) {
        return WindowsTrashStore.SH_FILE_OPERATION_STRUCT.byteOffset(PathElement.groupElement(field));
    }

    private static void createJunction(Path link, Path target) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), () -> "mklink /J failed: " + output);
    }
}
