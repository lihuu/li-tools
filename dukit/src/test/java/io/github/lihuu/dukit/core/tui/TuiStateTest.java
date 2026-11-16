package io.github.lihuu.dukit.core.tui;

import io.github.lihuu.dukit.core.browse.SortKey;
import io.github.lihuu.dukit.core.model.DirEntry;
import io.github.lihuu.dukit.core.model.ScanResult;
import io.github.lihuu.dukit.core.model.TreeEntry;
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

class TuiStateTest {
    @TempDir Path temporary;

    private DirEntry root;
    private ScanResult scan;
    private Path base;

    @BeforeEach
    void buildTree() throws IOException {
        base = temporary.resolve("tree");
        Files.createDirectories(base.resolve("alpha/nested"));
        Files.createDirectories(base.resolve("beta"));
        Files.createDirectories(base.resolve("gamma"));
        Files.writeString(base.resolve("alpha/big.bin"), "x".repeat(300), StandardCharsets.US_ASCII);
        Files.writeString(base.resolve("alpha/nested/deep.bin"), "x".repeat(200), StandardCharsets.US_ASCII);
        Files.writeString(base.resolve("beta/mid.bin"), "x".repeat(100), StandardCharsets.US_ASCII);
        scan = new DirectoryScanner(List.of()).scan(base, (files, bytes, dir) -> { });
        root = scan.root();
    }

    @Test
    void startsAtTheRootWithTheLargestEntrySelected() {
        TuiState state = new TuiState(scan);

        assertEquals(root, state.current());
        assertEquals(List.of("alpha", "beta", "gamma"), state.entries().stream().map(TreeEntry::name).toList());
        assertEquals(0, state.selected());
        assertEquals(SortKey.SIZE, state.sortKey());
    }

    @Test
    void arrowsMoveTheCursorAndStopAtTheEdges() {
        TuiState state = new TuiState(scan);

        state.handle(Key.DOWN, 10);
        assertEquals(1, state.selected());
        state.handle(Key.UP, 10);
        assertEquals(0, state.selected());
        state.handle(Key.UP, 10);
        assertEquals(0, state.selected(), "cannot move above the first entry");
        state.handle(Key.END, 10);
        assertEquals(2, state.selected());
        state.handle(Key.DOWN, 10);
        assertEquals(2, state.selected(), "cannot move below the last entry");
    }

    @Test
    void enterDescendsAndBackReturns() {
        TuiState state = new TuiState(scan);
        assertEquals(scan.path().toString(), state.currentPath());

        state.handle(Key.ENTER, 10);
        assertEquals("alpha", state.current().name());
        assertEquals(scan.path().resolve("alpha").toString(), state.currentPath());
        assertEquals(0, state.selected());

        state.handle(Key.BACK, 10);
        assertEquals(root, state.current());
        assertEquals(scan.path().toString(), state.currentPath());
        state.handle(Key.BACK, 10);
        assertEquals(root, state.current(), "back at the root is a no-op");
    }

    @Test
    void enterOnALeafStaysPut() {
        TuiState state = new TuiState(scan);
        state.handle(Key.ENTER, 10);
        state.handle(Key.ENTER, 10);
        assertEquals("nested", state.current().name());
        assertEquals(scan.path().resolve("alpha").resolve("nested").toString(), state.currentPath());

        state.handle(Key.ENTER, 10);
        assertEquals("nested", state.current().name(),
                "an empty directory is still enterable — that is how you learn it is empty");
    }

    @Test
    void sortCyclesAndKeepsTheCursorInBounds() {
        TuiState state = new TuiState(scan);
        state.handle(Key.END, 10);

        state.handle(Key.SORT, 10);
        assertEquals(SortKey.NAME, state.sortKey());
        assertTrue(state.selected() < state.entries().size());

        state.handle(Key.SORT, 10);
        assertEquals(SortKey.COUNT, state.sortKey());
        state.handle(Key.SORT, 10);
        assertEquals(SortKey.SIZE, state.sortKey());
    }

    @Test
    void scrollingFollowsTheCursor() {
        TuiState state = new TuiState(scan);

        state.handle(Key.END, 1);
        assertEquals(2, state.selected());
        assertEquals(2, state.scroll(), "a one-row viewport scrolls to the selection");

        state.handle(Key.HOME, 1);
        assertEquals(0, state.selected());
        assertEquals(0, state.scroll());
    }

    @Test
    void tTogglesTheCurrentDirectoriesLargestFiles() throws IOException {
        TuiState state = new TuiState(scanWithFiles(2));
        state.handle(Key.ENTER, 10);
        assertEquals("alpha", state.current().name());
        assertEquals(List.of("nested"), state.entries().stream().map(TreeEntry::name).toList());

        state.handle(Key.TOGGLE_FILES, 10);
        assertTrue(state.showFiles());
        assertEquals(List.of("nested", "big.bin"), state.entries().stream().map(TreeEntry::name).toList());
        assertEquals(300, state.entries().get(1).size(), "the largest file is shown at its real size");

        state.handle(Key.TOGGLE_FILES, 10);
        assertFalse(state.showFiles());
        assertEquals(List.of("nested"), state.entries().stream().map(TreeEntry::name).toList());
    }

