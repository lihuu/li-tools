package io.github.lihuu.tokenkit.cli;

import java.io.Console;
import java.io.PrintWriter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import io.github.lihuu.clicommon.json.JsonWriter;
import io.github.lihuu.tokenkit.core.storage.SqliteTokenRepository;
import org.jetbrains.annotations.NotNull;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "rm", description = "Remove one or more tokens", mixinStandardHelpOptions = true)
public final class RmCommand implements Callable<Integer> {
    private final SqliteTokenRepository repository;
    private final OutputStyle outputStyle;
    private final PrintWriter out;
    private final PrintWriter err;

    @Parameters(index = "0..*", arity = "1..*", description = "Token name(s) to delete")
    private List<String> names;

    @Option(names = {"-p", "--profile"}, description = "Target profile (default: active profile)")
    private String profile;

    @Option(names = {"-y", "--yes"}, description = "Confirm deletion without prompt")
    private boolean skipConfirm;

    @Option(names = "--json", description = "Output per-token deletion results as JSON")
    private boolean json;

    public RmCommand(
            @NotNull SqliteTokenRepository repository,
            @NotNull OutputStyle outputStyle,
            @NotNull PrintWriter out,
            @NotNull PrintWriter err
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.outputStyle = Objects.requireNonNull(outputStyle, "outputStyle must not be null");
        this.out = Objects.requireNonNull(out, "out must not be null");
        this.err = Objects.requireNonNull(err, "err must not be null");
    }

    @Override
    public Integer call() {
        if (names == null || names.isEmpty()) {
            err.println(outputStyle.error("Please specify at least one token name to delete"));
            return 2;
        }

        try {
            String targetProfile = (profile != null && !profile.trim().isEmpty())
                    ? profile.trim()
                    : repository.getActiveProfileName();

            LinkedHashSet<String> requested = new LinkedHashSet<>();
            for (String name : names) {
                requested.add(name.trim());
            }
            if (!skipConfirm) {
                Console console = System.console();
                if (console == null || !console.isTerminal()) {
                    err.println(outputStyle.error("Deletion requires --yes without an interactive terminal"));
                    return 2;
                }
                err.printf("Delete %s from profile '%s'? [y/N] ", String.join(", ", requested), targetProfile);
                err.flush();
                String answer = console.readLine();
                if (!"y".equalsIgnoreCase(answer) && !"yes".equalsIgnoreCase(answer)) {
                    err.println("Deletion cancelled");
                    return 1;
                }
            }

            JsonWriter results = json ? new JsonWriter().beginArray() : null;
            boolean allDeleted = true;
            for (String name : requested) {
                boolean deleted = repository.deleteToken(targetProfile, name);
                allDeleted &= deleted;
                if (results != null) {
                    results.beginObject().name("profile").value(targetProfile).name("name").value(name)
                            .name("status").value(deleted ? "deleted" : "not_found").endObject();
                } else if (deleted) {
                    out.println(outputStyle.success("Token '" + outputStyle.highlight(name) + "' deleted from profile '" + targetProfile + "'"));
                } else {
                    err.println(outputStyle.warning("Token '" + name + "' not found in profile '" + targetProfile + "'"));
                }
            }

            if (results != null) {
                out.println(results.endArray());
            }
            return allDeleted ? 0 : 1;
        } catch (Exception e) {
            err.println(outputStyle.error("Failed to delete token: " + e.getMessage()));
            return 1;
        }
    }
}
