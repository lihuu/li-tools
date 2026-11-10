package io.github.lihuu.mitmkit.cli;

import io.github.lihuu.clicommon.cli.CliExitCode;
import io.github.lihuu.clicommon.cli.GlobalOptionsMixin;
import io.github.lihuu.mitmkit.app.ApplicationInfo;
import org.jetbrains.annotations.NotNull;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
        name = "mitmkit",
        mixinStandardHelpOptions = true,
        versionProvider = CliCommand.VersionProvider.class,
        description = "Local HTTPS MITM proxy for development traffic capture. "
                + "With no subcommand in a terminal, shows this help.",
        subcommands = {RunCommand.class, CaCommand.class}
)
public final class CliCommand implements Callable<Integer> {
    @Spec
    CommandSpec spec;

    @Mixin
    GlobalOptionsMixin globalOptions;

    final Path workingDirectory;
    final Map<String, String> environment;
    final boolean stdinInteractive;
    final boolean stdoutInteractive;

    public CliCommand(
            @NotNull Path workingDirectory,
            @NotNull Map<String, String> environment,
            boolean stdinInteractive,
            boolean stdoutInteractive
    ) {
        this.workingDirectory = workingDirectory;
        this.environment = environment;
        this.stdinInteractive = stdinInteractive;
        this.stdoutInteractive = stdoutInteractive;
    }

    @Override
    public @NotNull Integer call() {
        spec.commandLine().usage(spec.commandLine().getOut());
        return CliExitCode.OK;
    }

    public static final class VersionProvider implements IVersionProvider {
        @Override
        public @NotNull String[] getVersion() {
            ApplicationInfo info = ApplicationInfo.load();
            return new String[]{info.name() + " " + info.version()};
        }
    }

    @Command(mixinStandardHelpOptions = true)
    abstract static class Action implements Callable<Integer> {
        @ParentCommand
        CliCommand root;
        @Spec
        CommandSpec spec;

        @Override
        public final @NotNull Integer call() throws Exception {
            return execute();
        }

        abstract int execute() throws Exception;

        PrintWriter out() {
            return spec.commandLine().getOut();
        }

        PrintWriter err() {
            return spec.commandLine().getErr();
        }
    }
}
