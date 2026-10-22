package io.github.lihuu.skillkit.cli;

import io.github.lihuu.clicommon.cli.CliExitCode;
import io.github.lihuu.clicommon.cli.CliUsageException;
import io.github.lihuu.clicommon.cli.GlobalOptionsMixin;
import io.github.lihuu.clicommon.json.JsonWriter;
import io.github.lihuu.skillkit.app.ApplicationInfo;
import io.github.lihuu.skillkit.core.RepositoryManager;
import io.github.lihuu.skillkit.core.RepositoryStore;
import io.github.lihuu.skillkit.core.SkillFiles;
import io.github.lihuu.skillkit.core.SourceResolver;
import org.jetbrains.annotations.NotNull;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "skillkit", description = "Manage your skills repository and agent installations.",
        mixinStandardHelpOptions = true, versionProvider = CliCommand.VersionProvider.class,
        subcommands = {CliCommand.Init.class, CliCommand.Create.class, CliCommand.Import.class,
                CliCommand.Track.class, CliCommand.Untrack.class, CliCommand.Catalog.class,
                CliCommand.Available.class, CliCommand.Find.class, CliCommand.Use.class})
public final class CliCommand implements Callable<Integer> {
    @Mixin GlobalOptionsMixin globalOptions;
    @Spec CommandSpec spec;
    @Option(names = "--repository", scope = CommandLine.ScopeType.INHERIT,
            description = "Personal skills repository (env SKILLKIT_REPOSITORY)")
    Path repository;

    private final Path home;
    private final Path workingDirectory;
    private final Map<String, String> environment;
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
                while (candidate != null && !Files.isRegularFile(candidate.resolve("skillkit.json"))) candidate = candidate.getParent();
                root = candidate == null ? home.resolve(".skillkit/repository") : candidate;
            }
            root = workingDirectory.resolve(root).toAbsolutePath().normalize();
            Path cache = home.resolve(".cache/skillkit").resolve(SkillFiles.key(root.toString()));
            manager = new RepositoryManager(new RepositoryStore(root), new SourceResolver(cache));
        }
        return manager;
    }

    String source(String value) {
        if (value.contains("://") || value.startsWith("git@")) return value;
        Path path = workingDirectory.resolve(value).normalize();
        return Files.isDirectory(path) ? path.toAbsolutePath().toString() : value;
    }

    void notice(String action, String name) {
        if (globalOptions.json) {
            spec.commandLine().getOut().println(new JsonWriter().beginObject().name("action").value(action)
                    .name("name").value(name).endObject());
        } else if (!globalOptions.quiet) spec.commandLine().getOut().println(action + " " + name);
    }

    void entries(List<RepositoryManager.Entry> entries) {
        if (globalOptions.json) {
            JsonWriter json = new JsonWriter().beginArray();
            for (RepositoryManager.Entry entry : entries) json.beginObject().name("name").value(entry.name())
                    .name("kind").value(entry.kind()).name("source").value(entry.source()).endObject();
            spec.commandLine().getOut().println(json.endArray());
        } else for (RepositoryManager.Entry entry : entries) {
            spec.commandLine().getOut().println(entry.name() + "\t" + entry.kind() + "\t" + entry.source());
        }
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

    static class SourceOptions {
        @Option(names = {"--source", "--repo"}, required = true, description = "Local directory, Git URL or GitHub owner/repo")
        String source;
        @Option(names = "--ref", defaultValue = "HEAD", description = "Upstream Git branch, tag or commit") String ref;
        @Option(names = "--dry-run", description = "Show requested changes without writes or fetching") boolean dryRun;
    }

    @Command(name = "init", mixinStandardHelpOptions = true, description = "Initialize a personal skills directory")
    public static final class Init extends Action {
        @Override int execute() throws IOException {
            root.manager().initialize();
            root.notice("initialized", root.manager().store().root().toString());
            return 0;
        }
    }

    @Command(name = "create", mixinStandardHelpOptions = true, description = "Create an editable local skill")
    public static final class Create extends Action {
        @Parameters(index = "0") String name;
        @Override int execute() throws IOException {
            root.manager().create(name);
            root.notice("created", name);
            return 0;
        }
    }

    @Command(name = "import", mixinStandardHelpOptions = true, description = "Copy skills into your repository as local content")
    public static final class Import extends Action {
        @Parameters(arity = "1..*") List<String> names;
        @Mixin SourceOptions source;
        @Override int execute() throws IOException {
            for (String name : names) {
                root.manager().collect(name, root.source(source.source), source.ref, false, source.dryRun);
                root.notice(source.dryRun ? "would-import" : "imported", name);
            }
            return 0;
        }
    }

    @Command(name = "track", mixinStandardHelpOptions = true, description = "Subscribe to upstream skills using metadata only")
    public static final class Track extends Action {
        @Parameters(arity = "1..*") List<String> names;
        @Mixin SourceOptions source;
        @Override int execute() throws IOException {
            for (String name : names) {
                root.manager().collect(name, root.source(source.source), source.ref, true, source.dryRun);
                root.notice(source.dryRun ? "would-track" : "tracked", name);
            }
            return 0;
        }
    }

    @Command(name = "untrack", mixinStandardHelpOptions = true, description = "Remove an upstream subscription; keep installed copies")
    public static final class Untrack extends Action {
        @Parameters(index = "0") String name;
        @Override int execute() throws IOException {
            root.manager().forget(name);
            root.notice("untracked", name);
            return 0;
        }
    }

    @Command(name = "catalog", mixinStandardHelpOptions = true, description = "List local content and upstream subscriptions")
    public static final class Catalog extends Action {
        @Override int execute() throws IOException { root.entries(root.manager().catalog()); return 0; }
    }

    @Command(name = "list-available", mixinStandardHelpOptions = true, description = "Discover skills in your repository or a source")
    public static final class Available extends Action {
        @Option(names = {"--source", "--repo"}) String source;
        @Option(names = "--ref", defaultValue = "HEAD") String ref;
        @Override int execute() throws IOException {
            root.entries(source == null ? root.manager().catalog() : root.manager().available(root.source(source), ref));
            return 0;
        }
    }

    @Command(name = "find", mixinStandardHelpOptions = true, description = "Find skills in your personal catalog")
    public static final class Find extends Action {
        @Parameters(index = "0", defaultValue = "") String query;
        @Override int execute() throws IOException {
            root.entries(root.manager().catalog().stream().filter(entry -> entry.name().contains(query)).toList());
            return 0;
        }
    }

    @Command(name = "use", mixinStandardHelpOptions = true, description = "Print a skill's instructions without installing it")
    public static final class Use extends Action {
        @Parameters(index = "0") String name;
        @Override int execute() throws IOException {
            String content = Files.readString(root.manager().resolve(name).directory().resolve("SKILL.md"));
            if (root.globalOptions.json) root.spec.commandLine().getOut().println(new JsonWriter().beginObject()
                    .name("name").value(name).name("content").value(content).endObject());
            else root.spec.commandLine().getOut().println(content);
            return 0;
        }
    }

    public static final class VersionProvider implements IVersionProvider {
        @Override public @NotNull String[] getVersion() {
            ApplicationInfo info = ApplicationInfo.load();
            return new String[]{info.name() + " " + info.version()};
        }
    }
}
