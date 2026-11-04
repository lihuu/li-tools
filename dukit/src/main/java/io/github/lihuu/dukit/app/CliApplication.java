package io.github.lihuu.dukit.app;

import io.github.lihuu.clicommon.cli.CliRunner;
import io.github.lihuu.dukit.cli.CliCommand;
import org.jetbrains.annotations.NotNull;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Map;

public final class CliApplication {
    private CliApplication() {}

    public static void main(@NotNull String[] args) {
        System.exit(CliRunner.run(new CliCommand(Path.of("").toAbsolutePath(), System.getenv(), System.console() != null), args));
    }

    public static int run(
            @NotNull String[] args,
            @NotNull PrintWriter out,
            @NotNull PrintWriter err,
            @NotNull Map<String, String> environment,
            boolean interactive
    ) {
        return run(args, Path.of("").toAbsolutePath(), out, err, environment, interactive);
    }

    public static int run(
            @NotNull String[] args, @NotNull Path workingDirectory,
            @NotNull PrintWriter out, @NotNull PrintWriter err,
            @NotNull Map<String, String> environment, boolean interactive
    ) {
        return CliRunner.run(new CliCommand(workingDirectory, environment, interactive), args, out, err, environment, interactive);
    }
}
