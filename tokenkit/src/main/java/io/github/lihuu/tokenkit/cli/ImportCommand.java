package io.github.lihuu.tokenkit.cli;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import io.github.lihuu.tokenkit.core.crypto.CryptoEngine;
import io.github.lihuu.tokenkit.core.crypto.EncryptedPayload;
import io.github.lihuu.tokenkit.core.key.MasterKeyProvider;
import io.github.lihuu.tokenkit.core.migration.MigrationBundle;
import io.github.lihuu.tokenkit.core.migration.MigrationEngine;
import io.github.lihuu.tokenkit.core.model.Profile;
import io.github.lihuu.tokenkit.core.model.Token;
import io.github.lihuu.tokenkit.core.storage.SqliteTokenRepository;
import org.jetbrains.annotations.NotNull;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "import", description = "Import tokens from an export bundle or JSON file", mixinStandardHelpOptions = true)
public final class ImportCommand implements Callable<Integer> {
    private final SqliteTokenRepository repository;
    private final MasterKeyProvider keyProvider;
    private final OutputStyle outputStyle;
    private final PrintWriter out;
    private final PrintWriter err;

    @Parameters(index = "0", description = "File to import (.tkbundle or .json)")
    private String file;

    @Option(names = {"--passphrase"}, description = "Passphrase to decrypt bundle", interactive = false)
    private String passphrase;

    @Option(names = {"--overwrite", "-f"}, description = "Overwrite existing tokens with same name")
    private boolean overwrite;

    public ImportCommand(
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
        if (file == null || file.trim().isEmpty()) {
            err.println(outputStyle.error("Please specify a file to import"));
            return 2;
        }

        Path filePath = Path.of(file.trim());
        if (!Files.exists(filePath)) {
            err.println(outputStyle.error("File not found: " + filePath));
            return 1;
        }

        try {
            byte[] fileBytes = Files.readAllBytes(filePath);
            MigrationBundle bundle;

            if (MigrationEngine.isEncryptedBundle(fileBytes)) {
                char[] passChars = resolvePassphrase();
                if (passChars == null || passChars.length == 0) {
                    err.println(outputStyle.error("Passphrase is required to decrypt bundle (use --passphrase)"));
                    return 2;
                }
                try {
                    bundle = MigrationEngine.unpackBundle(fileBytes, passChars);
                } finally {
                    Arrays.fill(passChars, '\0');
                }
            } else {
                String json = new String(fileBytes, StandardCharsets.UTF_8);
                bundle = MigrationEngine.fromJson(json);
            }

            byte[] masterKey = keyProvider.getOrCreateMasterKey();
            List<String> added = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            List<String> overwritten = new ArrayList<>();

            for (MigrationBundle.ExportedToken t : bundle.tokens()) {
                String targetProfile = (t.profile() != null && !t.profile().isEmpty()) ? t.profile() : "default";

                if (repository.findProfile(targetProfile).isEmpty()) {
                    repository.saveProfile(new Profile(targetProfile, null, false, System.currentTimeMillis()));
                }

                Optional<Token> existing = repository.findToken(targetProfile, t.name());
                if (existing.isPresent()) {
                    if (!overwrite) {
                        skipped.add(t.name());
                    } else {
                        EncryptedPayload payload = CryptoEngine.encrypt(t.value().getBytes(StandardCharsets.UTF_8), masterKey);
                        Token updated = new Token(
                                existing.get().id(),
                                targetProfile,
                                t.name(),
                                t.kind() != null ? t.kind() : "generic",
                                payload.nonce(),
                                payload.ciphertext(),
                                t.description(),
                                t.tags(),
                                t.expiresAt(),
                                existing.get().createdAt(),
                                System.currentTimeMillis()
                        );
                        repository.saveToken(updated);
                        overwritten.add(t.name());
                    }
                } else {
                    EncryptedPayload payload = CryptoEngine.encrypt(t.value().getBytes(StandardCharsets.UTF_8), masterKey);
                    Token inserted = new Token(
                            UUID.randomUUID().toString(),
                            targetProfile,
                            t.name(),
                            t.kind() != null ? t.kind() : "generic",
                            payload.nonce(),
                            payload.ciphertext(),
                            t.description(),
                            t.tags(),
                            t.expiresAt(),
                            t.createdAt() > 0 ? t.createdAt() : System.currentTimeMillis(),
                            System.currentTimeMillis()
                    );
                    repository.saveToken(inserted);
                    added.add(t.name());
                }
            }

            out.println(outputStyle.success("导入完成！(共处理 " + bundle.tokens().size() + " 个 Token)"));
            if (!added.isEmpty()) {
                out.println(outputStyle.success("  • 新增导入: " + added.size() + " 个 (" + String.join(", ", added) + ")"));
            } else {
                out.println(outputStyle.muted("  • 新增导入: 0 个"));
            }

            if (!skipped.isEmpty()) {
                out.println(outputStyle.warning("  • 冲突跳过: " + skipped.size() + " 个 [已存在] (" + String.join(", ", skipped) + ")"));
            } else {
                out.println(outputStyle.muted("  • 冲突跳过: 0 个"));
            }

            if (!overwritten.isEmpty()) {
                out.println(outputStyle.highlight("  • 覆盖更新: " + overwritten.size() + " 个 (" + String.join(", ", overwritten) + ")"));
            } else {
                out.println(outputStyle.muted("  • 覆盖更新: 0 个"));
            }

            return 0;
        } catch (Exception e) {
            err.println(outputStyle.error("Import failed: " + e.getMessage()));
            return 1;
        }
    }

    private char[] resolvePassphrase() {
        if (passphrase != null && !passphrase.isEmpty()) {
            return passphrase.toCharArray();
        }
        if (System.console() != null) {
            return System.console().readPassword("Enter passphrase to decrypt bundle: ");
        }
        return null;
    }
}
