package io.github.lihuu.skillkit.cli;

import io.github.lihuu.clicommon.json.JsonWriter;
import io.github.lihuu.skillkit.core.AgentPaths;
import io.github.lihuu.skillkit.core.SkillManager;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

final class InstallationCommands {
    private InstallationCommands() {}

    static class Scope {
        @Option(names = "--project", description = "Use project scope") boolean project;
        @Option(names = {"-g", "--global"}, description = "Use global scope (default)") boolean global;
    }

    static class Targets {
        @Option(names = {"-a", "--agent"}, split = ",", description = "Agent IDs (comma separated); default detects installed agents")
        List<String> agents = List.of();
        @ArgGroup(exclusive = true) Scope scope;
        @Option(names = "--project-dir", description = "Project directory (default current directory)") Path project;
        @Option(names = "--target-dir", description = "Explicit agent skills directory") Path target;

        List<Path> roots(CliCommand root) {
            if (target != null) {
                if (!agents.isEmpty()) throw new IllegalArgumentException("Choose --agent or --target-dir");
                return List.of(root.workingDirectory.resolve(target).toAbsolutePath().normalize());
            }
            Path projectRoot = project == null ? root.workingDirectory : root.workingDirectory.resolve(project);
            return AgentPaths.targets(agents, scope != null && scope.project, root.home, projectRoot, root.environment);
        }
    }

    static class Execution {
        @Option(names = "--jobs", defaultValue = "4", description = "Maximum parallel skill tasks") int jobs;
        @Option(names = "--dry-run", description = "Show changes without writes or fetching") boolean dryRun;
        void validate() { if (jobs < 1) throw new IllegalArgumentException("--jobs must be positive"); }
    }

    static int report(CliCommand root, List<SkillManager.Item> items) {
        boolean failed = false;
        JsonWriter json = new JsonWriter().beginArray();
        for (SkillManager.Item item : items) {
            failed |= item.status().equals("failed");
            if (root.globalOptions.json) {
                json.beginObject().name("name").value(item.name()).name("target").value(item.target())
                        .name("status").value(item.status()).name("message").value(item.message()).endObject();
            } else if (item.status().equals("failed")) {
                root.spec.commandLine().getErr().println(item.name() + ": " + item.message());
            } else if (!root.globalOptions.quiet || item.status().equals("installed")) {
                root.spec.commandLine().getOut().println(item.name() + "\t" + item.status() + "\t" + item.target());
            }
        }
        if (root.globalOptions.json) root.spec.commandLine().getOut().println(json.endArray());
        return failed ? 1 : 0;
    }

    @Command(name = "add", aliases = "install", mixinStandardHelpOptions = true, description = "Install local or subscribed skills")
    public static final class Install extends CliCommand.Action {
        @Parameters(arity = "1..*") List<String> names;
        @Mixin Targets targets;
        @Mixin Execution execution;
        @Option(names = {"--source", "--repo"}, description = "Subscribe to this source before installing") String source;
        @Option(names = "--ref", defaultValue = "HEAD") String ref;
        @Option(names = {"--symlink", "--link"}, description = "Link to original local content or versioned upstream cache") boolean symlink;
        @Override int execute() throws IOException {
            execution.validate();
            List<Path> roots = targets.roots(root);
            return report(root, root.installer().install(names, roots, symlink, execution.jobs, execution.dryRun, false,
                    source == null ? null : root.source(source), ref));
        }
    }

    @Command(name = "update", mixinStandardHelpOptions = true, description = "Refresh already installed skills; preserve scope and mode")
    public static final class Update extends CliCommand.Action {
        @Parameters(arity = "0..*") List<String> names = List.of();
        @Mixin Targets targets;
        @Mixin Execution execution;
        @Override int execute() throws IOException {
            execution.validate();
            return report(root, root.installer().install(names, targets.roots(root), false, execution.jobs, execution.dryRun, true, null, "HEAD"));
        }
    }

    @Command(name = "remove", aliases = {"rm", "uninstall"}, mixinStandardHelpOptions = true, description = "Remove managed installations; keep repository content")
    public static final class Remove extends CliCommand.Action {
        @Parameters(arity = "1..*") List<String> names;
        @Mixin Targets targets;
        @Option(names = "--dry-run") boolean dryRun;
        @Override int execute() throws IOException {
            return report(root, root.installer().remove(names, targets.roots(root), dryRun));
        }
    }

    @Command(name = "list", aliases = "ls", mixinStandardHelpOptions = true, description = "List installations managed by this repository")
    public static final class ListInstalled extends CliCommand.Action {
        @Mixin Targets targets;
        @Override int execute() throws IOException { return report(root, root.installer().inspect(targets.roots(root), false)); }
    }

    @Command(name = "check", mixinStandardHelpOptions = true, description = "Check installations against current local or upstream content")
    public static final class Check extends CliCommand.Action {
        @Mixin Targets targets;
        @Override int execute() throws IOException { return report(root, root.installer().inspect(targets.roots(root), true)); }
    }
}
