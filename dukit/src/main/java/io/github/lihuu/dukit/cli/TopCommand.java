package io.github.lihuu.dukit.cli;

import io.github.lihuu.clicommon.cli.CliExitCode;
import io.github.lihuu.clicommon.cli.GlobalOptionsMixin;
import io.github.lihuu.dukit.app.ApplicationInfo;
import io.github.lihuu.dukit.core.model.TopResult;
import io.github.lihuu.dukit.core.report.TopJsonReporter;
import io.github.lihuu.dukit.core.report.TopTextReporter;
import org.jetbrains.annotations.NotNull;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

@Command(name = "top", description = "Find the largest directories or files under a path. "
        + "Streams the scan without building a tree, so memory stays proportional to --limit.")
public final class TopCommand extends CliCommand.Action {
    @Parameters(index = "0", arity = "0..1", defaultValue = ".", description = "Directory to scan (default: current directory)")
    String path;

    @Option(names = {"--limit", "-n"}, defaultValue = "20", description = "How many entries to report (default: 20)")
    int limit;

    @Option(names = "--files", description = "Rank individual files instead of directories")
    boolean files;

    @Option(names = "--min-size", defaultValue = "0",
            description = "Only report entries at least this many bytes (accepts KiB/MiB/GiB suffixes)")
    String minSize;

    @Option(names = "--exclude", description = "Glob pattern to skip; a bare name matches any path component, a pattern with '/' matches the path relative to the scan root")
    List<String> excludes;

    @Override
    int execute() throws IOException {
        GlobalOptionsMixin options = root.globalOptions();
        long threshold = parseSize(minSize);
        TopResult result = root.topTree(path, excludes, Math.max(1, limit), options.quiet);
        root.reportScanErrors(result, options.quiet);

        String kind = files ? "files" : "directories";
        if (options.json) {
            Path target = root.workingDirectory.resolve(path).normalize().toAbsolutePath();
            new TopJsonReporter(out(), ApplicationInfo.load()).write(result, target.toString(), kind, threshold);
        } else {
            new TopTextReporter(out(), root.outputStyle()).write(result, kind, threshold);
        }
        return CliExitCode.OK;
    }

    private static long parseSize(@NotNull String value) {
        String trimmed = value.trim();
        String lower = trimmed.toLowerCase();
        long multiplier = 1;
        String number = lower;
        for (String[] suffix : new String[][]{{"b", "1"}, {"kib", "1024"}, {"kb", "1024"}, {"k", "1024"},
                {"mib", "1048576"}, {"mb", "1048576"}, {"m", "1048576"},
                {"gib", "1073741824"}, {"gb", "1073741824"}, {"g", "1073741824"},
                {"tib", "1099511627776"}, {"tb", "1099511627776"}, {"t", "1099511627776"}}) {
            if (lower.endsWith(suffix[0])) {
                multiplier = Long.parseLong(suffix[1]);
                number = lower.substring(0, lower.length() - suffix[0].length());
                break;
            }
        }
        try {
            return Long.parseLong(number.trim()) * multiplier;
        } catch (NumberFormatException invalid) {
            throw new io.github.lihuu.clicommon.cli.CliUsageException("invalid --min-size: " + value);
        }
    }
}
