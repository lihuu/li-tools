package io.github.lihuu.dukit.cli;

import io.github.lihuu.clicommon.cli.CliExitCode;
import io.github.lihuu.clicommon.cli.CliUsageException;
import io.github.lihuu.clicommon.cli.GlobalOptionsMixin;
import io.github.lihuu.dukit.core.browse.Browser;
import io.github.lihuu.dukit.core.model.ScanResult;
import io.github.lihuu.dukit.core.tui.FullScreenBrowser;
import org.jetbrains.annotations.NotNull;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.util.List;

@Command(name = "browse", description = "Scan a directory tree and explore it full-screen: arrows to move, "
        + "enter to descend, backspace to return. Requires an interactive terminal.")
public final class BrowseCommand extends CliCommand.Action {
    @Parameters(index = "0", arity = "0..1", defaultValue = ".", description = "Directory to scan (default: current directory)")
    String path;

    @Option(names = "--exclude", description = "Glob pattern to skip; a bare name matches any path component, a pattern with '/' matches the path relative to the scan root")
    List<String> excludes;

    @Override
    int execute() throws IOException {
        GlobalOptionsMixin options = root.globalOptions();
        if (!root.interactive) {
            throw new CliUsageException("browse requires an interactive terminal; use 'scan' instead");
        }
        ScanResult result = root.scanTree(path, excludes, options.quiet);
        root.reportScanErrors(result, options.quiet);
        boolean color = root.outputStyle().isAnsi();
        if (!FullScreenBrowser.run(result, color)) {
            // No usable terminal after all (dumb TERM, exotic terminal): the line-based
            // browser still works, it just needs Enter after every gesture.
            new Browser(result, in(), out(), root.outputStyle()).run();
        }
        return CliExitCode.OK;
    }
}
