package io.github.lihuu.tokenkit.cli;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import io.github.lihuu.tokenkit.core.crypto.CryptoEngine;
import io.github.lihuu.tokenkit.core.key.MasterKeyProvider;
import io.github.lihuu.tokenkit.core.model.Token;
import io.github.lihuu.tokenkit.core.storage.SqliteTokenRepository;
import io.github.lihuu.tokenkit.util.ShellFormatter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "env", description = "Export all tokens in the profile as shell environment variables", mixinStandardHelpOptions = true)
public final class EnvCommand implements Callable<Integer> {
    private final SqliteTokenRepository repository;
    private final MasterKeyProvider keyProvider;
    private final OutputStyle outputStyle;
    private final PrintWriter out;
    private final PrintWriter err;
    private final String defaultShell;

    @Option(names = {"-p", "--profile"}, description = "Target profile (default: active profile)")
    private String profile;

    @Option(names = {"-s", "--shell"}, description = "Target shell syntax (zsh, bash, pwsh, fish)")
    private String shell;

    public EnvCommand(
            @NotNull SqliteTokenRepository repository,
            @NotNull MasterKeyProvider keyProvider,
            @NotNull OutputStyle outputStyle,
            @NotNull PrintWriter out,
            @NotNull PrintWriter err
    ) {
        this(repository, keyProvider, outputStyle, out, err, null);
    }

    public EnvCommand(
            @NotNull SqliteTokenRepository repository,
            @NotNull MasterKeyProvider keyProvider,
            @NotNull OutputStyle outputStyle,
            @NotNull PrintWriter out,
            @NotNull PrintWriter err,
            @Nullable String defaultShell
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.keyProvider = Objects.requireNonNull(keyProvider, "keyProvider must not be null");
        this.outputStyle = Objects.requireNonNull(outputStyle, "outputStyle must not be null");
        this.out = Objects.requireNonNull(out, "out must not be null");
        this.err = Objects.requireNonNull(err, "err must not be null");
        this.defaultShell = defaultShell;
    }

    @Override
    public Integer call() {
        try {
            String targetProfile = (profile != null && !profile.trim().isEmpty())
                    ? profile.trim()
                    : repository.getActiveProfileName();

            List<Token> tokens = repository.findTokensByProfile(targetProfile);
            if (tokens.isEmpty()) {
                return 0;
            }

            byte[] masterKey = keyProvider.getOrCreateMasterKey();
            String effectiveShell = (shell != null && !shell.trim().isEmpty())
                    ? shell.trim()
                    : (defaultShell != null ? defaultShell : ShellFormatter.detectDefaultShell());

            for (Token token : tokens) {
                byte[] decryptedBytes = CryptoEngine.decrypt(token.nonce(), token.ciphertext(), masterKey);
                String secretValue = new String(decryptedBytes, StandardCharsets.UTF_8);
                String exportStatement = ShellFormatter.formatExport(effectiveShell, token.name(), secretValue);
                out.println(exportStatement);
            }
            return 0;
        } catch (Exception e) {
            err.println(outputStyle.error("Failed to export environment variables: " + e.getMessage()));
            return 1;
        }
    }
}
