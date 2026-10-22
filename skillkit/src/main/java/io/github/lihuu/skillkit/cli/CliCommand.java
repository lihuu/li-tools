package io.github.lihuu.skillkit.cli;

import io.github.lihuu.clicommon.cli.CliExitCode;
import io.github.lihuu.clicommon.cli.GlobalOptionsMixin;
import io.github.lihuu.skillkit.app.ApplicationInfo;
import org.jetbrains.annotations.NotNull;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

@Command(
        name = "skillkit",
        description = "Manage skills for local agents (module scaffold).",
        mixinStandardHelpOptions = true,
        versionProvider = CliCommand.VersionProvider.class
)
public final class CliCommand implements Callable<Integer> {
    @Mixin
    private GlobalOptionsMixin globalOptions;

    @Spec
    private CommandSpec spec;

    @Override
    public @NotNull Integer call() {
        spec.commandLine().usage(spec.commandLine().getOut(), CommandLine.Help.Ansi.OFF);
        return CliExitCode.OK;
    }

    public static final class VersionProvider implements IVersionProvider {
        @Override
        public @NotNull String[] getVersion() {
            ApplicationInfo info = ApplicationInfo.load();
            return new String[]{info.name() + " " + info.version()};
        }
    }
}
