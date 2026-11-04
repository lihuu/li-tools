package io.github.lihuu.dukit.core.browse;

import io.github.lihuu.clicommon.style.OutputStyle;
import io.github.lihuu.dukit.core.model.ScanResult;
import io.github.lihuu.dukit.core.scan.DirectoryScanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BrowserTest {
    @TempDir Path temporary;

    private Path root;

    @BeforeEach
    void buildTree() throws IOException {
        root = temporary.resolve("tree");
        Files.createDirectories(root.resolve("alpha"));
        Files.createDirectories(root.resolve("beta"));
        Files.writeString(root.resolve("alpha/content.bin"), "x".repeat(300), StandardCharsets.US_ASCII);
        Files.writeString(root.resolve("alpha/second.txt"), "x".repeat(10), StandardCharsets.US_ASCII);
        Files.writeString(root.resolve("beta/note.txt"), "x".repeat(100), StandardCharsets.US_ASCII);
        Files.writeString(root.resolve("top.txt"), "x".repeat(50), StandardCharsets.US_ASCII);
    }

    @Test
    void rendersCurrentDirectorySortedBySizeAndQuitsOnQ() throws IOException {
        Browser browser = browser("q\n");
        int code = browser.run();

        assertEquals(0, code);
        String out = this.out.toString();
        assertTrue(out.contains("tree"), "root is shown: " + out);
        assertTrue(out.contains("alpha"), out);
        assertTrue(out.contains("beta"), out);
        assertTrue(indexOf(out, "alpha") < indexOf(out, "beta"), "sorted by size descending: " + out);
    }

    @Test
    void cdEntersChildDirectoryAndUpReturns() throws IOException {
        int code = browser("cd 1\nup\nq\n").run();

        assertEquals(0, code);
        String out = this.out.toString();
        assertTrue(out.contains("content.bin"), "cd 1 shows alpha's children: " + out);
        assertTrue(out.contains("tree"), "up returns to the root listing: " + out);
    }

    @Test
    void topReturnsToRootFromAnyDepth() throws IOException {
        int code = browser("cd 1\ncd 1\ntop\nq\n").run();

        assertEquals(0, code);
        assertTrue(out.toString().contains("alpha"), "top re-lists the root: " + out);
    }

    @Test
    void sortNameReordersTheListing() throws IOException {
        int code = browser("sort name\nq\n").run();

        assertEquals(0, code);
        String out = this.out.toString();
        assertTrue(indexOf(out, "alpha") < indexOf(out, "beta"), "name sort is ascending: " + out);
    }

    @Test
    void sortCountReordersByFileCount() throws IOException {
        int code = browser("sort count\nq\n").run();

        assertEquals(0, code);
        String out = this.out.toString();
        assertTrue(indexOf(out, "alpha") < indexOf(out, "beta"),
                "alpha holds two files, beta one: " + out);
    }

    @Test
    void unknownCommandAndFileTargetsStayInPlace() throws IOException {
        int code = browser("fly to the moon\ncd 3\nq\n").run();

        assertEquals(0, code);
        String out = this.out.toString();
        assertTrue(out.contains("unknown command"), out);
        assertTrue(out.contains("not a directory"), "cd onto a file explains itself: " + out);
    }

    @Test
    void endOfInputEndsWithoutError() throws IOException {
        assertEquals(0, browser("").run());
        assertEquals(0, browser("cd 1\n").run(), "EOF right after a command also ends cleanly");
    }

    @Test
    void helpListsTheCommands() throws IOException {
        int code = browser("?\nq\n").run();

        assertEquals(0, code);
        assertTrue(out.toString().contains("cd <n>"), out.toString());
    }

    private StringWriter out;

    private Browser browser(String input) {
        out = new StringWriter();
        try {
            ScanResult result = new DirectoryScanner(List.of()).scan(root, (files, bytes, dir) -> { });
            return new Browser(result, new StringReader(input), new PrintWriter(out, true), OutputStyle.plain());
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static int indexOf(String haystack, String needle) {
        int at = haystack.indexOf(needle);
        assertTrue(at >= 0, needle + " missing from output: " + haystack);
        return at;
    }
}
