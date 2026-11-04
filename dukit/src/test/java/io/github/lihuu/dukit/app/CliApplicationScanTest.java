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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CliApplicationScanTest {
    private static final JsonFactory JSON = new JsonFactory();

    @TempDir Path temporary;

    @Test
    void scanPrintsTreeSortedBySizeAndReturnsZero() throws IOException {
        Path root = tree("plain");
        Result result = run("scan", root.toString());

        assertEquals(0, result.code(), result.err());
        assertTrue(result.out().contains("plain"), "root name is shown");
        assertTrue(result.out().contains("350"), "total size is shown");
        assertTrue(indexOf(result.out(), "a") < indexOf(result.out(), "top.txt"),
                "children are ordered by size descending: " + result.out());
        assertTrue(result.out().contains("3"), "file count is shown");
    }

    @Test
    void depthLimitsTreeLevelsWhileScanningEverything() throws IOException {
        Path root = tree("depth");
        Result result = run("scan", root.toString(), "--depth", "1");

        assertEquals(0, result.code(), result.err());
        assertTrue(result.out().contains("a/"), "first level is shown");
        assertFalse(result.out().contains("nested"), "second level is hidden: " + result.out());
        assertFalse(result.out().contains("deep.bin"), "second level is hidden: " + result.out());
        assertTrue(result.out().contains("300"), "sizes below the cut are still aggregated");
    }

    @Test
    void excludeSkipsMatchingEntries() throws IOException {
        Path root = tree("exclude");
        Result byName = run("scan", root.toString(), "--exclude", "big.bin");
        assertEquals(0, byName.code(), byName.err());
        assertFalse(byName.out().contains("big.bin"), "excluded files are not listed");
        assertTrue(byName.out().contains("250"), "remaining sizes are aggregated: " + byName.out());

        Result byRelative = run("scan", root.toString(), "--exclude", "a/big.bin");
        assertEquals(0, byRelative.code(), byRelative.err());
        assertFalse(byRelative.out().contains("big.bin"), "relative globs also apply");
        assertTrue(byRelative.out().contains("250"), "remaining sizes are aggregated: " + byRelative.out());
    }

    @Test
    void jsonOutputParsesAndCarriesTheTree() throws IOException {
        Path root = tree("json");
        Result result = run("scan", root.toString(), "--json");

        assertEquals(0, result.code(), result.err());
        ParsedJson parsed = parse(result.out());
        assertEquals("dukit", parsed.metaTool);
        assertEquals(350, parsed.sizeOf("json"));
        assertEquals(300, parsed.sizeOf("a"));
        assertEquals(List.of("a", "top.txt"), parsed.childNamesOf("json"));
    }

    @Test
    void scanRefusesMissingPathAndPlainFiles() throws IOException {
        Result missing = run("scan", temporary.resolve("absent").toString());
        assertEquals(1, missing.code(), "a missing path is an execution failure, not a usage error");
        assertFalse(missing.err().isBlank());

        Path file = temporary.resolve("file.txt");
        Files.writeString(file, "content");
        Result notADirectory = run("scan", file.toString());
        assertEquals(1, notADirectory.code());
        assertFalse(notADirectory.err().isBlank());
    }

    @Test
    void pipedOutputContainsNoAnsiEscapes() throws IOException {
        Path root = tree("ansi");
        Result result = run("scan", root.toString());
        assertEquals(0, result.code(), result.err());
        assertFalse(result.out().contains("\u001b["), "piped output must be plain");
        assertFalse(result.err().contains("\u001b["), "piped stderr must be plain");
    }

    @Test
    void bareInvocationPrintsHelpAndReturnsZero() {
        Result result = run();
        assertEquals(0, result.code());
        assertTrue(result.out().contains("Usage"), result.out());
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
        Files.createDirectories(root.resolve("a/nested"));
        Files.writeString(root.resolve("a/nested/deep.bin"), "x".repeat(200), StandardCharsets.US_ASCII);
        Files.writeString(root.resolve("a/big.bin"), "x".repeat(100), StandardCharsets.US_ASCII);
        Files.writeString(root.resolve("top.txt"), "x".repeat(50), StandardCharsets.US_ASCII);
        return root;
    }

    private static int indexOf(String haystack, String needle) {
        int at = haystack.indexOf(needle);
        assertTrue(at >= 0, needle + " missing from output: " + haystack);
        return at;
    }

    private static ParsedJson parse(String json) throws IOException {
        try (JsonParser parser = JSON.createParser(json)) {
            ParsedJson parsed = new ParsedJson();
            parser.nextToken();
            expect(parser, JsonToken.START_OBJECT);
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.currentName();
                parser.nextToken();
                if ("meta".equals(field)) {
                    expect(parser, JsonToken.START_OBJECT);
                    while (parser.nextToken() != JsonToken.END_OBJECT) {
                        String metaField = parser.currentName();
                        parser.nextToken();
                        if ("tool".equals(metaField)) {
                            parsed.metaTool = parser.getValueAsString();
                        } else {
                            parser.skipChildren();
                        }
                    }
                } else if ("tree".equals(field)) {
                    readNode(parser, parsed);
                } else {
                    parser.skipChildren();
                }
            }
            return parsed;
        }
    }

    private static void readNode(JsonParser parser, ParsedJson parsed) throws IOException {
        expect(parser, JsonToken.START_OBJECT);
        String name = null;
        List<String> childNames = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String field = parser.currentName();
            parser.nextToken();
            switch (field) {
                case "name" -> name = parser.getValueAsString();
                case "size" -> {
                    assertNotNull(name, "name precedes size in dukit JSON output");
                    parsed.sizes.put(name, parser.getLongValue());
                }
                case "children" -> {
                    expect(parser, JsonToken.START_ARRAY);
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        readNode(parser, parsed);
                        childNames.add(parsed.lastName);
                    }
                }
                default -> parser.skipChildren();
            }
        }
        assertNotNull(name, "every tree node carries a name");
        parsed.children.put(name, childNames);
        parsed.lastName = name;
    }

    private static void expect(JsonParser parser, JsonToken expected) throws IOException {
        assertEquals(expected, parser.currentToken());
    }

    private static final class ParsedJson {
        final Map<String, Long> sizes = new HashMap<>();
        final Map<String, List<String>> children = new HashMap<>();
        String metaTool;
        String lastName;

        long sizeOf(String name) {
            Long size = sizes.get(name);
            assertNotNull(size, "no size recorded for " + name);
            return size;
        }

        List<String> childNamesOf(String name) {
            List<String> names = children.get(name);
            assertNotNull(names, "no children recorded for " + name);
            return names;
        }
    }

    private record Result(int code, String out, String err) {
    }
}
