package io.github.lihuu.skillkit.cli;

import io.github.lihuu.clicommon.json.JsonWriter;
import io.github.lihuu.skillkit.core.AgentPaths;
import io.github.lihuu.skillkit.core.SkillManager;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

final class InstallationCommands {
    private InstallationCommands() {}

    static class Execution {
        @Option(names = "--jobs", defaultValue = "4", description = "Maximum parallel skill tasks") int jobs;
        @Option(names = "--dry-run", description = "Show changes without writes or fetching") boolean dryRun;
        void validate() { if (jobs < 1) throw new IllegalArgumentException("--jobs must be positive"); }
    }

    static class Targets {
        @Option(names = {"--agent", "-a"}, split = ",", paramLabel = "<agent>",
                description = "Agent target; repeat or comma-separate (install defaults to shared)")
        List<String> agents = List.of();

        List<Path> resolve(CliCommand root, boolean installing) {
            return agents.isEmpty() && !installing ? AgentPaths.targets(root.home, root.environment)
                    : AgentPaths.targets(root.home, root.environment, agents);
        }
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

    @Command(name = "install", mixinStandardHelpOptions = true, description = "Install skills into shared or selected agent directories")
    public static final class Install extends CliCommand.Action {
        @Parameters(arity = "1..*") List<String> names;
        @Mixin Execution execution;
        @Mixin Targets targets;
        @Option(names = "--repo", description = "External repository or directory; omit to install your own skills") String source;
        @Option(names = "--ref", defaultValue = "HEAD") String ref;
        @Option(names = "--symlink", description = "Link to original local content or versioned upstream cache") boolean symlink;
        @Override int execute() throws IOException {
            execution.validate();
            List<Path> roots = targets.resolve(root, true);
            return report(root, root.installer().install(names, roots, symlink, execution.jobs, execution.dryRun, false,
                    source == null ? null : root.source(source), ref));
        }
    }

    @Command(name = "update", mixinStandardHelpOptions = true, description = "Update installed skills globally for all supported agents")
    public static final class Update extends CliCommand.Action {
        @Parameters(arity = "0..*") List<String> names = List.of();
        @Mixin Execution execution;
        @Mixin Targets targets;
        @Override int execute() throws IOException {
            execution.validate();
            return report(root, root.installer().install(names, targets.resolve(root, false), false, execution.jobs, execution.dryRun, true, null, "HEAD"));
        }
    }

    @Command(name = "uninstall", mixinStandardHelpOptions = true, description = "Uninstall skills globally; keep your original files")
    public static final class Uninstall extends CliCommand.Action {
        @Parameters(arity = "1..*") List<String> names;
        @Option(names = "--dry-run") boolean dryRun;
        @Mixin Targets targets;
        @Override int execute() throws IOException {
            return report(root, root.installer().remove(names, targets.resolve(root, false), dryRun));
        }
    }

    @Command(name = "list", mixinStandardHelpOptions = true, description = "List globally installed skills managed by this repository")
    public static final class ListInstalled extends CliCommand.Action {
        @Mixin Targets targets;
        @Override int execute() throws IOException { return report(root, root.installer().inspect(targets.resolve(root, false))); }
    }

}
