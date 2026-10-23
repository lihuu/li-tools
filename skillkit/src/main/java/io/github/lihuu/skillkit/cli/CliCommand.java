package io.github.lihuu.skillkit.cli;

import io.github.lihuu.clicommon.cli.CliExitCode;
import io.github.lihuu.clicommon.cli.CliUsageException;
import io.github.lihuu.clicommon.cli.GlobalOptionsMixin;
import io.github.lihuu.skillkit.app.ApplicationInfo;
import io.github.lihuu.skillkit.core.RepositoryManager;
import io.github.lihuu.skillkit.core.RepositoryStore;
import io.github.lihuu.skillkit.core.SkillFiles;
import io.github.lihuu.skillkit.core.SkillManager;
import io.github.lihuu.skillkit.core.SourceResolver;
import org.jetbrains.annotations.NotNull;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "skillkit", description = "Manage your personal skills repository and install into shared or selected agent directories.",
        mixinStandardHelpOptions = true, versionProvider = CliCommand.VersionProvider.class,
        subcommands = {InstallationCommands.Install.class, InstallationCommands.Update.class,
                InstallationCommands.Uninstall.class, InstallationCommands.ListInstalled.class, PushCommand.class, PullCommand.class})
public final class CliCommand implements Callable<Integer> {
    @Mixin GlobalOptionsMixin globalOptions;
    @Spec CommandSpec spec;
    @Option(names = "--repository", scope = CommandLine.ScopeType.INHERIT,
            description = "Personal skills repository (env SKILLKIT_REPOSITORY)")
    Path repository;

    final Path home;
    final Path workingDirectory;
    final Map<String, String> environment;
    private RepositoryManager manager;

    public CliCommand(@NotNull Path home, @NotNull Path workingDirectory, @NotNull Map<String, String> environment) {
        this.home = home;
        this.workingDirectory = workingDirectory;
        this.environment = environment;
    }

    @Override
    public @NotNull Integer call() {
        spec.commandLine().usage(spec.commandLine().getOut(), CommandLine.Help.Ansi.OFF);
        return CliExitCode.OK;
    }

    RepositoryManager manager() {
        if (manager == null) {
            Path root = repository;
            if (root == null && environment.containsKey("SKILLKIT_REPOSITORY")) root = Path.of(environment.get("SKILLKIT_REPOSITORY"));
            if (root == null) {
                Path candidate = workingDirectory;
                while (candidate != null && !Files.isRegularFile(candidate.resolve("skillkit.json"))
                        && !Files.isDirectory(candidate.resolve("skills"))) candidate = candidate.getParent();
                root = candidate == null ? home.resolve(".skillkit/repository") : candidate;
            }
            root = workingDirectory.resolve(root).toAbsolutePath().normalize();
            Path cache = home.resolve(".skillkit/cache").resolve(SkillFiles.key(root.toString()));
            manager = new RepositoryManager(new RepositoryStore(root), new SourceResolver(cache, home.resolve(".skillkit/cache/git")));
        }
        return manager;
    }

    SkillManager installer() {
        return new SkillManager(manager(), home.resolve(".skillkit/cache/staging"));
    }

    String source(String value) {
        if (value.contains("://") || value.startsWith("git@")) return value;
        Path path = workingDirectory.resolve(value).normalize();
        return Files.isDirectory(path) ? path.toAbsolutePath().toString() : value;
    }

    @Command
    abstract static class Action implements Callable<Integer> {
        @ParentCommand CliCommand root;
        @Override
        public final @NotNull Integer call() throws IOException {
            try {
                return execute();
            } catch (IllegalArgumentException failure) {
                throw new CliUsageException(failure.getMessage());
            }
        }
        abstract int execute() throws IOException;
    }

    public static final class VersionProvider implements IVersionProvider {
        @Override public @NotNull String[] getVersion() {
            ApplicationInfo info = ApplicationInfo.load();
            return new String[]{info.name() + " " + info.version()};
        }
    }
}
