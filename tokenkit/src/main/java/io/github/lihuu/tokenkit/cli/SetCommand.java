package io.github.lihuu.tokenkit.cli;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import io.github.lihuu.tokenkit.core.crypto.CryptoEngine;
import io.github.lihuu.tokenkit.core.crypto.EncryptedPayload;
import io.github.lihuu.tokenkit.core.key.MasterKeyProvider;
import io.github.lihuu.tokenkit.core.model.Token;
import io.github.lihuu.tokenkit.core.storage.SqliteTokenRepository;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "set", description = "Store or update an encrypted token", mixinStandardHelpOptions = true)
public final class SetCommand implements Callable<Integer> {
    private final SqliteTokenRepository repository;
    private final MasterKeyProvider keyProvider;
    private final OutputStyle outputStyle;
    private final PrintWriter out;
    private final PrintWriter err;

    @Parameters(index = "0", description = "Token name (e.g. OPENAI_API_KEY)")
    private String name;

    @Parameters(index = "1", arity = "0..1", description = "Token value (masked prompt or pipe if omitted)")
    private String value;

    @Option(names = {"-p", "--profile"}, description = "Target profile (default: active profile)")
    private String profile;

    @Option(names = {"-d", "--desc", "--description"}, description = "Description notes")
    private String description;

    @Option(names = {"-t", "--tag", "--tags"}, description = "Comma-separated tags")
    private String tags;

    @Option(names = {"-k", "--kind"}, defaultValue = "generic", description = "Token kind (generic, api_key, pem, composite)")
    private String kind = "generic";

    public SetCommand(
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
            String tokenValue = resolveTokenValue();
            if (tokenValue == null || tokenValue.isEmpty()) {
                err.println(outputStyle.error("Token value cannot be empty"));
                return 1;
            }

            String targetProfile = (profile != null && !profile.trim().isEmpty())
                    ? profile.trim()
                    : repository.getActiveProfileName();

            byte[] masterKey = keyProvider.getOrCreateMasterKey();
            byte[] secretBytes = tokenValue.getBytes(StandardCharsets.UTF_8);
            EncryptedPayload payload = CryptoEngine.encrypt(secretBytes, masterKey);

            long now = System.currentTimeMillis();
            String tokenId = UUID.randomUUID().toString();
            Token token = new Token(
                    tokenId,
                    targetProfile,
                    name.trim(),
                    kind,
                    payload.nonce(),
                    payload.ciphertext(),
                    description,
                    tags,
                    null,
                    now,
                    now
            );

            repository.saveToken(token);
            out.println(outputStyle.success("Token '" + outputStyle.highlight(name.trim()) + "' saved in profile '" + outputStyle.highlight(targetProfile) + "'"));
            return 0;
        } catch (Exception e) {
            err.println(outputStyle.error("Failed to save token: " + e.getMessage()));
            return 1;
        }
    }

    private @Nullable String resolveTokenValue() throws Exception {
        if (value != null) {
            return value;
        }
        if (System.console() != null) {
            char[] passwordChars = System.console().readPassword("Enter token value for '%s': ", name);
            if (passwordChars != null) {
                return new String(passwordChars);
            }
            return null;
        }
        // Read from standard in (pipe)
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!sb.isEmpty()) {
                    sb.append("\n");
                }
                sb.append(line);
            }
            return sb.toString();
        }
    }
}
