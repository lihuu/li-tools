package io.github.lihuu.dukit.app;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CliApplicationTest {
    @TempDir Path temporary;

    @Test
    void scanPrintsFullDirectoryTreeAsJsonByDefault() throws IOException {
        Path root = tree("plain");
        Result result = run("scan", root.toString());

        assertEquals(0, result.code(), result.err());
        assertTrue(result.out().trim().startsWith("{"), "JSON is the default: " + result.out());
        JsonDocument document = parse(result.out());
        assertEquals("dukit", document.metaTool);
        assertEquals(650, document.metaSize);
        assertEquals(4, document.metaFiles);
        assertEquals(3, document.metaDirectories);
        assertEquals(List.of("a", "b"), document.childNames);
        assertFalse(document.json.contains("\"top.txt\""), "files are not tree nodes: " + document.json);
    }

    @Test
    void scanJsonDepthShrinksThePayloadButKeepsFullAggregates() throws IOException {
        Path root = tree("jsondepth");
        Result full = run("scan", root.toString());
        Result shallow = run("scan", root.toString(), "--depth", "1");

        assertEquals(0, shallow.code(), shallow.err());
        assertTrue(shallow.out().length() < full.out().length(), "the cut shrinks the payload");
        JsonDocument document = parse(shallow.out());
        assertEquals(List.of("a", "b"), document.childNames);
        assertEquals(650, document.metaSize, "the total still covers the whole subtree");
        assertFalse(document.json.contains("\"sub\""), "second level is not emitted: " + document.json);
    }

    @Test
    void scanTextModeStillRendersAHumanTree() throws IOException {
        Path root = tree("text");
        Result result = run("scan", root.toString(), "--text");

        assertEquals(0, result.code(), result.err());
        // Compare against the resolved path: on Windows the temp directory may be handed to
        // the test as an 8.3 short name (C:\Users\RUNNER~1\...) while the report shows the
        // path toRealPath() expands it to.
        assertTrue(result.out().contains(root.toRealPath().toString()), "root path is shown: " + result.out());
        assertTrue(result.out().contains("650 B"), result.out());
        assertTrue(result.out().contains("a/"), result.out());
        assertFalse(result.out().contains("\"tree\""), "text mode must not emit JSON");
    }

    @Test
    void scanTextDepthCutsDisplayButNotAggregation() throws IOException {
        Path root = tree("depth");
        Result result = run("scan", root.toString(), "--text", "--depth", "1");

        assertEquals(0, result.code(), result.err());
        assertTrue(result.out().contains("a/"), result.out());
        assertFalse(result.out().contains("sub/"), "second level is hidden: " + result.out());
        assertTrue(result.out().contains("600 B"), "sizes below the cut are still aggregated");
    }

    @Test
    void excludeSkipsMatchingEntries() throws IOException {
        Path root = tree("exclude");
        Result result = run("scan", root.toString(), "--exclude", "sub");

        assertEquals(0, result.code(), result.err());
        assertFalse(result.out().contains("\"sub\""), "excluded directories are absent");
    }

    @Test
    void topDefaultsToDirectoriesRankedBySize() throws IOException {
        Path root = tree("top");
        Result result = run("top", root.toString());

        assertEquals(0, result.code(), result.err());
        String out = result.out();
        assertTrue(out.contains("a/") || out.contains("a"), out);
        assertTrue(out.indexOf("a") < out.indexOf("b"), "largest first: " + out);
        assertTrue(out.contains("650 B"), "total is reported: " + out);
    }

    @Test
    void topFilesRanksIndividualFiles() throws IOException {
        Path root = tree("topfiles");
        Result result = run("top", root.toString(), "--files", "--limit", "2");

        assertEquals(0, result.code(), result.err());
        String out = result.out();
        assertTrue(out.contains("big.bin"), out);
        assertTrue(out.contains("deep.txt"), out);
        assertFalse(out.contains("top.txt"), "limit 2 keeps only the biggest: " + out);
    }

    @Test
    void topJsonCarriesOnlyTheRequestedEntries() throws IOException {
        Path root = tree("topjson");
        Result result = run("top", root.toString(), "--json", "--limit", "2");

        assertEquals(0, result.code(), result.err());
        assertTrue(result.out().trim().startsWith("{"), result.out());
        assertTrue(result.out().contains("\"entries\""), result.out());
        assertTrue(result.out().contains("a"), result.out());
        // Two directories exist (a, a/sub) and limit is 2; b has size 0 and is dropped.
        assertEquals(2, countOccurrences(result.out(), "{\"path\""), result.out());
    }

    @Test
    void topMinSizeFiltersSmallEntries() throws IOException {
        Path root = tree("threshold");
        Result result = run("top", root.toString(), "--limit", "10", "--min-size", "500B");

        assertEquals(0, result.code(), result.err());
        assertTrue(result.out().contains("a"), result.out());
        assertFalse(result.out().contains("sub"), "a/sub is 200 B and below the 500 B floor: " + result.out());
    }

    @Test
    void scanAcceptsASymlinkedRootDirectory() throws IOException {
        Path root = tree("real");
        Path link = temporary.resolve("link");
        try {
            Files.createSymbolicLink(link, root);
        } catch (UnsupportedOperationException | IOException unsupported) {
            return;
        }

        Result result = run("scan", link.toString(), "--depth", "1");

        assertEquals(0, result.code(), "a symlinked root must still scan: " + result.err());
        assertTrue(result.out().contains("650"), result.out());
    }

    @Test
    void subcommandHelpWorks() {
        Result result = run("top", "--help");
        assertEquals(0, result.code(), result.err());
        assertTrue(result.out().contains("--limit"), result.out());
    }

    @Test
    void bareInvocationWithAPathReportsJsonWhenPiped() throws IOException {
        Path root = tree("barepath");
        Result result = run(root.toString());

        assertEquals(0, result.code(), result.err());
        assertTrue(result.out().trim().startsWith("{"), "a piped bare invocation reports JSON: " + result.out());
        JsonDocument document = parse(result.out());
        assertEquals(650, document.metaSize);
        assertEquals(List.of("a", "b"), document.childNames);
    }

    @Test
    void bareInvocationWithAMissingPathFails() {
        Result result = run(temporary.resolve("nope").toString());

        assertEquals(1, result.code());
        assertFalse(result.err().isBlank());
    }

    @Test
    void subcommandsStillWinOverTheRootPathArgument() throws IOException {
        Path root = tree("subwins");
        Result result = run("scan", root.toString(), "--text");

        assertEquals(0, result.code(), result.err());
        assertTrue(result.out().contains("a/"), "scan still runs as a subcommand: " + result.out());
        assertFalse(result.out().trim().startsWith("{"), "--text must not be read as a path");
    }

    @Test
    void missingPathIsAnExecutionFailure() {
        Result missing = run("scan", temporary.resolve("absent").toString());
        assertEquals(1, missing.code(), "a missing path is an execution failure, not a usage error");
        assertFalse(missing.err().isBlank());

        Result notADirectory = run("top", temporary.resolve("absent").toString());
        assertEquals(1, notADirectory.code());
    }

    @Test
    void pipedOutputContainsNoAnsiEscapes() throws IOException {
        Path root = tree("ansi");
        Result result = run("scan", root.toString(), "--text");
        assertEquals(0, result.code(), result.err());
        assertFalse(result.out().contains("\u001b["), "piped output must be plain");
        assertFalse(result.err().contains("\u001b["), "piped stderr must be plain");
    }

    @Test
    void bareInvocationPrintsHelpAndReturnsZero() {
        Result result = run();
        assertEquals(0, result.code());
        assertTrue(result.out().contains("Usage"), result.out());
        assertTrue(result.out().contains("top"), "top is discoverable: " + result.out());
    }

    @Test
    void versionFlagPrintsToolAndVersion() {
        Result result = run("--version");
        assertEquals(0, result.code(), result.err());
        assertTrue(result.out().startsWith("dukit "), result.out());
    }

    private Result run(String... args) {
        StringWriter outBuffer = new StringWriter();
        StringWriter errBuffer = new StringWriter();
        int code = CliApplication.run(args, temporary, new PrintWriter(outBuffer, true),
                new PrintWriter(errBuffer, true), Map.of(), false);
        return new Result(code, outBuffer.toString(), errBuffer.toString());
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

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            count++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return count;
    }

    private static JsonDocument parse(String json) throws IOException {
        JsonDocument document = new JsonDocument(json);
        try (JsonParser parser = new JsonFactory().createParser(json)) {
            parser.nextToken();
            assertEquals(JsonToken.START_OBJECT, parser.currentToken());
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.currentName();
                parser.nextToken();
                if ("meta".equals(field)) {
                    readMeta(parser, document);
                } else if ("tree".equals(field)) {
                    readTree(parser, document);
                } else {
                    parser.skipChildren();
                }
            }
        }
        return document;
    }

    private static void readMeta(JsonParser parser, JsonDocument document) throws IOException {
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String field = parser.currentName();
            parser.nextToken();
            switch (field) {
                case "tool" -> document.metaTool = parser.getValueAsString();
                case "size" -> document.metaSize = parser.getLongValue();
                case "files" -> document.metaFiles = parser.getLongValue();
                case "directories" -> document.metaDirectories = parser.getLongValue();
                default -> parser.skipChildren();
            }
        }
    }

    private static void readTree(JsonParser parser, JsonDocument document) throws IOException {
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String field = parser.currentName();
            parser.nextToken();
            if ("children".equals(field)) {
                assertEquals(JsonToken.START_ARRAY, parser.currentToken());
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    document.childNames.add(readNodeName(parser));
                }
            } else {
                parser.skipChildren();
            }
        }
    }

    private static String readNodeName(JsonParser parser) throws IOException {
        String name = null;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String field = parser.currentName();
            parser.nextToken();
            if ("name".equals(field)) {
                name = parser.getValueAsString();
            } else {
                parser.skipChildren();
            }
        }
        return name;
    }

    private static final class JsonDocument {
        final List<String> childNames = new ArrayList<>();
        final String json;
        String metaTool;
        long metaSize;
        long metaFiles;
        long metaDirectories;

        JsonDocument(String json) {
            this.json = json;
        }
    }

    private record Result(int code, String out, String err) {
    }
}