    @Test
    void enterOnAFileDoesNotLeaveTheDirectory() throws IOException {
        TuiState state = new TuiState(scanWithFiles(2));
        state.handle(Key.ENTER, 10);
        state.handle(Key.TOGGLE_FILES, 10);
        state.handle(Key.DOWN, 10);
        assertEquals("big.bin", state.entries().get(state.selected()).name());

        state.handle(Key.ENTER, 10);

        assertEquals("alpha", state.current().name());
        assertEquals(scan.path().resolve("alpha").toString(), state.currentPath());
    }

    @Test
    void hidingFilesClampsTheCursorBackToTheDirectories() throws IOException {
        TuiState state = new TuiState(scanWithFiles(2));
        state.handle(Key.ENTER, 1);
        state.handle(Key.TOGGLE_FILES, 1);
        state.handle(Key.END, 1);
        assertEquals(1, state.selected(), "the file row is selectable");

        state.handle(Key.TOGGLE_FILES, 1);

        assertEquals(0, state.selected());
        assertEquals(1, state.entries().size());
    }


    @Test
    void aDirectoryWithoutSubdirectoriesListsItsFilesWithoutToggling() throws IOException {
        Path filesOnly = temporary.resolve("files-only");
        Files.createDirectories(filesOnly);
        Files.writeString(filesOnly.resolve("small.bin"), "x".repeat(10), StandardCharsets.US_ASCII);
        Files.writeString(filesOnly.resolve("large.bin"), "x".repeat(300), StandardCharsets.US_ASCII);
        TuiState state = new TuiState(new DirectoryScanner(List.of()).scan(filesOnly, (files, bytes, dir) -> { }));

        assertFalse(state.showFiles(), "the toggle itself stays off");
        assertTrue(state.filesVisible(), "but there is nothing else to show");
        assertEquals(List.of("large.bin", "small.bin"),
                state.entries().stream().map(TreeEntry::name).toList());
    }

    @Test
    void filesAreListedFromTheDirectoryItselfNotOnlyTheScannedPreview() throws IOException {
        Path wide = temporary.resolve("wide");
        Files.createDirectories(wide);
        for (int i = 1; i <= 5; i++) {
            Files.writeString(wide.resolve("f" + i + ".bin"), "x".repeat(i * 100), StandardCharsets.US_ASCII);
        }
        ScanResult result = new DirectoryScanner(List.of()).scan(wide, (files, bytes, dir) -> { }, 2);
        TuiState state = new TuiState(result);

        assertEquals(2, state.current().topFiles().size(), "the scan itself kept only a preview");
        assertEquals(List.of("f5.bin", "f4.bin", "f3.bin", "f2.bin", "f1.bin"),
                state.entries().stream().map(TreeEntry::name).toList());
        assertFalse(state.filesTruncated(), "five files fit in a listing of two thousand");
        assertEquals(5, state.filesTotal());
    }

    @Test
    void subdirectoriesHideTheirFilesUntilAsked() throws IOException {
        TuiState state = new TuiState(scan);
        state.handle(Key.ENTER, 10);
        assertEquals("alpha", state.current().name());

        assertFalse(state.filesVisible(), "alpha has a subdirectory, so files start hidden");
        assertEquals(List.of("nested"), state.entries().stream().map(TreeEntry::name).toList());
        assertTrue(state.files().isEmpty());
    }

    @Test
    void leavingAFileOnlyDirectoryHidesItsFilesAgain() throws IOException {
        Path mixed = temporary.resolve("mixed");
        Files.createDirectories(mixed.resolve("inner"));
        Files.createDirectories(mixed.resolve("files-only"));
        Files.writeString(mixed.resolve("files-only/only.bin"), "x".repeat(10), StandardCharsets.US_ASCII);
        TuiState state = new TuiState(new DirectoryScanner(List.of()).scan(mixed, (files, bytes, dir) -> { }));

        state.handle(Key.ENTER, 10);
        assertEquals("files-only", state.current().name());
        assertTrue(state.filesVisible());
        assertEquals(List.of("only.bin"), state.entries().stream().map(TreeEntry::name).toList());

        state.handle(Key.BACK, 10);
        assertFalse(state.filesVisible(), "the directory above still has subdirectories to show");
        assertTrue(state.entries().stream().allMatch(entry -> entry instanceof DirEntry));
    }

    @Test
    void quitStopsTheLoop() {
        TuiState state = new TuiState(scan);
        assertFalse(state.handle(Key.QUIT, 10));
        assertTrue(state.handle(Key.NONE, 10));
    }

    private ScanResult scanWithFiles(int limit) throws IOException {
        return new DirectoryScanner(List.of()).scan(base, (files, bytes, dir) -> { }, limit);
    }
}
