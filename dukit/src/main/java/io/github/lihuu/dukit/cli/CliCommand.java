package io.github.lihuu.dukit.cli;

import io.github.lihuu.clicommon.cli.CliExitCode;
import io.github.lihuu.clicommon.cli.GlobalOptionsMixin;
import io.github.lihuu.clicommon.style.OutputStyle;
import io.github.lihuu.dukit.app.ApplicationInfo;
import io.github.lihuu.dukit.core.model.ScanResult;
import io.github.lihuu.dukit.core.scan.DirectoryScanner;
import io.github.lihuu.dukit.core.scan.ProgressPrinter;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "dukit", description = "Analyze disk usage across directories with scan reports and an interactive browser.",
        mixinStandardHelpOptions = true, versionProvider = CliCommand.VersionProvider.class,
        subcommands = {ScanCommand.class, BrowseCommand.class})
public final class CliCommand implements Callable<Integer> {
    @Mixin GlobalOptionsMixin globalOptions;
    @Spec CommandSpec spec;

    final Path workingDirectory;
    final Map<String, String> environment;
    final boolean interactive;

    public CliCommand(@NotNull Path workingDirectory, @NotNull Map<String, String> environment, boolean interactive) {
        this.workingDirectory = workingDirectory;
        this.environment = environment;
        this.interactive = interactive;
    }

    @Override
    public @NotNull Integer call() {
        spec.commandLine().usage(spec.commandLine().getOut(), CommandLine.Help.Ansi.OFF);
        return CliExitCode.OK;
    }

    GlobalOptionsMixin globalOptions() {
        return globalOptions;
    }

    OutputStyle outputStyle() {
        return OutputStyle.of(environment, interactive, globalOptions.noColor);
    }

    /**
     * Resolves the requested path against the working directory, verifies it is a readable
     * directory and scans it. Shared by every command that needs a scan.
     */
    ScanResult scanTree(@NotNull String path, List<String> excludes, boolean quiet) throws IOException {
        Path target = workingDirectory.resolve(path).normalize().toAbsolutePath();
        if (!Files.isDirectory(target)) {
            throw new IOException("not a directory: " + target);
        }
        PrintWriter errWriter = spec.commandLine().getErr();
        ProgressPrinter progress = new ProgressPrinter(errWriter, interactive && !quiet);
        ScanResult result;
        try {
            result = new DirectoryScanner(excludes == null ? List.of() : excludes).scan(target, progress);
        } finally {
            progress.finish();
        }
        return result;
    }

    private static final int MAX_PRINTED_ERRORS = 10;

    void reportScanErrors(@NotNull ScanResult result, boolean quiet) {
        if (result.errors().isEmpty() || quiet) {
            return;
        }
        PrintWriter errWriter = spec.commandLine().getErr();
        OutputStyle style = outputStyle();
        for (int i = 0; i < Math.min(result.errors().size(), MAX_PRINTED_ERRORS); i++) {
            errWriter.println(style.warning(result.errors().get(i).path() + ": " + result.errors().get(i).message()));
        }
        int remaining = result.errors().size() - MAX_PRINTED_ERRORS;
        if (remaining > 0) {
            errWriter.println(style.warning("… and " + remaining + " more unreadable paths"));
        }
    }

    @Command
    abstract static class Action implements Callable<Integer> {
        @ParentCommand CliCommand root;
        @Spec CommandSpec spec;

        @Override
        public final @NotNull Integer call() throws IOException {
            return execute();
        }

        abstract int execute() throws IOException;

        PrintWriter out() {
            return spec.commandLine().getOut();
        }

        BufferedReader in() {
            return new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        }
    }

    public static final class VersionProvider implements IVersionProvider {
        @Override public @NotNull String[] getVersion() {
            ApplicationInfo info = ApplicationInfo.load();
            return new String[]{info.name() + " " + info.version()};
        }
    }
}
