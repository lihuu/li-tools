package io.github.lihuu.skillkit.app;

import io.github.lihuu.clicommon.cli.CliRunner;
import io.github.lihuu.skillkit.cli.CliCommand;
import org.jetbrains.annotations.NotNull;

import java.io.PrintWriter;
import java.util.Map;

public final class CliApplication {
    private CliApplication() {}

    public static void main(@NotNull String[] args) {
        System.exit(CliRunner.run(new CliCommand(), args));
    }

    public static int run(
            @NotNull String[] args,
            @NotNull PrintWriter out,
            @NotNull PrintWriter err,
            @NotNull Map<String, String> environment,
            boolean interactive
    ) {
        return CliRunner.run(new CliCommand(), args, out, err, environment, interactive);
    }
}
