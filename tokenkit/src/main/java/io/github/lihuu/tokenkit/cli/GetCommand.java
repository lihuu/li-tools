package io.github.lihuu.tokenkit.cli;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import io.github.lihuu.tokenkit.core.crypto.CryptoEngine;
import io.github.lihuu.tokenkit.core.key.MasterKeyProvider;
import io.github.lihuu.tokenkit.core.model.Token;
import io.github.lihuu.tokenkit.core.storage.SqliteTokenRepository;
import io.github.lihuu.tokenkit.util.ClipboardUtil;
import org.jetbrains.annotations.NotNull;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "get", description = "Retrieve and decrypt a token", mixinStandardHelpOptions = true)
public final class GetCommand implements Callable<Integer> {
    private final SqliteTokenRepository repository;
    private final MasterKeyProvider keyProvider;
    private final OutputStyle outputStyle;
    private final PrintWriter out;
    private final PrintWriter err;

    @Parameters(index = "0", description = "Token name")
    private String name;

    @Option(names = {"-p", "--profile"}, description = "Target profile (default: active profile)")
    private String profile;

    @Option(names = {"-c", "--clip", "--clipboard"}, description = "Copy to system clipboard instead of printing")
    private boolean clip;

    public GetCommand(
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
            String targetProfile = (profile != null && !profile.trim().isEmpty())
                    ? profile.trim()
                    : repository.getActiveProfileName();

            Optional<Token> tokenOpt = repository.findToken(targetProfile, name.trim());
            if (tokenOpt.isEmpty()) {
                err.println(outputStyle.error("Token '" + outputStyle.highlight(name.trim()) + "' not found in profile '" + targetProfile + "'"));
                return 1;
            }

            Token token = tokenOpt.get();
            byte[] masterKey = keyProvider.getOrCreateMasterKey();
            byte[] decryptedBytes = CryptoEngine.decrypt(token.nonce(), token.ciphertext(), masterKey);
            String secretValue = new String(decryptedBytes, StandardCharsets.UTF_8);

            if (clip) {
                boolean copied = ClipboardUtil.copyToClipboard(secretValue);
                if (copied) {
                    out.println(outputStyle.success("Token '" + outputStyle.highlight(name.trim()) + "' copied to system clipboard"));
                    return 0;
                } else {
                    err.println(outputStyle.warning("Failed to access system clipboard; printing to stdout:"));
                    out.println(secretValue);
                    return 0;
                }
            }

            out.print(secretValue);
            out.flush();
            return 0;
        } catch (Exception e) {
            err.println(outputStyle.error("Failed to decrypt token: " + e.getMessage()));
            return 1;
        }
    }
}
