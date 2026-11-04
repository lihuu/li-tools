package io.github.lihuu.dukit.cli;

import io.github.lihuu.clicommon.cli.CliExitCode;
import io.github.lihuu.clicommon.cli.GlobalOptionsMixin;
import io.github.lihuu.dukit.app.ApplicationInfo;
import io.github.lihuu.dukit.core.model.ScanResult;
import io.github.lihuu.dukit.core.report.JsonReporter;
import io.github.lihuu.dukit.core.report.TreeReporter;
import org.jetbrains.annotations.NotNull;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.util.List;

@Command(name = "scan", description = "Scan a directory tree and print a size report sorted by size. "
        + "Sizes are apparent sizes (logical file sizes); the JDK exposes no portable allocated-block metric.")
public final class ScanCommand extends CliCommand.Action {
    @Parameters(index = "0", arity = "0..1", defaultValue = ".", description = "Directory to scan (default: current directory)")
    String path;

    @Option(names = "--depth", defaultValue = "1", description = "Directory levels to show below the root; sizes always cover whole subtrees (default: 1)")
    int depth;

    @Option(names = "--exclude", description = "Glob pattern to skip; a bare name matches any path component, a pattern with '/' matches the path relative to the scan root")
    List<String> excludes;

    @Override
    int execute() throws IOException {
        GlobalOptionsMixin options = root.globalOptions();
        ScanResult result = root.scanTree(path, excludes, options.quiet);
        root.reportScanErrors(result, options.quiet);
        if (options.json) {
            new JsonReporter(out(), ApplicationInfo.load()).write(result);
        } else {
            new TreeReporter(out(), root.outputStyle(), depth).write(result);
        }
        return CliExitCode.OK;
    }
}
