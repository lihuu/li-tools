package io.github.lihuu.clicommon.cli;

import io.github.lihuu.clicommon.style.OutputStyle;
import org.jetbrains.annotations.NotNull;
import picocli.CommandLine;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Unified CLI runner and lifecycle orchestrator for li-tools.
 */
public final class CliRunner {
    private CliRunner() {}

    /**
     * Pins stdout and stderr to UTF-8 to prevent garbled text in LANG=C environments.
     */
    public static void pinUtf8Streams() {
        try {
            System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    public static int run(@NotNull Object rootCommand, String @NotNull [] args) {
        pinUtf8Streams();
        PrintWriter out = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
        PrintWriter err = new PrintWriter(System.err, true, StandardCharsets.UTF_8);
        return run(rootCommand, args, out, err, System.getenv(), System.console() != null);
    }

    public static int run(
            @NotNull Object rootCommand,
            String @NotNull [] args,
            @NotNull PrintWriter out,
            @NotNull PrintWriter err,
            @NotNull Map<String, String> env,
            boolean interactive
    ) {
        boolean noColorFlag = false;
        boolean debugFlag = false;
        for (String arg : args) {
            if ("--".equals(arg)) {
                break;
            }
            if ("--no-color".equals(arg)) {
                noColorFlag = true;
            } else if ("--debug".equals(arg)) {
                debugFlag = true;
            }
        }

        final boolean isDebug = debugFlag;
        final OutputStyle outputStyle = OutputStyle.of(env, interactive, noColorFlag);

        CommandLine cmd = new CommandLine(rootCommand);
        cmd.setOut(out);
        cmd.setErr(err);

        cmd.setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
            if (ex instanceof CliUsageException) {
                err.println(OutputStyle.plain().error(ex.getMessage()));
                return CliExitCode.USAGE;
            }
            if (ex instanceof CliExecutionException) {
                err.println(outputStyle.error(ex.getMessage()));
                return CliExitCode.FAILURE;
            }
            if (isDebug) {
                ex.printStackTrace(err);
                return CliExitCode.FAILURE;
            }
            String message = (ex.getMessage() != null && !ex.getMessage().isBlank())
                    ? ex.getMessage()
                    : ex.getClass().getSimpleName();
            err.println(outputStyle.error(message));
            return CliExitCode.FAILURE;
        });

        cmd.setParameterExceptionHandler((ex, args1) -> {
            CommandLine.UnmatchedArgumentException.printSuggestions(ex, err);
            err.println(OutputStyle.plain().error(ex.getMessage()));
            return CliExitCode.USAGE;
        });

        return cmd.execute(args);
    }
}
