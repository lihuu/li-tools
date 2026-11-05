package io.github.lihuu.dukit.cli;

import io.github.lihuu.clicommon.cli.CliExitCode;
import io.github.lihuu.clicommon.cli.GlobalOptionsMixin;
import io.github.lihuu.clicommon.style.OutputStyle;
import io.github.lihuu.dukit.app.ApplicationInfo;
import io.github.lihuu.dukit.core.model.ScanError;
import io.github.lihuu.dukit.core.model.ScanResult;
import io.github.lihuu.dukit.core.model.TopResult;
import io.github.lihuu.dukit.core.scan.DirectoryScanner;
import io.github.lihuu.dukit.core.browse.Browser;
import io.github.lihuu.dukit.core.delete.DeleteService;
import io.github.lihuu.dukit.core.trash.TrashStores;
import io.github.lihuu.dukit.core.report.JsonReporter;
import io.github.lihuu.dukit.core.scan.ProgressPrinter;
import io.github.lihuu.dukit.core.tui.FullScreenBrowser;
import io.github.lihuu.dukit.core.tui.TuiState;
import org.jetbrains.annotations.NotNull;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "dukit", description = "Analyze disk usage. Run it with no arguments in a terminal to browse the "
        + "current directory full-screen; give it a path to browse that one. Piped output is a JSON report. "
        + "Subcommands cover one-shot scans and rankings.",
        mixinStandardHelpOptions = true, versionProvider = CliCommand.VersionProvider.class,
        subcommands = {ScanCommand.class, TopCommand.class, BrowseCommand.class})
public final class CliCommand implements Callable<Integer> {
    private static final int MAX_PRINTED_ERRORS = 10;

    @Mixin GlobalOptionsMixin globalOptions;
    @Spec CommandSpec spec;

    @Parameters(index = "0", arity = "0..1",
            description = "Directory to open (default: current directory)")
    String path;

    final Path workingDirectory;
    final Map<String, String> environment;
    final boolean interactive;

    public CliCommand(@NotNull Path workingDirectory, @NotNull Map<String, String> environment, boolean interactive) {
        this.workingDirectory = workingDirectory;
        this.environment = environment;
        this.interactive = interactive;
    }

    @Override
    public @NotNull Integer call() throws IOException {
        // A bare invocation with no terminal keeps the documented behaviour: print usage.
        // With a terminal it is the tool's main gesture, so it opens the browser.
        if (path == null && !interactive) {
            spec.commandLine().usage(spec.commandLine().getOut(), CommandLine.Help.Ansi.OFF);
            return CliExitCode.OK;
        }
        ScanResult result = scanTree(path == null ? "." : path, null, globalOptions.quiet,
                interactive ? TuiState.TOP_FILES : 0);
        reportScanErrors(result, globalOptions.quiet);
        if (interactive) {
            if (FullScreenBrowser.run(result, outputStyle().isAnsi(), deleteService())) {
                return CliExitCode.OK;
            }
            // Raw mode unavailable (exotic terminal): the line-based browser still works.
            return new Browser(result, input(), out(), outputStyle(), deleteService()).run();
        }
        // Piped: a report is what a script can consume. Same shape as `scan`.
        new JsonReporter(out(), ApplicationInfo.load(), -1).write(result);
        return CliExitCode.OK;
    }

    private @NotNull BufferedReader input() {
        return new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    }

    private @NotNull PrintWriter out() {
        return spec.commandLine().getOut();
    }

    GlobalOptionsMixin globalOptions() {
        return globalOptions;
    }

    OutputStyle outputStyle() {
        return OutputStyle.of(environment, interactive, globalOptions.noColor);
    }

    DeleteService deleteService() {
        return new DeleteService(TrashStores.forCurrentPlatform(
                Path.of(System.getProperty("user.home")), environment, Clock.systemDefaultZone()));
    }

    ScanResult scanTree(@NotNull String path, List<String> excludes, boolean quiet) throws IOException {
        return scanTree(path, excludes, quiet, 0);
    }

    ScanResult scanTree(@NotNull String path, List<String> excludes, boolean quiet, int topFiles) throws IOException {
        Path target = resolve(path);
        ProgressPrinter progress = progressPrinter(quiet);
        ScanResult result;
        try {
            result = new DirectoryScanner(excludes == null ? List.of() : excludes).scan(target, progress, topFiles);
        } finally {
            progress.finish();
        }
        return result;
    }

    TopResult topTree(@NotNull String path, List<String> excludes, int limit, boolean quiet) throws IOException {
        Path target = resolve(path);
        ProgressPrinter progress = progressPrinter(quiet);
        try {
            return new DirectoryScanner(excludes == null ? List.of() : excludes).top(target, limit, progress);
        } finally {
            progress.finish();
        }
    }

    private @NotNull Path resolve(@NotNull String path) throws IOException {
        Path target = workingDirectory.resolve(path).normalize().toAbsolutePath();
        if (!Files.isDirectory(target)) {
            throw new IOException("not a directory: " + target);
        }
        // A scan root that is itself a symlink (/tmp on macOS, for instance) must be
        // resolved first: the walker never follows links, so it would otherwise see the
        // root as a single skipped file.
        return target.toRealPath();
    }

    private @NotNull ProgressPrinter progressPrinter(boolean quiet) {
        return new ProgressPrinter(spec.commandLine().getErr(), interactive && !quiet);
    }

    void reportScanErrors(@NotNull ScanResult result, boolean quiet) {
        reportErrors(result.errors(), quiet);
    }

    void reportScanErrors(@NotNull TopResult result, boolean quiet) {
        reportErrors(result.errors(), quiet);
    }

    private void reportErrors(@NotNull List<ScanError> errors, boolean quiet) {
        if (errors.isEmpty() || quiet) {
            return;
        }
        PrintWriter errWriter = spec.commandLine().getErr();
        OutputStyle style = outputStyle();
        for (int i = 0; i < Math.min(errors.size(), MAX_PRINTED_ERRORS); i++) {
            errWriter.println(style.warning(errors.get(i).path() + ": " + errors.get(i).message()));
        }
        int remaining = errors.size() - MAX_PRINTED_ERRORS;
        if (remaining > 0) {
            errWriter.println(style.warning("… and " + remaining + " more unreadable paths"));
        }
    }

    @Command(mixinStandardHelpOptions = true)
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
