package io.github.lihuu.dukit.core.scan;

import io.github.lihuu.dukit.core.model.DirEntry;
import io.github.lihuu.dukit.core.model.ScanResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DirectoryScannerTest {
    @TempDir Path temporary;

    @Test
    void aggregatesSizesAndCountsForAKnownTree() throws IOException {
        Path root = tree("plain");
        ScanResult result = new DirectoryScanner(List.of()).scan(root, (files, bytes, dir) -> { });

        DirEntry entry = result.root();
        assertEquals(450, entry.size());
        assertEquals(3, entry.fileCount());
        assertEquals(2, entry.dirCount());
        assertTrue(result.errors().isEmpty());

        DirEntry a = child(entry, "a");
        assertEquals(400, a.size());
        assertEquals(2, a.fileCount());
        assertEquals(0, a.dirCount());

        DirEntry b = child(entry, "b");
        assertEquals(0, b.size());
        assertEquals(0, b.fileCount());

        DirEntry topFile = child(entry, "top.txt");
        assertEquals(50, topFile.size());
        assertEquals(0, topFile.dirCount());
    }

    @Test
    void sortsChildrenBySizeDescendingThenName() throws IOException {
        Path root = tree("sorted");
        ScanResult result = new DirectoryScanner(List.of()).scan(root, (files, bytes, dir) -> { });

        List<DirEntry> children = result.root().children();
        assertEquals(List.of("a", "top.txt", "b"), children.stream().map(DirEntry::name).toList());
    }

    @Test
    void skipsSymlinksWithoutFollowingThem() throws IOException {
        Path root = tree("links");
        Path outside = temporary.resolve("outside.txt");
        Files.writeString(outside, "outside content");
        Files.createSymbolicLink(root.resolve("link.txt"), outside);

        ScanResult result = new DirectoryScanner(List.of()).scan(root, (files, bytes, dir) -> { });

        assertEquals(450, result.root().size(), "symlink targets must not be counted");
        assertEquals(3, result.root().fileCount(), "symlinks are not regular files");
    }

    @Test
    void countsHardlinkedContentOncePerScan() throws IOException {
        Path root = tree("hardlinks");
        Path original = root.resolve("a/big.bin");
        Files.createLink(root.resolve("b/twin.bin"), original);

        ScanResult result = new DirectoryScanner(List.of()).scan(root, (files, bytes, dir) -> { });

        assertEquals(4, result.root().fileCount(), "every hardlink is a file");
        assertEquals(450, result.root().size(), "hardlinked content counts once");
    }

    @Test
    void excludesByBareNameAndByRelativeGlob() throws IOException {
        Path root = tree("excluded");
        ScanResult byName = new DirectoryScanner(List.of("b")).scan(root, (files, bytes, dir) -> { });
        assertTrue(childNames(byName.root()).noneMatch("b"::equals));
        assertEquals(1, byName.root().dirCount());
        assertEquals(450, byName.root().size(), "b is an empty directory, sizes are unchanged");

        ScanResult byGlob = new DirectoryScanner(List.of("a/small.txt")).scan(root, (files, bytes, dir) -> { });
        DirEntry a = child(byGlob.root(), "a");
        assertEquals(1, a.fileCount());
        assertEquals(300, a.size());
        assertEquals(350, byGlob.root().size());
    }

    @Test
    void recordsUnreadableDirectoriesAndKeepsScanning() throws IOException {
        Path root = tree("locked");
        Path locked = root.resolve("b");
        boolean posix = true;
        try {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        } catch (UnsupportedOperationException unsupported) {
            posix = false;
        }
        assumeTrue(posix, "POSIX permissions unsupported on this platform");

        try {
            ScanResult result = new DirectoryScanner(List.of()).scan(root, (files, bytes, dir) -> { });
            assertEquals(1, result.errors().size());
            assertEquals(locked, result.errors().get(0).path());
            assertFalse(result.errors().get(0).message().isBlank());
            assertEquals(1, result.root().errorCount());
            assertEquals(450, result.root().size(), "the readable part is still aggregated");
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxrwxrwx"));
        }
    }

    @Test
    void reportsProgressWhileScanning() throws IOException {
        Path root = tree("progress");
        List<Path> seen = new java.util.ArrayList<>();
        new DirectoryScanner(List.of()).scan(root, (files, bytes, dir) -> seen.add(dir));
        assertFalse(seen.isEmpty(), "the listener must observe progress updates");
        assertEquals(root, seen.get(seen.size() - 1), "the last update covers the scan root");
    }

    private Path tree(String name) throws IOException {
        Path root = temporary.resolve(name);
        Files.createDirectories(root.resolve("a"));
        Files.createDirectories(root.resolve("b"));
        Files.writeString(root.resolve("a/big.bin"), "x".repeat(300), StandardCharsets.US_ASCII);
        Files.writeString(root.resolve("a/small.txt"), "x".repeat(100), StandardCharsets.US_ASCII);
        Files.writeString(root.resolve("top.txt"), "x".repeat(50), StandardCharsets.US_ASCII);
        return root;
    }

    private static DirEntry child(DirEntry entry, String name) {
        return entry.children().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    private static java.util.stream.Stream<String> childNames(DirEntry entry) {
        return entry.children().stream().map(DirEntry::name);
    }
}
