package io.github.lihuu.tokenkit.cli;

import io.github.lihuu.clicommon.json.JsonWriter;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import io.github.lihuu.tokenkit.core.crypto.CryptoEngine;
import io.github.lihuu.tokenkit.core.key.MasterKeyProvider;
import io.github.lihuu.tokenkit.core.model.Token;
import io.github.lihuu.tokenkit.core.storage.SqliteTokenRepository;
import org.jetbrains.annotations.NotNull;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "list", description = "List registered tokens", mixinStandardHelpOptions = true)
public final class ListCommand implements Callable<Integer> {
    private final SqliteTokenRepository repository;
    private final MasterKeyProvider keyProvider;
    private final OutputStyle outputStyle;
    private final PrintWriter out;
    private final PrintWriter err;

    @Option(names = {"-p", "--profile"}, description = "Target profile or 'all' (default: active profile)")
    private String profile;

    @Option(names = {"-t", "--tag", "--tags"}, description = "Filter tokens by tag")
    private String tag;

    @Option(names = "--json", description = "Output in JSON format")
    private boolean json;

    @Option(names = "--reveal", description = "Reveal plaintext secret (caution)")
    private boolean reveal;

    public ListCommand(
            @NotNull SqliteTokenRepository repository,
            @NotNull MasterKeyProvider keyProvider,
            @NotNull OutputStyle outputStyle,
            @NotNull PrintWriter out,
            @NotNull PrintWriter err
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.keyProvider = Objects.requireNonNull(keyProvider, "keyProvider must not be null");
        this.outputStyle = Objects.requireNonNull(outputStyle, "outputStyle must not be null");
        this.out = Objects.requireNonNull(out, "out must not be null");
        this.err = Objects.requireNonNull(err, "err must not be null");
    }

    @Override
    public Integer call() {
        try {
            List<Token> tokens;
            if ("all".equalsIgnoreCase(profile)) {
                tokens = repository.findAllTokens();
            } else {
                String targetProfile = (profile != null && !profile.trim().isEmpty())
                        ? profile.trim()
                        : repository.getActiveProfileName();
                tokens = repository.findTokensByProfile(targetProfile);
            }

            if (tag != null && !tag.trim().isEmpty()) {
                String filterTag = tag.trim().toLowerCase();
                tokens = tokens.stream()
                        .filter(t -> t.tags() != null && t.tags().toLowerCase().contains(filterTag))
                        .toList();
            }

            if (tokens.isEmpty() && !json) {
                out.println(outputStyle.muted("No tokens found."));
                return 0;
            }

            if (json) {
                renderJson(tokens);
            } else {
                renderTable(tokens);
            }
            return 0;
        } catch (Exception e) {
            err.println(outputStyle.error("Failed to list tokens: " + e.getMessage()));
            return 1;
        }
    }

    private void renderTable(List<Token> tokens) throws Exception {
        byte[] masterKey = reveal ? keyProvider.getOrCreateMasterKey() : null;

        out.printf("%-12s  %-24s  %-10s  %-20s  %-15s%n",
                outputStyle.bold("PROFILE"),
                outputStyle.bold("NAME"),
                outputStyle.bold("KIND"),
                outputStyle.bold("VALUE"),
                outputStyle.bold("TAGS")
        );
        out.println("─".repeat(88));

        for (Token token : tokens) {
            String valueDisplay;
            if (reveal && masterKey != null) {
                try {
                    byte[] decrypted = CryptoEngine.decrypt(token.nonce(), token.ciphertext(), masterKey);
                    valueDisplay = new String(decrypted, StandardCharsets.UTF_8);
                } catch (Exception e) {
                    valueDisplay = "[decryption failed]";
                }
            } else {
                valueDisplay = Token.mask(token.name() + "•secret");
            }

            out.printf("%-12s  %-24s  %-10s  %-20s  %-15s%n",
                    token.profile(),
                    outputStyle.highlight(token.name()),
                    outputStyle.muted(token.kind()),
                    valueDisplay,
                    token.tags() != null ? token.tags() : ""
            );
        }
    }

    private void renderJson(List<Token> tokens) {
        JsonWriter writer = new JsonWriter().beginArray();
        for (Token token : tokens) {
            writer.beginObject()
                    .name("id").value(token.id())
                    .name("profile").value(token.profile())
                    .name("name").value(token.name())
                    .name("kind").value(token.kind())
                    .name("tags").value(token.tags() != null ? token.tags() : "")
                    .endObject();
        }
        out.println(writer.endArray());
    }
}
