package io.github.lihuu.clicommon.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;

class CliRunnerTest {

    @Command(name = "happy")
    static class HappyCommand implements Callable<Integer> {
        @Mixin
        GlobalOptionsMixin global;

        @Override
        public Integer call() {
            return CliExitCode.OK;
        }
    }

    @Command(name = "fail")
    static class FailCommand implements Callable<Integer> {
        @Mixin
        GlobalOptionsMixin global;

        @Override
        public Integer call() {
            throw new CliExecutionException("Resource not found");
        }
    }

    @Command(name = "usage")
    static class UsageCommand implements Callable<Integer> {
        @Mixin
        GlobalOptionsMixin global;

        @Override
        public Integer call() {
            throw new CliUsageException("Bad parameter");
        }
    }

    @Command(name = "crash")
    static class CrashCommand implements Callable<Integer> {
        @Mixin
        GlobalOptionsMixin global;

        @Override
        public Integer call() {
            throw new IllegalStateException("Unexpected crash");
        }
    }

    @Test
    void shouldReturnOkOnHappyCommand() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();

        int code = CliRunner.run(
                new HappyCommand(),
                new String[]{},
                new PrintWriter(out, true),
                new PrintWriter(err, true),
                Map.of(),
                false
        );

        assertEquals(CliExitCode.OK, code);
        assertTrue(err.toString().isEmpty());
    }

    @Test
    void shouldReturnFailureAndCleanStderrOnExecutionException() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();

        int code = CliRunner.run(
                new FailCommand(),
                new String[]{},
                new PrintWriter(out, true),
                new PrintWriter(err, true),
                Map.of(),
                false
        );

        assertEquals(CliExitCode.FAILURE, code);
        assertTrue(err.toString().contains("Resource not found"));
        assertFalse(err.toString().contains("CliExecutionException"));
    }

    @Test
    void shouldReturnUsageErrorOnUsageException() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();

        int code = CliRunner.run(
                new UsageCommand(),
                new String[]{},
                new PrintWriter(out, true),
                new PrintWriter(err, true),
                Map.of(),
                false
        );

        assertEquals(CliExitCode.USAGE, code);
        assertTrue(err.toString().contains("Bad parameter"));
    }

    @Test
    void shouldReturnUsageErrorOnInvalidCliOption() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();

        int code = CliRunner.run(
                new HappyCommand(),
                new String[]{"--invalid-flag"},
                new PrintWriter(out, true),
                new PrintWriter(err, true),
                Map.of(),
                false
        );

        assertEquals(CliExitCode.USAGE, code);
    }

    @Test
    void shouldSuppressStackTraceWithoutDebugFlag() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();

        int code = CliRunner.run(
                new CrashCommand(),
                new String[]{},
                new PrintWriter(out, true),
                new PrintWriter(err, true),
                Map.of(),
                false
        );

        assertEquals(CliExitCode.FAILURE, code);
        assertTrue(err.toString().contains("Unexpected crash"));
        assertFalse(err.toString().contains("at io.github.lihuu"));
    }

    @Test
    void shouldPrintStackTraceWithDebugFlag() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();

        int code = CliRunner.run(
                new CrashCommand(),
                new String[]{"--debug"},
                new PrintWriter(out, true),
                new PrintWriter(err, true),
                Map.of(),
                false
        );

        assertEquals(CliExitCode.FAILURE, code);
        assertTrue(err.toString().contains("IllegalStateException: Unexpected crash"));
        assertTrue(err.toString().contains("at io.github.lihuu"));
    }

    @Test
    void shouldNeverEmitAnsiOnUsageErrorsEvenWhenInteractive() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();

        int code = CliRunner.run(
                new UsageCommand(),
                new String[]{},
                new PrintWriter(out, true),
                new PrintWriter(err, true),
                Map.of("TERM", "xterm-256color"),
                true
        );

        assertEquals(CliExitCode.USAGE, code);
        assertFalse(err.toString().contains("\u001b["), "Usage error must never contain ANSI escape codes");
        assertTrue(err.toString().contains("Bad parameter"));
    }

    @Command(name = "positional-crash")
    static class PositionalCrashCommand implements Callable<Integer> {
        @picocli.CommandLine.Parameters
        java.util.List<String> positionalArgs;

        @Override
        public Integer call() {
            throw new IllegalStateException("Unexpected crash");
        }
    }

    @Test
    void shouldRespectDoubleDashDelimiterDuringFlagPreScan() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();

        // When -- is followed by --debug, the pre-scan should stop at -- and NOT enable debug
        int code = CliRunner.run(
                new PositionalCrashCommand(),
                new String[]{"--", "--debug"},
                new PrintWriter(out, true),
                new PrintWriter(err, true),
                Map.of(),
                false
        );

        assertEquals(CliExitCode.FAILURE, code);
        // Stack trace should NOT be printed because --debug came after --
        assertFalse(err.toString().contains("at io.github.lihuu"));
    }
}
