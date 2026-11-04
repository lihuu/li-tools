package io.github.lihuu.dukit.core.scan;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProgressPrinterTest {
    @Test
    void enabledPrinterRewritesOneLineWithCountsAndBytes() {
        StringWriter buffer = new StringWriter();
        ProgressPrinter progress = new ProgressPrinter(new PrintWriter(buffer, true), true);

        progress.onUpdate(1024, 4096, Path.of("/some/dir"));
        progress.finish();

        String printed = buffer.toString();
        assertTrue(printed.contains("1024 files"), printed);
        assertTrue(printed.contains("4.0 KiB"), printed);
        assertTrue(printed.contains("/some/dir"), printed);
        assertTrue(printed.startsWith("\r"), "updates rewrite the line in place");
        assertTrue(printed.endsWith("\r"), "finish ends by clearing the line");
    }

    @Test
    void disabledPrinterPrintsNothing() {
        StringWriter buffer = new StringWriter();
        ProgressPrinter progress = new ProgressPrinter(new PrintWriter(buffer, true), false);

        progress.onUpdate(1024, 4096, Path.of("/some/dir"));
        progress.finish();

        assertEquals("", buffer.toString(), "a disabled printer must stay silent");
    }

    @Test
    void finishWithoutUpdatePrintsNothing() {
        StringWriter buffer = new StringWriter();
        new ProgressPrinter(new PrintWriter(buffer, true), true).finish();

        assertEquals("", buffer.toString());
    }

    @Test
    void laterUpdatesEraseEarlierLongerLines() {
        StringWriter buffer = new StringWriter();
        ProgressPrinter progress = new ProgressPrinter(new PrintWriter(buffer, true), true);

        progress.onUpdate(1024, 4096, Path.of("/a/very/long/directory/name/that/takes/space/aaaaaaaaaaaaaaaaaaaa"));
        progress.onUpdate(2048, 8192, Path.of("/short"));
        progress.finish();

        String printed = buffer.toString();
        int shortUpdate = printed.indexOf("/short");
        assertTrue(shortUpdate >= 0, printed);
        String tail = printed.substring(shortUpdate, printed.indexOf("\r", shortUpdate));
        assertTrue(tail.endsWith("  "), "padding erases the remains of the previous line: " + tail);
    }
}
