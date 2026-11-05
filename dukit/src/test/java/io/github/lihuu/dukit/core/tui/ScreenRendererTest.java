package io.github.lihuu.dukit.core.tui;

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

class ScreenRendererTest {
    @TempDir Path temporary;

    private DirEntry root;
    private ScanResult scan;
    private TuiState state;
    private Path base;

    @BeforeEach
    void buildTree() throws IOException {
        base = temporary.resolve("tree");
        Files.createDirectories(base.resolve("alpha/nested"));
        Files.createDirectories(base.resolve("beta"));
        Files.writeString(base.resolve("alpha/big.bin"), "x".repeat(300), StandardCharsets.US_ASCII);
        Files.writeString(base.resolve("alpha/nested/deep.bin"), "x".repeat(200), StandardCharsets.US_ASCII);
        Files.writeString(base.resolve("beta/mid.bin"), "x".repeat(100), StandardCharsets.US_ASCII);
        scan = new DirectoryScanner(List.of()).scan(base, (files, bytes, dir) -> { });
        root = scan.root();
        state = new TuiState(scan);
    }

    @Test
    void tRevealsTheCurrentDirectoriesLargestFiles() throws IOException {
        TuiState files = stateWithFiles(5);
        files.handle(Key.ENTER, 6);

        String hidden = String.join("\n", new ScreenRenderer(false).render(files, 80, 10));
        assertFalse(hidden.contains("big.bin"), "files stay out of the listing until t: " + hidden);

        files.handle(Key.TOGGLE_FILES, 6);
        List<String> lines = new ScreenRenderer(false).render(files, 80, 10);

        assertTrue(lines.get(1).contains("1 files shown"), lines.get(1));
        assertTrue(lines.get(3).contains("nested/"), lines.get(3));
        assertTrue(lines.get(4).contains("big.bin"), lines.get(4));
        assertFalse(lines.get(4).contains("big.bin/"), "a file has no directory slash: " + lines.get(4));
        assertTrue(lines.get(9).contains("files(on)"), lines.get(9));
    }

    @Test
    void styledFileRowsStillFitTheWidth() throws IOException {
        TuiState files = stateWithFiles(5);
        files.handle(Key.ENTER, 6);
        files.handle(Key.TOGGLE_FILES, 6);

        List<String> lines = new ScreenRenderer(true).render(files, 40, 12);

        for (String line : lines) {
            String visible = line.replaceAll("\033\\[[0-9;]*m", "");
            assertTrue(visible.length() <= 40, "visible width exceeded: [" + visible + "]");
        }
        assertTrue(lines.get(4).contains("\033[90m"), "file rows are dimmed: " + lines.get(4));
    }

    private TuiState stateWithFiles(int limit) throws IOException {
        return new TuiState(new DirectoryScanner(List.of()).scan(base, (files, bytes, dir) -> { }, limit));
    }

    @Test
    void fillsExactlyTheRequestedHeight() {
        List<String> lines = new ScreenRenderer(false).render(state, 200, 10);

        assertEquals(10, lines.size(), "a frame must cover the whole screen");
        assertTrue(lines.get(0).contains(scan.path().toString()), lines.get(0));
        assertTrue(lines.get(1).contains("directories"), lines.get(1));
        assertTrue(lines.get(9).contains("quit"), "the footer is the last row: " + lines.get(9));
    }

    @Test
    void everyLineFitsTheWidth() {
        List<String> lines = new ScreenRenderer(false).render(state, 40, 12);

        for (String line : lines) {
            assertTrue(line.length() <= 40, "line exceeds width: [" + line + "]");
        }
    }

    @Test
    void stylingNeverCountsTowardTheWidth() {
        List<String> lines = new ScreenRenderer(true).render(state, 40, 12);

        for (String line : lines) {
            String visible = line.replaceAll("\033\\[[0-9;]*m", "");
            assertTrue(visible.length() <= 40, "visible width exceeded: [" + visible + "]");
        }
    }

    @Test
    void longNamesAreTruncatedWithAnEllipsis() throws IOException {
        Path base = temporary.resolve("long");
        Files.createDirectories(base.resolve("a-very-long-directory-name-that-will-not-fit-at-all"));
        ScanResult result = new DirectoryScanner(List.of()).scan(base, (files, bytes, dir) -> { });

        List<String> lines = new ScreenRenderer(false).render(new TuiState(result), 30, 8);

        String listing = lines.get(3);
        assertTrue(listing.contains("…"), "expected an ellipsis: " + listing);
        assertTrue(listing.length() <= 30, listing);
    }

    @Test
    void selectedRowIsMarkedAndHighlighted() {
        List<String> plain = new ScreenRenderer(false).render(state, 80, 10);
        assertTrue(plain.get(3).contains("▶"), "cursor marker on the first row: " + plain.get(3));
        assertFalse(plain.get(4).contains("▶"), plain.get(4));

        state.handle(Key.DOWN, 6);
        List<String> moved = new ScreenRenderer(false).render(state, 80, 10);
        assertFalse(moved.get(3).contains("▶"), moved.get(3));
        assertTrue(moved.get(4).contains("▶"), moved.get(4));
    }

    @Test
    void colorModeWrapsTheSelectionInReverseVideo() {
        List<String> lines = new ScreenRenderer(true).render(state, 60, 10);

        assertTrue(lines.get(3).contains("\033[7m"), "selection uses reverse video: " + lines.get(3));
        assertTrue(lines.get(3).contains("\033[0m"));
    }

    @Test
    void emptyDirectorySaysSo() throws IOException {
        Path base = temporary.resolve("empty");
        Files.createDirectories(base);
        ScanResult result = new DirectoryScanner(List.of()).scan(base, (files, bytes, dir) -> { });

        List<String> lines = new ScreenRenderer(false).render(new TuiState(result), 60, 8);

        assertTrue(lines.get(3).contains("no subdirectories"), lines.get(3));
    }

    @Test
    void sortKeyIsShownInTheFooter() {
        state.handle(Key.SORT, 6);
        List<String> lines = new ScreenRenderer(false).render(state, 80, 10);

        assertTrue(lines.get(9).contains("sort(name)"), lines.get(9));
    }

    @Test
    void positionCounterTracksTheSelection() {
        state.handle(Key.DOWN, 6);
        List<String> lines = new ScreenRenderer(false).render(state, 80, 10);

        assertTrue(lines.get(9).trim().endsWith("2/2"), lines.get(9));
    }
}
