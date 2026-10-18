package io.github.lihuu.tokenkit.cli;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import io.github.lihuu.tokenkit.core.crypto.CryptoEngine;
import io.github.lihuu.tokenkit.core.key.MasterKeyProvider;
import io.github.lihuu.tokenkit.core.model.Token;
import io.github.lihuu.tokenkit.core.storage.SqliteTokenRepository;
import org.jetbrains.annotations.NotNull;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Unmatched;

@Command(name = "run", description = "Run a command with decrypted tokens injected into its environment", mixinStandardHelpOptions = true)
public final class RunCommand implements Callable<Integer> {
    private final SqliteTokenRepository repository;
    private final MasterKeyProvider keyProvider;
    private final OutputStyle outputStyle;
    private final PrintWriter out;
    private final PrintWriter err;

    @Option(names = {"-p", "--profile"}, description = "Target profile (default: active profile)")
    private String profile;

    @Parameters(index = "0..*", arity = "0..*", description = "Command and arguments to execute")
    private List<String> command = new ArrayList<>();

    @Unmatched
    private List<String> unmatched = new ArrayList<>();

    public RunCommand(
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
        List<String> fullCommand = new ArrayList<>();
        if (command != null) {
            fullCommand.addAll(command);
        }
        if (unmatched != null) {
            fullCommand.addAll(unmatched);
        }

        if (fullCommand.isEmpty()) {
            err.println(outputStyle.error("Please specify a command to run (e.g. tokenkit run -- npm start)"));
            return 2;
        }

        try {
            String targetProfile = (profile != null && !profile.trim().isEmpty())
                    ? profile.trim()
                    : repository.getActiveProfileName();

            List<Token> tokens = repository.findTokensByProfile(targetProfile);
            byte[] masterKey = keyProvider.getOrCreateMasterKey();

            ProcessBuilder processBuilder = new ProcessBuilder(fullCommand);
            Map<String, String> processEnv = processBuilder.environment();

            for (Token token : tokens) {
                byte[] decrypted = CryptoEngine.decrypt(token.nonce(), token.ciphertext(), masterKey);
                String secretValue = new String(decrypted, StandardCharsets.UTF_8);
                processEnv.put(token.name(), secretValue);
            }

            processBuilder.inheritIO();
            Process process = processBuilder.start();
            return process.waitFor();
        } catch (Exception e) {
            err.println(outputStyle.error("Failed to execute command: " + e.getMessage()));
            return 1;
        }
    }
}
