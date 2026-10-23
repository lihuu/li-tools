package io.github.lihuu.skillkit.cli;

import io.github.lihuu.clicommon.json.JsonWriter;
import io.github.lihuu.skillkit.core.RepositorySynchronizer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;

@Command(name = "pull", mixinStandardHelpOptions = true, description = "Clone your personal repository, or pull fast-forward updates")
public final class PullCommand extends CliCommand.Action {
    @Option(names = "--repo", description = "Personal Git repository; required for the first clone") String repository;
    @Option(names = "--dry-run", description = "Show the operation without writes or network access") boolean dryRun;

    @Override int execute() throws IOException {
        if (repository != null && repository.isBlank()) throw new IllegalArgumentException("Repository must not be blank");
        RepositorySynchronizer synchronizer = new RepositorySynchronizer(root.manager().store().root(), root.environment);
        RepositorySynchronizer.Result result = synchronizer.pull(repository == null ? null : root.source(repository), dryRun);
        if (root.globalOptions.json) {
            root.spec.commandLine().getOut().println(new JsonWriter().beginObject().name("status").value(result.status())
                    .name("repository").value(root.manager().store().root().toString()).name("revision").value(result.revision()).endObject());
        } else if (!root.globalOptions.quiet) {
            root.spec.commandLine().getOut().println(result.status() + "\t" + root.manager().store().root());
        }
        return 0;
    }
}
