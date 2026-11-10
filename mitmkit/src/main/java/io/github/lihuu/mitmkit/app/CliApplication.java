package io.github.lihuu.mitmkit.app;

import io.github.lihuu.clicommon.cli.CliRunner;
import io.github.lihuu.mitmkit.cli.CliCommand;
import org.jetbrains.annotations.NotNull;

import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

public final class CliApplication {
    private CliApplication() {}

    public static void main(@NotNull String[] args) {
        CliRunner.pinUtf8Streams();
        PrintWriter out = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
        PrintWriter err = new PrintWriter(System.err, true, StandardCharsets.UTF_8);
        System.exit(run(args, Path.of("").toAbsolutePath(), out, err, System.getenv(),
                System.console() != null, System.console() != null, System.in));
    }

    public static int run(
            @NotNull String[] args,
            @NotNull Path workingDirectory,
            @NotNull PrintWriter out,
            @NotNull PrintWriter err,
            @NotNull Map<String, String> environment,
            boolean stdinInteractive,
            boolean stdoutInteractive,
            @NotNull InputStream stdin
    ) {
        CliCommand command = new CliCommand(workingDirectory, environment, stdinInteractive, stdoutInteractive);
        return CliRunner.run(command, args, out, err, environment, stdoutInteractive);
    }
}
