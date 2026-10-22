package io.github.lihuu.skillkit.app;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliApplicationTest {
    @Test
    void bareInvocationShowsHelp() {
        Result result = execute(Map.of(), false);

        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("Usage: skillkit"));
        assertTrue(result.out().contains("--help"));
        assertEquals("", result.err());
    }

    @Test
    void helpIncludesSharedGlobalOptions() {
        Result result = execute(Map.of(), false, "--help");

        assertEquals(0, result.exitCode());
        for (String option : new String[]{"--json", "--quiet", "--no-color", "--debug", "--version"}) {
            assertTrue(result.out().contains(option), option);
        }
        assertEquals("", result.err());
        assertFalse(result.out().contains("\u001b"));
    }

    @Test
    void versionComesFromMavenFilteredResource() throws IOException {
        Properties properties = new Properties();
        try (InputStream input = CliApplicationTest.class.getResourceAsStream("/application.properties")) {
            assertNotNull(input);
            properties.load(input);
        }
        String version = properties.getProperty("app.version");
        assertNotNull(version);
        assertFalse(version.isBlank());
        assertFalse(version.contains("${"));

        Result result = execute(Map.of(), false, "--version");

        assertEquals(0, result.exitCode());
        assertEquals("skillkit " + version, result.out().strip());
        assertEquals("", result.err());
    }

    @Test
    void unknownOptionReturnsUsageErrorOnStderrWithoutAnsi() {
        Result result = execute(Map.of("TERM", "xterm-256color"), true, "--unknown-option");

        assertEquals(2, result.exitCode());
        assertEquals("", result.out());
        assertTrue(result.err().contains("--unknown-option"));
        assertFalse(result.err().contains("\u001b"));
    }

    @Test
    void noColorAndSharedFlagsWorkWithBareInvocation() {
        for (Map<String, String> environment : List.of(Map.<String, String>of(), Map.of("NO_COLOR", ""))) {
            Result result = execute(environment, true, "--no-color", "--quiet", "--json", "--debug");

            assertEquals(0, result.exitCode());
            assertTrue(result.out().contains("Usage: skillkit"));
            assertEquals("", result.err());
            assertFalse(result.out().contains("\u001b"));
        }
    }

    private Result execute(Map<String, String> environment, boolean interactive, String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exitCode = CliApplication.run(args, new PrintWriter(out, true), new PrintWriter(err, true),
                environment, interactive);
        return new Result(exitCode, out.toString(), err.toString());
    }

    private record Result(int exitCode, String out, String err) {}
}
