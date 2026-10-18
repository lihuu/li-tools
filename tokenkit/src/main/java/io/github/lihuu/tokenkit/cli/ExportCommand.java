package io.github.lihuu.tokenkit.cli;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import io.github.lihuu.tokenkit.core.crypto.CryptoEngine;
import io.github.lihuu.tokenkit.core.key.MasterKeyProvider;
import io.github.lihuu.tokenkit.core.migration.MigrationBundle;
import io.github.lihuu.tokenkit.core.migration.MigrationEngine;
import io.github.lihuu.tokenkit.core.model.Token;
import io.github.lihuu.tokenkit.core.storage.SqliteTokenRepository;
import org.jetbrains.annotations.NotNull;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "export", description = "Export tokens to an encrypted bundle or plain JSON", mixinStandardHelpOptions = true)
public final class ExportCommand implements Callable<Integer> {
    private final SqliteTokenRepository repository;
    private final MasterKeyProvider keyProvider;
    private final OutputStyle outputStyle;
    private final PrintWriter out;
    private final PrintWriter err;

    @Option(names = {"-p", "--profile"}, description = "Target profile (default: active profile)")
    private String profile;

    @Option(names = {"--all"}, description = "Export all profiles")
    private boolean all;

    @Option(names = {"-o", "--out"}, description = "Output destination path")
    private String destination;

    @Option(names = {"--passphrase"}, description = "Passphrase to encrypt the export bundle", interactive = false)
    private String passphrase;

    @Option(names = {"--unencrypted"}, description = "Export as plain unencrypted JSON")
    private boolean unencrypted;

    public ExportCommand(
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
            if (all) {
                tokens = repository.findAllTokens();
            } else {
                String targetProfile = (profile != null && !profile.trim().isEmpty())
                        ? profile.trim()
                        : repository.getActiveProfileName();
                tokens = repository.findTokensByProfile(targetProfile);
            }

            if (tokens.isEmpty()) {
                out.println(outputStyle.warning("No tokens found to export."));
                return 0;
            }

            byte[] masterKey = keyProvider.getOrCreateMasterKey();
            List<MigrationBundle.ExportedToken> exportedTokens = new ArrayList<>();
            for (Token token : tokens) {
                byte[] decrypted = CryptoEngine.decrypt(token.nonce(), token.ciphertext(), masterKey);
                String secret = new String(decrypted, StandardCharsets.UTF_8);
                exportedTokens.add(new MigrationBundle.ExportedToken(
                        token.profile(),
                        token.name(),
                        token.kind(),
                        secret,
                        token.description(),
                        token.tags(),
                        token.expiresAt(),
                        token.createdAt(),
                        token.updatedAt()
                ));
            }

            MigrationBundle bundle = new MigrationBundle(1, System.currentTimeMillis(), exportedTokens);

            Path targetPath;
            if (destination != null && !destination.trim().isEmpty()) {
                targetPath = Path.of(destination.trim());
            } else {
                String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
                String filename = unencrypted
                        ? "tokenkit-export-" + timestamp + ".json"
                        : "tokenkit-export-" + timestamp + ".tkbundle";
                targetPath = Path.of(".", filename);
            }

            Path parentDir = targetPath.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }

            if (unencrypted) {
                String json = MigrationEngine.toJson(bundle);
                Files.writeString(targetPath, json, StandardCharsets.UTF_8);
            } else {
                char[] passChars = resolvePassphrase();
                if (passChars == null || passChars.length == 0) {
                    err.println(outputStyle.error("Passphrase is required for encrypted export (use --passphrase)"));
                    return 2;
                }
                try {
                    byte[] packed = MigrationEngine.packBundle(bundle, passChars);
                    Files.write(targetPath, packed);
                } finally {
                    Arrays.fill(passChars, '\0');
                }
            }

            out.println(outputStyle.success("成功导出 " + exportedTokens.size() + " 个 Token" + (unencrypted ? " (明文 JSON)!" : " (加密迁移包)!")));
            out.println("  文件位置: " + outputStyle.highlight(targetPath.toString()));
            return 0;
        } catch (Exception e) {
            err.println(outputStyle.error("Export failed: " + e.getMessage()));
            return 1;
        }
    }

    private char[] resolvePassphrase() {
        if (passphrase != null && !passphrase.isEmpty()) {
            return passphrase.toCharArray();
        }
        if (System.console() != null) {
            char[] p1 = System.console().readPassword("Enter passphrase to encrypt export bundle: ");
            char[] p2 = System.console().readPassword("Confirm passphrase: ");
            if (!Arrays.equals(p1, p2)) {
                err.println(outputStyle.error("Passphrases do not match."));
                return null;
            }
            return p1;
        }
        return null;
    }
}
