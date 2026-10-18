package io.github.lihuu.tokenkit.cli;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import io.github.lihuu.tokenkit.core.crypto.CryptoEngine;
import io.github.lihuu.tokenkit.core.crypto.EncryptedPayload;
import io.github.lihuu.tokenkit.core.key.MasterKeyProvider;
import io.github.lihuu.tokenkit.core.model.Token;
import io.github.lihuu.tokenkit.core.storage.SqliteTokenRepository;
import io.github.lihuu.tokenkit.util.ShellFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvAndRunCommandTest {

    private SqliteTokenRepository repository;
    private MasterKeyProvider keyProvider;
    private OutputStyle outputStyle;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        byte[] masterKey = CryptoEngine.generateRandomBytes(32);
        this.keyProvider = () -> masterKey;
        this.repository = new SqliteTokenRepository(tempDir.resolve("tokens.db"), keyProvider);
        this.repository.initializeSchema();
        this.outputStyle = OutputStyle.plain();

        // Seed two tokens
        byte[] secretA = "secret-val-A".getBytes();
        EncryptedPayload payloadA = CryptoEngine.encrypt(secretA, masterKey);
        repository.saveToken(new Token("id-1", "default", "TOKEN_A", "generic", payloadA.nonce(), payloadA.ciphertext(), null, null, null, 1000L, 1000L));

        byte[] secretB = "secret\"val$B\nline2".getBytes();
        EncryptedPayload payloadB = CryptoEngine.encrypt(secretB, masterKey);
        repository.saveToken(new Token("id-2", "default", "TOKEN_B", "generic", payloadB.nonce(), payloadB.ciphertext(), null, null, null, 1000L, 1000L));
    }

    @Test
    void shellFormatterShouldFormatDifferentShellSyntaxes() {
        String zsh = ShellFormatter.formatExport("zsh", "FOO", "hello\"world$var");
        assertEquals("export FOO=\"hello\\\"world\\$var\"", zsh);

        String pwsh = ShellFormatter.formatExport("pwsh", "FOO", "hello\"world$var");
        assertEquals("$env:FOO = \"hello`\"world`$var\"", pwsh);

        String fish = ShellFormatter.formatExport("fish", "FOO", "simple");
        assertEquals("set -gx FOO \"simple\"", fish);
    }

    @Test
    void envCommandShouldExportZshExportsByDefault() {
        StringWriter out = new StringWriter();
        EnvCommand envCmd = new EnvCommand(repository, keyProvider, outputStyle, new PrintWriter(out), new PrintWriter(new StringWriter()), "zsh");
        CommandLine cl = new CommandLine(envCmd);
        int exit = cl.execute();

        assertEquals(0, exit);
        String output = out.toString();
        assertTrue(output.contains("export TOKEN_A=\"secret-val-A\""));
        assertTrue(output.contains("export TOKEN_B="));
    }

    @Test
    void envCommandShouldExportPowerShellWhenRequested() {
        StringWriter out = new StringWriter();
        EnvCommand envCmd = new EnvCommand(repository, keyProvider, outputStyle, new PrintWriter(out), new PrintWriter(new StringWriter()), "zsh");
        CommandLine cl = new CommandLine(envCmd);
        int exit = cl.execute("--shell", "pwsh");

        assertEquals(0, exit);
        String output = out.toString();
        assertTrue(output.contains("$env:TOKEN_A = \"secret-val-A\""));
    }

    @Test
    void runCommandShouldInjectEnvironmentIntoSubprocess() {
        RunCommand runCmd = new RunCommand(repository, keyProvider, outputStyle, new PrintWriter(new StringWriter()), new PrintWriter(new StringWriter()));
        CommandLine cl = new CommandLine(runCmd);

        // Run 'tokenkit run -- sh -c ...' as specified in CLI contract
        int exit = cl.execute("--", "sh", "-c", "test \"$TOKEN_A\" = \"secret-val-A\"");
        assertEquals(0, exit);
    }
}
