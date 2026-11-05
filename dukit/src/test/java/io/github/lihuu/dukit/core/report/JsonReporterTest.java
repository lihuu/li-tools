package io.github.lihuu.dukit.core.report;

import io.github.lihuu.dukit.app.ApplicationInfo;
import io.github.lihuu.dukit.core.model.ScanResult;
import io.github.lihuu.dukit.core.scan.DirectoryScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonReporterTest {
    @TempDir Path temporary;

    @Test
    void omitsTheInteractiveFilePreviewFromJson() throws IOException {
        Path root = temporary.resolve("tree");
        Files.createDirectories(root.resolve("a"));
        Files.writeString(root.resolve("a/big.bin"), "x".repeat(300), StandardCharsets.US_ASCII);
        Files.writeString(root.resolve("top.txt"), "x".repeat(50), StandardCharsets.US_ASCII);
        ScanResult result = new DirectoryScanner(List.of()).scan(root, (files, bytes, dir) -> { }, 5);
        assertFalse(result.root().topFiles().isEmpty(), "the scan retained a preview before serialization");
        assertFalse(result.root().children().get(0).topFiles().isEmpty());

        StringWriter buffer = new StringWriter();
        new JsonReporter(new PrintWriter(buffer, true), ApplicationInfo.load(), -1).write(result);

        String json = buffer.toString();
        assertFalse(json.contains("big.bin"), "file nodes must not enter the JSON tree: " + json);
        assertFalse(json.contains("top.txt"), "file nodes must not enter the JSON tree: " + json);
        assertFalse(json.contains("topFiles"), "the preview is not part of the report contract: " + json);
        assertTrue(json.contains("\"ownFileCount\": 1"), "the aggregate counters remain: " + json);
    }
}
