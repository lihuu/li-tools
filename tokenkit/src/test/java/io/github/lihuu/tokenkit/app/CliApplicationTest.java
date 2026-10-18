package io.github.lihuu.tokenkit.app;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import io.github.lihuu.tokenkit.core.crypto.CryptoEngine;
import io.github.lihuu.tokenkit.core.key.MasterKeyProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliApplicationTest {

    private Path dbPath;
    private MasterKeyProvider keyProvider;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        this.dbPath = tempDir.resolve("cli-test.db");
        byte[] masterKey = CryptoEngine.generateRandomBytes(32);
        this.keyProvider = () -> masterKey;
    }

    private int execute(StringWriter out, StringWriter err, String... args) {
        return CliApplication.run(
                args,
                dbPath,
                keyProvider,
                new PrintWriter(out, true),
                new PrintWriter(err, true)
        );
    }

    @Test
    void shouldDisplayHelp() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exitCode = execute(out, err, "--help");

        assertEquals(0, exitCode);
        String output = out.toString();
        assertTrue(output.contains("tokenkit"));
        assertTrue(output.contains("Usage:"));
    }

    @Test
    void shouldDisplayVersion() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exitCode = execute(out, err, "--version");

        assertEquals(0, exitCode);
        String output = out.toString();
        assertTrue(output.contains("0.1.0"));
    }

    @Test
    void shouldPerformCompleteWorkflowViaCliApplication() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();

        // 1. Set token
        int setExit = execute(out, err, "set", "DEMO_KEY", "demo-secret-value");
        assertEquals(0, setExit);
        assertTrue(out.toString().contains("DEMO_KEY"));

        // 2. Get token
        StringWriter getOut = new StringWriter();
        int getExit = execute(getOut, new StringWriter(), "get", "DEMO_KEY");
        assertEquals(0, getExit);
        assertEquals("demo-secret-value", getOut.toString());

        // 3. List tokens
        StringWriter listOut = new StringWriter();
        int listExit = execute(listOut, new StringWriter(), "list");
        assertEquals(0, listExit);
        assertTrue(listOut.toString().contains("DEMO_KEY"));
        // Confirm secret is masked in listing
        assertTrue(!listOut.toString().contains("demo-secret-value"));

        // 4. Create and switch profile
        execute(new StringWriter(), new StringWriter(), "profile", "create", "testenv");
        execute(new StringWriter(), new StringWriter(), "profile", "use", "testenv");

        // 5. Set token in testenv profile
        execute(new StringWriter(), new StringWriter(), "set", "ENV_SPECIFIC", "env-value");

        // 6. Env export in testenv profile
        StringWriter envOut = new StringWriter();
        int envExit = execute(envOut, new StringWriter(), "env", "--shell", "zsh");
        assertEquals(0, envExit);
        assertTrue(envOut.toString().contains("export ENV_SPECIFIC=\"env-value\""));

        // 7. Rm token
        StringWriter rmOut = new StringWriter();
        int rmExit = execute(rmOut, new StringWriter(), "rm", "ENV_SPECIFIC", "-y");
        assertEquals(0, rmExit);
        assertTrue(rmOut.toString().contains("deleted from profile"));
    }
}
