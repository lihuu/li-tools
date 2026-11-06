package io.github.lihuu.dukit.cli;

import io.github.lihuu.clicommon.cli.CliExitCode;
import io.github.lihuu.clicommon.cli.CliUsageException;
import io.github.lihuu.clicommon.cli.GlobalOptionsMixin;
import io.github.lihuu.dukit.app.ApplicationInfo;
import io.github.lihuu.dukit.core.model.ScanResult;
import io.github.lihuu.dukit.core.report.JsonReporter;
import io.github.lihuu.dukit.core.report.TreeReporter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.util.List;

@Command(name = "scan", description = "Scan a directory tree and print every directory with its size. "
        + "Output is JSON by default; use --text for a human-readable tree. "
        + "JSON reports logical size and occupied disk space. Text shows occupied space by default.")
public final class ScanCommand extends CliCommand.Action {
    @Parameters(index = "0", arity = "0..1", defaultValue = ".", description = "Directory to scan (default: current directory)")
    String path;

    @Option(names = "--text", description = "Print a human-readable tree instead of JSON")
    boolean text;

    @Option(names = "--depth", defaultValue = "-1",
            description = "Directory levels to emit; sizes always cover whole subtrees (default: -1 = all levels, 0 = root only)")
    int depth;

    @Option(names = "--apparent-size",
            description = "Show logical sizes in --text output")
    boolean apparentSize;

    @Option(names = "--exclude", description = "Glob pattern to skip; a bare name matches any path component, a pattern with '/' matches the path relative to the scan root")
    List<String> excludes;

    @Override
    int execute() throws IOException {
        GlobalOptionsMixin options = root.globalOptions();
        if (text && options.json) {
            throw new CliUsageException("--text and --json cannot be combined");
        }
        ScanResult result = root.scanTree(path, excludes, options.quiet);
        root.reportScanErrors(result, options.quiet);
        if (text) {
            new TreeReporter(out(), root.outputStyle(), depth, apparentSize).write(result);
        } else {
            new JsonReporter(out(), ApplicationInfo.load(), depth).write(result);
        }
        return CliExitCode.OK;
    }
}
