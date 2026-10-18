package io.github.lihuu.tokenkit.cli;

import java.io.PrintWriter;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
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

            int deletedCount = 0;
            for (String tokenName : names) {
                String cleanName = tokenName.trim();
                boolean deleted = repository.deleteToken(targetProfile, cleanName);
                if (deleted) {
                    out.println(outputStyle.success("Token '" + outputStyle.highlight(cleanName) + "' deleted from profile '" + targetProfile + "'"));
                    deletedCount++;
                } else {
                    err.println(outputStyle.warning("Token '" + cleanName + "' not found in profile '" + targetProfile + "'"));
                }
            }

            return deletedCount > 0 ? 0 : 1;
        } catch (Exception e) {
            err.println(outputStyle.error("Failed to delete token: " + e.getMessage()));
            return 1;
        }
    }
}
