package io.github.lihuu.skillkit.cli;

import io.github.lihuu.clicommon.json.JsonWriter;
import io.github.lihuu.skillkit.core.RepositoryPublisher;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;

@Command(name = "push", mixinStandardHelpOptions = true, description = "Commit your skills and source metadata, then publish to your remote repository")
public final class PushCommand extends CliCommand.Action {
    @Option(names = "--repo", description = "Publishing repository, saved as origin; omit after first push") String repository;
    @Option(names = {"-m", "--message"}, defaultValue = "chore(skills): publish skills") String message;
    @Option(names = "--dry-run", description = "Show the publication without committing or pushing") boolean dryRun;

    @Override int execute() throws IOException {
        if (repository != null && repository.isBlank()) throw new IllegalArgumentException("Publishing repository must not be blank");
        RepositoryPublisher publisher = new RepositoryPublisher(root.manager().store().root(), root.environment);
        RepositoryPublisher.Result result = publisher.push(repository == null ? null : root.source(repository), message, dryRun);
        if (root.globalOptions.json) {
            root.spec.commandLine().getOut().println(new JsonWriter().beginObject()
                    .name("status").value(result.status()).name("branch").value(result.branch())
                    .name("revision").value(result.revision()).name("committed").value(result.committed()).endObject());
        } else {
            root.spec.commandLine().getOut().println(result.status() + "\t" + result.branch() + "\t" + result.revision());
        }
        return 0;
    }
}
