package io.github.lihuu.tokenkit.cli;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import io.github.lihuu.tokenkit.app.ApplicationInfo;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Option;

@Command(
        name = "tokenkit",
        description = "Modern, cross-platform encrypted universal token manager with embedded SQLite and sub-10ms shell injection",
        mixinStandardHelpOptions = true,
        versionProvider = CliCommand.VersionProvider.class
)
public final class CliCommand implements Callable<Integer> {
    private final PrintWriter out;

    @Option(names = {"--no-color"}, description = "Disable ANSI color formatting", scope = CommandLine.ScopeType.INHERIT)
    private boolean noColor;

    public CliCommand(PrintWriter out) {
        this.out = out;
    }

    @Override
    public Integer call() {
        new CommandLine(this).usage(out);
        return 0;
    }

    public boolean isNoColor() {
        return noColor;
    }

    public static final class VersionProvider implements IVersionProvider {
        @Override
        public String[] getVersion() {
            ApplicationInfo info = ApplicationInfo.load();
            return new String[]{info.name() + " " + info.version()};
        }
    }
}
