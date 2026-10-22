package io.github.lihuu.skillkit.app;

import io.github.lihuu.clicommon.cli.CliRunner;
import io.github.lihuu.skillkit.cli.CliCommand;
import org.jetbrains.annotations.NotNull;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Map;

public final class CliApplication {
    private CliApplication() {}

    public static void main(@NotNull String[] args) {
        System.exit(CliRunner.run(new CliCommand(Path.of(System.getProperty("user.home")), Path.of("").toAbsolutePath(), System.getenv()), args));
    }

    public static int run(
            @NotNull String[] args,
            @NotNull PrintWriter out,
            @NotNull PrintWriter err,
            @NotNull Map<String, String> environment,
            boolean interactive
    ) {
        return run(args, Path.of(System.getProperty("user.home")), Path.of("").toAbsolutePath(), out, err, environment, interactive);
    }

    public static int run(
            @NotNull String[] args, @NotNull Path home, @NotNull Path workingDirectory,
            @NotNull PrintWriter out, @NotNull PrintWriter err,
            @NotNull Map<String, String> environment, boolean interactive
    ) {
        return CliRunner.run(new CliCommand(home, workingDirectory, environment), args, out, err, environment, interactive);
    }
}
