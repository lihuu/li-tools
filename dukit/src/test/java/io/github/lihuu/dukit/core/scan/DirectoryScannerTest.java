package io.github.lihuu.dukit.core.scan;

import io.github.lihuu.dukit.core.model.DirEntry;
import io.github.lihuu.dukit.core.model.FileEntry;
import io.github.lihuu.dukit.core.model.ScanResult;
import io.github.lihuu.dukit.core.model.TopEntry;
import io.github.lihuu.dukit.core.model.TopResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DirectoryScannerTest {
    @TempDir Path temporary;

    @Test
    void treeContainsDirectoriesOnlyAndFoldsFilesIntoTheirParent() throws IOException {
        Path root = tree("plain");
        ScanResult result = new DirectoryScanner(List.of()).scan(root, (files, bytes, dir) -> { });

        DirEntry entry = result.root();
        assertEquals(650, entry.size());
        assertEquals(4, entry.fileCount(), "all files in the subtree are counted");
        assertEquals(3, entry.dirCount(), "a, b and a/sub");
        assertEquals(2, entry.children().size(), "only directories become children: " + names(entry));
        assertTrue(result.errors().isEmpty());

        DirEntry a = child(entry, "a");
        assertEquals(600, a.size());
        assertEquals(3, a.fileCount());
        assertEquals(1, a.dirCount());
        assertEquals(400, a.ownFileSize(), "files directly inside a");
        assertEquals(2, a.ownFileCount());

        DirEntry sub = child(a, "sub");
        assertEquals(200, sub.size());
        assertEquals(1, sub.fileCount());
        assertEquals(0, sub.dirCount());
        assertEquals(0, sub.children().size());
        assertEquals(200, sub.ownFileSize());
    }

    @Test
    void sortsChildrenBySizeDescendingThenName() throws IOException {
        Path root = tree("sorted");
        ScanResult result = new DirectoryScanner(List.of()).scan(root, (files, bytes, dir) -> { });

        assertEquals(List.of("a", "b"), names(result.root()));
    }

    @Test
    void skipsSymlinksWithoutFollowingThem() throws IOException {
        Path root = tree("links");
        Path outside = temporary.resolve("outside.txt");
        Files.writeString(outside, "outside content");
        Files.createSymbolicLink(root.resolve("link.txt"), outside);
        Files.createSymbolicLink(root.resolve("linkdir"), root.resolve("a"));

        ScanResult result = new DirectoryScanner(List.of()).scan(root, (files, bytes, dir) -> { });

        assertEquals(650, result.root().size(), "symlink targets must not be counted");
        assertEquals(4, result.root().fileCount());
        assertEquals(3, result.root().dirCount(), "a symlinked directory is not traversed");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void skipsWindowsJunctionsWithoutFollowingThem() throws IOException, InterruptedException {
        Path outside = Files.createDirectories(temporary.resolve("junction-outside"));
        Files.writeString(outside.resolve("outside.bin"), "x".repeat(4096), StandardCharsets.US_ASCII);
        Path root = Files.createDirectories(temporary.resolve("junction-root"));
        Files.writeString(root.resolve("inside.bin"), "x".repeat(64), StandardCharsets.US_ASCII);
        Path junction = root.resolve("junction");

        Process mklink = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                junction.toString(), outside.toString()).redirectErrorStream(true).start();
        String output = new String(mklink.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
        assumeTrue(mklink.waitFor() == 0, () -> "cannot create a junction: " + output);
        assertTrue(Files.isDirectory(junction, LinkOption.NOFOLLOW_LINKS),
                "a junction looks like a directory to NIO, which is what made it traversable");

        try {
            ScanResult result = new DirectoryScanner(List.of()).scan(root, (files, bytes, dir) -> { });

            assertEquals(64, result.root().size(), "content behind the junction is outside the root");
            assertEquals(1, result.root().fileCount(), "the external file is not part of the scan");
            assertEquals(0, result.root().dirCount());
            assertTrue(result.root().children().isEmpty(), "the junction must not become a tree node");
        } finally {
            Files.deleteIfExists(junction);
        }
    }

    @Test
    void hardlinkedContentFollowsThePlatformsFileKeySupport() throws IOException {
        Path root = tree("hardlinks");
        Path original = root.resolve("a/big.bin");
        Files.createLink(root.resolve("a/twin.bin"), original);

        ScanResult result = new DirectoryScanner(List.of()).scan(root, (files, bytes, dir) -> { });

        assertEquals(5, result.root().fileCount(), "every hardlink is a file");

        // Windows exposes no file key through java.nio.file (verified on JDK 25: it is null
        // and the unix: view is unsupported), so two links to one inode are indistinguishable
        // from two files with equal content. Assert that reality rather than skipping the
        // case, so the platform difference stays visible.
        boolean fileKeysAvailable = fileKeysAvailable(original);
        if (fileKeysAvailable) {
            assertEquals(650, result.root().size(), "hardlinked content counts once");
        } else {
            assertEquals(950, result.root().size(),
                    "without file keys the same content counts once per link");
        }
    }

    @Test
    void countsManyHardlinksToTheSameInodeOnce() throws IOException {
        Path root = temporary.resolve("manylinks");
        Files.createDirectories(root);
        Path original = root.resolve("original.bin");
        Files.writeString(original, "x".repeat(100), StandardCharsets.US_ASCII);
        for (int index = 0; index < 40; index++) {
            Files.createLink(root.resolve("link" + index + ".bin"), original);
        }

        ScanResult result = new DirectoryScanner(List.of()).scan(root, (files, bytes, dir) -> { });

        assertEquals(41, result.root().fileCount(), "every hardlink is a file");
        if (fileKeysAvailable(original)) {
            assertEquals(100, result.root().size(), "shared content counts once");
        } else {
            assertEquals(4100, result.root().size(), "without file keys the content counts per link");
        }
    }

    private static boolean fileKeysAvailable(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).fileKey() != null;
    }

    @Test
    void excludesByBareNameAndByRelativeGlob() throws IOException {
        Path root = tree("excluded");
        ScanResult byName = new DirectoryScanner(List.of("sub")).scan(root, (files, bytes, dir) -> { });
        assertEquals(List.of("a", "b"), names(byName.root()));
        assertEquals(450, byName.root().size());
        assertEquals(2, byName.root().dirCount(), "a and b; sub is excluded");

        ScanResult byGlob = new DirectoryScanner(List.of("a/small.txt")).scan(root, (files, bytes, dir) -> { });
        assertEquals(550, byGlob.root().size());
        assertEquals(300, child(byGlob.root(), "a").ownFileSize());
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
            assertEquals(1, result.root().errorCount());
            assertEquals(650, result.root().size(), "the readable part is still aggregated");
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxrwxrwx"));
        }
    }

    @Test
    void parallelResultsMatchTheSerialWalk() throws IOException {
        Path root = wideTree("parallel", 6, 5);
        ScanResult serial = new DirectoryScanner(List.of(), 1).scan(root, (files, bytes, dir) -> { });
        ScanResult parallel = new DirectoryScanner(List.of(), 8).scan(root, (files, bytes, dir) -> { });

        assertEquals(shape(serial.root()), shape(parallel.root()),
                "a parallel walk must produce the same tree as a serial one");
        assertEquals(serial.errors().size(), parallel.errors().size());

        TopResult serialTop = new DirectoryScanner(List.of(), 1).top(root, 10, (files, bytes, dir) -> { });
        TopResult parallelTop = new DirectoryScanner(List.of(), 8).top(root, 10, (files, bytes, dir) -> { });
        assertEquals(serialTop.totalSize(), parallelTop.totalSize());
        assertEquals(serialTop.fileCount(), parallelTop.fileCount());
        assertEquals(serialTop.largestDirectories().stream().map(TopEntry::path).toList(),
                parallelTop.largestDirectories().stream().map(TopEntry::path).toList());
        assertEquals(serialTop.largestFiles().stream().map(TopEntry::path).toList(),
                parallelTop.largestFiles().stream().map(TopEntry::path).toList());
    }

    private static String shape(DirEntry entry) {
        StringBuilder builder = new StringBuilder();
        appendShape(entry, 0, builder);
        return builder.toString();
    }

    private static void appendShape(DirEntry entry, int level, StringBuilder builder) {
        builder.append("  ".repeat(level)).append(entry.name()).append(' ')
                .append(entry.size()).append(' ').append(entry.fileCount()).append(' ')
                .append(entry.dirCount()).append(' ').append(entry.ownFileSize()).append(' ')
                .append(entry.ownFileCount()).append('\n');
        for (DirEntry child : entry.children()) {
            appendShape(child, level + 1, builder);
        }
    }

    /**
     * Builds a tree wide and deep enough to cross the fork threshold at several levels.
     */
    private Path wideTree(String name, int breadth, int depth) throws IOException {
        Path root = temporary.resolve(name);
        buildLevel(root, breadth, depth, 0);
        return root;
    }

    private void buildLevel(Path dir, int breadth, int depth, int level) throws IOException {
        if (level >= depth) {
            return;
        }
        for (int index = 0; index < breadth; index++) {
            Path child = dir.resolve("d" + index);
            Files.createDirectories(child);
            Files.writeString(child.resolve("f" + index + ".bin"),
                    "x".repeat(10 + index), StandardCharsets.US_ASCII);
            buildLevel(child, breadth, depth, level + 1);
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

    @Test
    void topRanksLargestDirectoriesWithoutIncludingTheRoot() throws IOException {
        Path root = tree("topdirs");
        TopResult result = new DirectoryScanner(List.of()).top(root, 2, (files, bytes, dir) -> { });

        assertEquals(4, result.fileCount());
        assertTrue(result.totalSize() > 0, "total is the occupied size of the whole scan");
        List<Path> paths = result.largestDirectories().stream().map(TopEntry::path).toList();
        assertEquals(List.of(root.resolve("a"), root.resolve("a/sub")), paths,
                "the scan root is the query, not a result");
    }

    @Test
    void topRanksLargestFilesAndRespectsTheLimit() throws IOException {
        Path root = tree("topfiles");
        TopResult result = new DirectoryScanner(List.of()).top(root, 2, (files, bytes, dir) -> { });

        List<TopEntry> files = result.largestFiles();
        assertEquals(2, files.size(), "the heap is bounded by the limit");
        assertEquals(root.resolve("a/big.bin"), files.get(0).path());
        assertEquals(300, files.get(0).size(), "apparent size is still carried");
        if (NativeStatReader.forCurrentPlatform() != null) {
            assertTrue(files.get(0).diskSize() >= 4096, "occupied size is block-rounded");
        } else {
            // Windows exposes no block count, so occupied size falls back to apparent size.
            assertEquals(files.get(0).size(), files.get(0).diskSize());
        }
    }

    @Test
    void retainsABoundedFilePreviewPerDirectoryOnlyWhenAsked() throws IOException {
        Path root = tree("preview");
        ScanResult plain = new DirectoryScanner(List.of()).scan(root, (files, bytes, dir) -> { });
        assertTrue(plain.root().topFiles().isEmpty());
        assertTrue(child(plain.root(), "a").topFiles().isEmpty(), "an ordinary scan keeps no file rows");

        ScanResult bounded = new DirectoryScanner(List.of()).scan(root,
                (files, bytes, dir) -> { }, 1);
        assertEquals(List.of("top.txt"), fileNames(bounded.root()));
        DirEntry a = child(bounded.root(), "a");
        assertEquals(List.of("big.bin"), fileNames(a), "only the largest direct file survives");
        assertEquals(List.of("deep.txt"), fileNames(child(a, "sub")));
        assertTrue(child(bounded.root(), "b").topFiles().isEmpty(), "an empty directory keeps no preview");

        ScanResult wider = new DirectoryScanner(List.of()).scan(root,
                (files, bytes, dir) -> { }, 2);
        assertEquals(List.of("big.bin", "small.txt"), fileNames(child(wider.root(), "a")));
    }

    @Test
    void excludesApplyToTheFilePreviewToo() throws IOException {
        Path root = tree("previewexclude");
        ScanResult result = new DirectoryScanner(List.of("a/big.bin")).scan(root,
                (files, bytes, dir) -> { }, 5);

        assertEquals(List.of("small.txt"), fileNames(child(result.root(), "a")));
    }

    @Test
    void topKeepsOnlyTheLargestEntriesRegardlessOfVisitOrder() throws IOException {
        Path root = temporary.resolve("order");
        Files.createDirectories(root);
        // Sizes must differ by at least one block: rounding makes small files identical.
        for (int index = 1; index <= 40; index++) {
            Files.writeString(root.resolve("f" + index + ".bin"), "x".repeat(index * 4096), StandardCharsets.US_ASCII);
        }
        TopResult result = new DirectoryScanner(List.of()).top(root, 3, (files, bytes, dir) -> { });

        List<TopEntry> files = result.largestFiles();
        assertEquals(3, files.size());
        assertEquals(List.of(40L, 39L, 38L),
                files.stream().map(entry -> entry.size() / 4096).toList());
    }

    @Test
    void topRejectsAMissingRoot() {
        Path missing = temporary.resolve("absent");
        assertThrows(IOException.class, () -> new DirectoryScanner(List.of()).top(missing, 5, (files, bytes, dir) -> { }));
    }

    @Test
    void scanSkipsADirectoryWhoseListingStallsAndKeepsItsSiblings() throws IOException {
        Path realRoot = Files.createDirectories(temporary.resolve("stallskip"));
        // Sizes differ by at least one block so the children sort by occupied size.
        for (int index = 1; index <= 4; index++) {
            Path fine = realRoot.resolve("fine" + index);
            Files.createDirectories(fine);
            Files.writeString(fine.resolve("f.bin"), "x".repeat(index * 4096), StandardCharsets.US_ASCII);
        }
        Files.createDirectories(realRoot.resolve("cloud"));

        StallFileSystem stallSystem = StallFileSystemProvider.mount();
        Path stallRoot = stallSystem.getPath(realRoot.toString());
        StallFileSystemProvider.stallDirectory(stallRoot.resolve("cloud"));
        try {
            // A listing that blocks must not hold the scan hostage: after the per-directory
            // budget the directory is recorded as an error and its siblings are kept.
            ScanResult result = assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () ->
                    new DirectoryScanner(List.of(), 0, java.util.concurrent.TimeUnit.SECONDS.toNanos(1))
                            .scan(stallRoot, (files, bytes, dir) -> { }));

            assertEquals(1, result.errors().size());
            assertEquals(stallRoot.resolve("cloud"), result.errors().get(0).path());
            assertTrue(result.errors().get(0).message().contains("timed out"),
                    "the error names the timeout: " + result.errors().get(0).message());
            assertEquals(40960, result.root().size(), "the readable siblings are still aggregated");
            assertEquals(4, result.root().dirCount());
            assertEquals(1, result.root().errorCount());
            assertEquals(List.of("fine4", "fine3", "fine2", "fine1"), names(result.root()),
                    "the stalled directory is not part of the tree");
        } finally {
            StallFileSystemProvider.unstallAll();
        }
    }

    @Test
    void scanKeepsADirectoryThatIsSlowButNeverSilent() throws IOException {
        Path realRoot = Files.createDirectories(temporary.resolve("trickle"));
        for (int index = 1; index <= 4; index++) {
            Path fine = realRoot.resolve("fine" + index);
            Files.createDirectories(fine);
            Files.writeString(fine.resolve("f.bin"), "x".repeat(index * 4096), StandardCharsets.US_ASCII);
        }
        Path slow = Files.createDirectories(realRoot.resolve("slow"));
        for (int index = 0; index < 20; index++) {
            Files.writeString(slow.resolve("f" + index + ".bin"), "x".repeat(4096), StandardCharsets.US_ASCII);
        }

        StallFileSystem stallSystem = StallFileSystemProvider.mount();
        Path stallRoot = stallSystem.getPath(realRoot.toString());
        // 20 entries × 100 ms = 2 s of work, twice the budget, but never one silent 1 s:
        // a mount that keeps producing results must not be mistaken for a dead one.
        StallFileSystemProvider.trickleDirectory(stallRoot.resolve("slow"), 100);
        try {
            ScanResult result = assertTimeoutPreemptively(java.time.Duration.ofSeconds(20), () ->
                    new DirectoryScanner(List.of(), 0, java.util.concurrent.TimeUnit.SECONDS.toNanos(1))
                            .scan(stallRoot, (files, bytes, dir) -> { }));

            assertTrue(result.errors().isEmpty(), "a progressing listing is not a stall: " + result.errors());
            DirEntry slowEntry = child(result.root(), "slow");
            assertEquals(20, slowEntry.fileCount(), "every trickled file is counted");
            assertEquals(20L * 4096, slowEntry.size());
            assertEquals(0, result.root().errorCount());
        } finally {
            StallFileSystemProvider.unstallAll();
        }
    }

    @Test
    void scanAbortsWhenTheRootListingStalls() throws IOException {
        Path realRoot = Files.createDirectories(temporary.resolve("stallroot"));
        Files.writeString(realRoot.resolve("never-listed.txt"), "x", StandardCharsets.US_ASCII);

        StallFileSystem stallSystem = StallFileSystemProvider.mount();
        Path stallRoot = stallSystem.getPath(realRoot.toString());
        StallFileSystemProvider.stallDirectory(stallRoot);
        try {
            IOException failure = assertThrows(IOException.class, () ->
                    assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () ->
                            new DirectoryScanner(List.of(), 0, java.util.concurrent.TimeUnit.SECONDS.toNanos(1))
                                    .scan(stallRoot, (files, bytes, dir) -> { })));
            assertTrue(failure.getMessage().contains("timed out"),
                    "the abort names the timeout: " + failure.getMessage());
        } finally {
            StallFileSystemProvider.unstallAll();
        }
    }

    private Path tree(String name) throws IOException {
        Path root = temporary.resolve(name);
        Files.createDirectories(root.resolve("a/sub"));
        Files.createDirectories(root.resolve("b"));
        Files.writeString(root.resolve("a/big.bin"), "x".repeat(300), StandardCharsets.US_ASCII);
        Files.writeString(root.resolve("a/small.txt"), "x".repeat(100), StandardCharsets.US_ASCII);
        Files.writeString(root.resolve("a/sub/deep.txt"), "x".repeat(200), StandardCharsets.US_ASCII);
        Files.writeString(root.resolve("top.txt"), "x".repeat(50), StandardCharsets.US_ASCII);
        return root;
    }

    private static List<String> names(DirEntry entry) {
        return entry.children().stream().map(DirEntry::name).toList();
    }

    private static List<String> fileNames(DirEntry entry) {
        return entry.topFiles().stream().map(FileEntry::name).toList();
    }

    private static DirEntry child(DirEntry entry, String name) {
        return entry.children().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }
}
