package io.github.lihuu.dukit.core.delete;

import io.github.lihuu.dukit.core.model.DirEntry;
import io.github.lihuu.dukit.core.model.ScanResult;
import io.github.lihuu.dukit.core.scan.DirectoryScanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TreeUpdaterTest {
    @TempDir Path temporary;

    private ScanResult scan;

    @BeforeEach
    void buildTree() throws IOException {
        Path root = temporary.resolve("tree");
        Files.createDirectories(root.resolve("alpha/nested"));
        Files.createDirectories(root.resolve("beta"));
        Files.writeString(root.resolve("alpha/big.bin"), "x".repeat(300), StandardCharsets.US_ASCII);
        Files.writeString(root.resolve("alpha/nested/deep.bin"), "x".repeat(200), StandardCharsets.US_ASCII);
        Files.writeString(root.resolve("beta/mid.bin"), "x".repeat(100), StandardCharsets.US_ASCII);
        scan = new DirectoryScanner(List.of()).scan(root, (files, bytes, dir) -> { });
    }

    @Test
    void removingALeafSubtractsExactlyItsAggregates() throws IOException, DeleteException {
        Path alpha = scan.path().resolve("alpha");
        DirEntry before = child(scan.root(), "alpha");

        ScanResult updated = TreeUpdater.remove(scan, alpha.resolve("nested"));

        DirEntry after = child(updated.root(), "alpha");
        assertEquals(before.size() - 200, after.size());
        assertEquals(before.fileCount() - 1, after.fileCount());
        assertEquals(before.dirCount() - 1, after.dirCount());
        assertEquals(0, after.children().size());
        assertEquals(scan.root().size() - 200, updated.root().size());
    }

    @Test
    void removingAnIntermediateNodeRemovesItsWholeSubtree() throws DeleteException {
        Path alpha = scan.path().resolve("alpha");
        DirEntry before = child(scan.root(), "alpha");

        ScanResult updated = TreeUpdater.remove(scan, alpha);

        assertEquals(1, updated.root().children().size());
        assertEquals("beta", updated.root().children().get(0).name());
        assertEquals(scan.root().size() - before.size(), updated.root().size());
        assertEquals(scan.root().fileCount() - before.fileCount(), updated.root().fileCount());
        assertEquals(scan.root().dirCount() - 1 - before.dirCount(), updated.root().dirCount());
    }

    @Test
    void untouchedSubtreesAreReusedRatherThanRebuilt() throws DeleteException {
        Path alpha = scan.path().resolve("alpha");
        DirEntry betaBefore = child(scan.root(), "beta");

        ScanResult updated = TreeUpdater.remove(scan, alpha);

        DirEntry betaAfter = child(updated.root(), "beta");
        assertSame(betaBefore, betaAfter, "an unaffected subtree should keep its identity");
    }

    @Test
    void refusesTheRootAndPathsOutsideTheTree() {
        assertThrows(DeleteException.class, () -> TreeUpdater.remove(scan, scan.path()));
        assertThrows(DeleteException.class, () -> TreeUpdater.remove(scan, scan.path().resolveSibling("elsewhere")));
    }

    @Test
    void refusesAPathThatIsNotANode() {
        assertThrows(DeleteException.class, () -> TreeUpdater.remove(scan, scan.path().resolve("missing")));
    }

    private static DirEntry child(DirEntry entry, String name) {
        return entry.children().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }
}
