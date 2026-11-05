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
    private StringWriter output;

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
    void listsDirectoriesLargestFirst() throws IOException {
        int code = browser("q\n").run();

        assertEquals(0, code);
        String out = output.toString();
        assertTrue(out.contains(root.toString()), out);
        assertTrue(out.contains("alpha"), out);
        assertTrue(out.contains("beta"), out);
        assertTrue(out.indexOf("alpha") < out.indexOf("beta"), "largest first: " + out);
        assertFalse(out.contains("top.txt"), "files are not listed as entries");
    }

    @Test
    void bareNumberDescendsWithoutTheCdPrefix() throws IOException {
        int code = browser("1\nq\n").run();

        assertEquals(0, code);
        String out = output.toString();
        assertTrue(out.contains(root.resolve("alpha").toString()), "descended into alpha: " + out);
        assertTrue(out.contains("310 B"), "own files are summarised (300 + 10): " + out);
    }

    @Test
    void doubleDotReturnsToTheParent() throws IOException {
        int code = browser("1\n..\nq\n").run();

        assertEquals(0, code);
        String out = output.toString();
        int back = out.lastIndexOf(root.toString());
        assertTrue(back > out.indexOf("alpha/second"), "returned to the root listing: " + out);
    }

    @Test
    void cdAndUpStillWork() throws IOException {
        int code = browser("cd 2\nup\nq\n").run();

        assertEquals(0, code);
        assertTrue(output.toString().contains(root.resolve("beta").toString()));
    }

    @Test
    void sCyclesBetweenSortKeys() throws IOException {
        int code = browser("s\nq\n").run();

        assertEquals(0, code);
        String out = output.toString();
        assertTrue(out.contains("sort: name"), "s switches to name sort: " + out);
    }

    @Test
    void topReturnsToRootFromAnyDepth() throws IOException {
        int code = browser("1\ntop\nq\n").run();

        assertEquals(0, code);
        assertTrue(output.toString().contains("alpha"), output.toString());
    }

    @Test
    void unknownCommandAndBadIndexStayInPlace() throws IOException {
        int code = browser("fly to the moon\n99\nq\n").run();

        assertEquals(0, code);
        String out = output.toString();
        assertTrue(out.contains("unknown command"), out);
        assertTrue(out.contains("no entry 99"), out);
    }

    @Test
    void endOfInputEndsWithoutError() throws IOException {
        assertEquals(0, browser("").run());
        assertEquals(0, browser("1\n").run(), "EOF right after a command also ends cleanly");
    }

    @Test
    void helpListsTheGestures() throws IOException {
        int code = browser("?\nq\n").run();

        assertEquals(0, code);
        assertTrue(output.toString().contains("descend into entry"), output.toString());
    }

    private Browser browser(String input) throws IOException {
        output = new StringWriter();
        ScanResult result = new DirectoryScanner(List.of()).scan(root, (files, bytes, dir) -> { });
        return new Browser(result, new StringReader(input), new PrintWriter(output, true), OutputStyle.plain());
    }
}
