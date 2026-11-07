package io.github.lihuu.jsonkit.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliApplicationTest {

    @TempDir
    Path directory;

    private record Result(int code, String out, String err) {}

    private Result run(String stdin, boolean stdinInteractive, boolean stdoutInteractive, String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Map<String, String> environment = new HashMap<>();
        environment.put("TERM", "xterm-256color");
        int code = CliApplication.run(
                args,
                directory,
                new PrintWriter(out, true),
                new PrintWriter(err, true),
                environment,
                stdinInteractive,
                stdoutInteractive,
                new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
        return new Result(code, out.toString(), err.toString());
    }

    private Result runWithEnvironment(Map<String, String> environment, String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = CliApplication.run(
                args,
                directory,
                new PrintWriter(out, true),
                new PrintWriter(err, true),
                environment,
                false,
                false,
                new ByteArrayInputStream(new byte[0]));
        return new Result(code, out.toString(), err.toString());
    }

    private Path file(String name, String content) throws IOException {
        Path path = directory.resolve(name);
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    @Test
    void formatsAFileByDefault() throws Exception {
        file("response.json", "{\"user\":{\"name\":\"Alice\"}}");

        Result result = run("", false, false, "response.json");

        assertEquals(0, result.code(), result.err());
        assertEquals("{\n  \"user\": {\n    \"name\": \"Alice\"\n  }\n}\n", result.out());
        assertEquals("", result.err());
    }

    @Test
    void readsStdinWhenItIsPiped() {
        Result result = run("{\"a\":1}", false, false);

        assertEquals(0, result.code(), result.err());
        assertEquals("{\n  \"a\": 1\n}\n", result.out());
    }

    @Test
    void explicitDashReadsStdinEvenWhenItIsATerminal() {
        Result result = run("{\"a\":1}", true, false, "-");

        assertEquals(0, result.code(), result.err());
        assertEquals("{\n  \"a\": 1\n}\n", result.out());
    }

    @Test
    void bareInvocationInATerminalShowsHelpAndSucceeds() {
        Result result = run("", true, false);

        assertEquals(0, result.code(), result.err());
        assertTrue(result.out().contains("Usage: jsonkit"), result.out());
        assertEquals("", result.err());
    }

    @Test
    void pathWithoutAFileInATerminalIsAUsageError() {
        Result result = run("", true, false, "-p", "user.name");

        assertEquals(2, result.code());
        assertEquals("", result.out());
        assertTrue(result.err().contains("stdin") || result.err().contains("FILE"), result.err());
        assertFalse(result.err().contains("\u001b"), result.err());
    }

    @Test
    void pathWithoutAFileReadsPipedStdin() {
        Result result = run("{\"user\":{\"name\":\"Alice\"}}", false, false, "-p", "user.name");

        assertEquals(0, result.code(), result.err());
        assertEquals("\"Alice\"\n", result.out());
    }

    @Test
    void explicitDashWithAPathReadsStdinEvenInATerminal() {
        Result result = run("{\"a\":1}", true, false, "-p", "a", "-");

        assertEquals(0, result.code(), result.err());
        assertEquals("1\n", result.out());
    }

    @Test
    void pipedInputIsColoredWhenStdoutIsATerminal() {
        Result result = run("{\"a\":\"value\"}", false, true);

        assertEquals(0, result.code(), result.err());
        assertTrue(result.out().contains("\u001b[36;1m"), result.out());
        assertEquals("{\n  \"a\": \"value\"\n}\n", stripAnsi(result.out()));
    }

    @Test
    void acceptsThePathBeforeOrAfterTheFile() throws Exception {
        file("response.json", "{\"user\":{\"name\":\"Alice\"}}");

        Result before = run("", false, false, "-p", "user.name", "response.json");
        Result after = run("", false, false, "response.json", "-p", "user.name");

        assertEquals(0, before.code(), before.err());
        assertEquals(before.out(), after.out());
        assertEquals("\"Alice\"\n", before.out());
    }

    @Test
    void singleValuePathPrintsTheSelectedJsonValue() throws Exception {
        file("response.json", "{\"user\":{\"name\":\"Alice\"},\"ok\":true,\"nothing\":null}");

        assertEquals("\"Alice\"\n", run("", false, false, "-p", "user.name", "response.json").out());
        assertEquals("true\n", run("", false, false, "-p", "ok", "response.json").out());
        assertEquals("null\n", run("", false, false, "-p", "nothing", "response.json").out());
    }

    @Test
    void singleValuePathWithoutAMatchFails() throws Exception {
        file("response.json", "{\"user\":{\"name\":\"Alice\"}}");

        Result result = run("", false, false, "-p", "user.missing", "response.json");

        assertEquals(1, result.code());
        assertEquals("", result.out());
        assertTrue(result.err().contains("matched no value"), result.err());
    }

    @Test
    void multiValueQueryPrintsAnArray() throws Exception {
        file("response.json", "{\"items\":[{\"id\":1},{\"id\":2}]}");

        Result result = run("", false, false, "-p", "items[*].id", "response.json");

        assertEquals(0, result.code(), result.err());
        assertEquals("[\n  1,\n  2\n]\n", result.out());
    }

    @Test
    void multiValueQueryWithoutMatchesStillSucceedsWithAnEmptyArray() throws Exception {
        file("response.json", "{\"items\":[]}");

        Result result = run("", false, false, "-p", "items[*].id", "response.json");

        assertEquals(0, result.code(), result.err());
        assertEquals("[]\n", result.out());
    }

    @Test
    void multiValueQueryKeepsASingleNullInTheArray() throws Exception {
        file("response.json", "{\"items\":[null]}");

        Result result = run("", false, false, "-p", "items[*]", "response.json");

        assertEquals(0, result.code(), result.err());
        assertEquals("[\n  null\n]\n", result.out());
    }

    @Test
    void bigNumbersAndNullSurviveQueries() throws Exception {
        file("response.json", "{\"big\":123456789012345678901234567890,\"nothing\":null}");

        assertEquals("123456789012345678901234567890\n",
                run("", false, false, "-p", "big", "response.json").out());
        assertEquals("null\n", run("", false, false, "-p", "nothing", "response.json").out());
        Result whole = run("", false, false, "-p", "$", "response.json");
        assertEquals(0, whole.code(), whole.err());
        assertTrue(whole.out().contains("\"big\": 123456789012345678901234567890"), whole.out());
    }

    @Test
    void aNullRootDocumentIsValid() throws Exception {
        file("null.json", "null");

        Result view = run("", false, false, "null.json");
        Result query = run("", false, false, "-p", "$", "null.json");

        assertEquals(0, view.code(), view.err());
        assertEquals("null\n", view.out());
        assertEquals(0, query.code(), query.err());
        assertEquals("null\n", query.out());
    }

    @Test
    void filterQueryUsesTheSameSemanticsAsTheStandardForm() throws Exception {
        file("response.json", "{\"items\":[{\"id\":1,\"status\":\"failed\"},{\"id\":2,\"status\":\"ok\"}]}");

        Result shorthand = run("", false, false, "-p", "items[?@.status == 'failed'].id", "response.json");
        Result standard = run("", false, false, "-p", "$.items[?@.status == 'failed'].id", "response.json");

        assertEquals(0, shorthand.code(), shorthand.err());
        assertEquals(shorthand.out(), standard.out());
        assertEquals("[\n  1\n]\n", shorthand.out());
    }

    @Test
    void invalidJsonPathIsAUsageError() throws Exception {
        file("response.json", "{\"a\":1}");

        Result result = run("", false, false, "-p", "$[", "response.json");

        assertEquals(2, result.code());
        assertEquals("", result.out());
        assertTrue(result.err().contains("invalid JSONPath"), result.err());
    }

    @Test
    void invalidJsonIsAnExecutionFailureWithSourceAndLocation() throws Exception {
        file("response.json", "{\n  \"a\": \n}");

        Result result = run("", false, false, "-p", "a", "response.json");

        assertEquals(1, result.code());
        assertEquals("", result.out());
        assertTrue(result.err().contains("response.json:3:"), result.err());
    }

    @Test
    void emptyInputIsAnExecutionFailure() {
        Result result = run("", false, false, "-p", "a");

        assertEquals(1, result.code());
        assertEquals("", result.out());
        assertTrue(result.err().contains("empty input"), result.err());
    }

    @Test
    void viewingAllowsDuplicateKeysButQueryingRejectsThem() throws Exception {
        file("response.json", "{\"a\":1,\"a\":2}");

        Result view = run("", false, false, "response.json");
        Result query = run("", false, false, "-p", "a", "response.json");

        assertEquals(0, view.code(), view.err());
        assertTrue(view.out().contains("\"a\": 1") && view.out().contains("\"a\": 2"), view.out());
        assertEquals(1, query.code());
        assertEquals("", query.out());
        assertTrue(query.err().contains("duplicate"), query.err());
    }

    @Test
    void missingFileIsAnExecutionFailure() {
        Result result = run("", false, false, "missing.json");

        assertEquals(1, result.code());
        assertEquals("", result.out());
        assertTrue(result.err().contains("missing.json"), result.err());
    }

    @Test
    void unknownOptionIsAUsageError() {
        Result result = run("", false, false, "--not-a-real-option");

        assertEquals(2, result.code());
        assertEquals("", result.out());
        assertFalse(result.err().contains("\u001b"), result.err());
    }

    @Test
    void extraPositionalArgumentsAreAUsageError() {
        Result result = run("", false, false, "one.json", "two.json");

        assertEquals(2, result.code());
        assertEquals("", result.out());
    }

    @Test
    void doubleDashAllowsAFileWhoseNameStartsWithAHyphen() throws Exception {
        file("-data.json", "{\"a\":1}");

        Result result = run("", false, false, "--", "-data.json");

        assertEquals(0, result.code(), result.err());
        assertEquals("{\n  \"a\": 1\n}\n", result.out());
    }

    @Test
    void filePathsMayContainSpaces() throws Exception {
        file("with space.json", "{\"a\":1}");

        Result result = run("", false, false, "with space.json");

        assertEquals(0, result.code(), result.err());
        assertEquals("{\n  \"a\": 1\n}\n", result.out());
    }

    @Test
    void stdoutRedirectedOutputHasNoAnsiEvenWithATerminalStdin() throws Exception {
        file("response.json", "{\"a\":1}");

        Result result = run("", true, false, "response.json");

        assertEquals(0, result.code(), result.err());
        assertFalse(result.out().contains("\u001b"), result.out());
    }

    @Test
    void noColorFlagDisablesAnsiOnATerminal() throws Exception {
        file("response.json", "{\"a\":1}");

        Result colored = run("", false, true, "response.json");
        Result plain = run("", false, true, "--no-color", "response.json");

        assertTrue(colored.out().contains("\u001b"), colored.out());
        assertFalse(plain.out().contains("\u001b"), plain.out());
        assertEquals(stripAnsi(colored.out()), plain.out());
    }

    @Test
    void noColorEnvironmentDisablesAnsiOnATerminal() throws Exception {
        file("response.json", "{\"a\":1}");
        Map<String, String> environment = new HashMap<>();
        environment.put("TERM", "xterm-256color");
        environment.put("NO_COLOR", "");

        Result result = runWithEnvironment(environment, "response.json");

        assertEquals(0, result.code(), result.err());
        assertFalse(result.out().contains("\u001b"), result.out());
    }

    @Test
    void colorizedOutputKeepsTheSameVisibleText() throws Exception {
        file("response.json", "{\"a\":\"value\",\"n\":1,\"ok\":true,\"nothing\":null}");

        Result colored = run("", false, true, "response.json");

        assertEquals(0, colored.code(), colored.err());
        assertTrue(colored.out().contains("\u001b[36;1m"), colored.out());
        assertEquals("{\n  \"a\": \"value\",\n  \"n\": 1,\n  \"ok\": true,\n  \"nothing\": null\n}\n",
                stripAnsi(colored.out()));
    }

    @Test
    void helpAndVersionSucceed() {
        Result help = run("", false, false, "--help");
        Result version = run("", false, false, "--version");

        assertEquals(0, help.code(), help.err());
        assertTrue(help.out().contains("Usage: jsonkit"), help.out());
        assertEquals(0, version.code(), version.err());
        assertTrue(version.out().contains("jsonkit " + ApplicationInfo.load().version()), version.out());
    }

    @Test
    void debugFlagIsAccepted() throws Exception {
        file("response.json", "{\"a\":1}");

        Result result = run("", false, false, "--debug", "response.json");

        assertEquals(0, result.code(), result.err());
    }

    private static String stripAnsi(String value) {
        return value.replaceAll("\u001b\\[[;\\d]*m", "");
    }
}
