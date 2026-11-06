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
        assertTrue(output.contains(ApplicationInfo.load().version()));
    }

    @Test
    void refusesUnconfirmedDeletionWithoutATerminal() {
        assertEquals(0, execute(new StringWriter(), new StringWriter(), "set", "KEEP", "fixture"));

        StringWriter err = new StringWriter();
        assertEquals(2, execute(new StringWriter(), err, "rm", "KEEP"));
        assertTrue(err.toString().contains("--yes"), err.toString());
        assertEquals(0, execute(new StringWriter(), new StringWriter(), "get", "KEEP"));
    }

    @Test
    void emptyJsonListRemainsAnArray() {
        StringWriter out = new StringWriter();
        assertEquals(0, execute(out, new StringWriter(), "list", "--json"));
        assertEquals("[]", out.toString().strip());
    }

    @Test
    void reportsEachRemovalAndFailsIfAnyRequestedTokenIsMissing() {
        assertEquals(0, execute(new StringWriter(), new StringWriter(), "set", "EXISTS", "fixture"));

        StringWriter out = new StringWriter();
        assertEquals(1, execute(out, new StringWriter(), "rm", "--yes", "--json", "EXISTS", "MISSING"));
        assertTrue(out.toString().contains("\"status\": \"deleted\""), out.toString());
        assertTrue(out.toString().contains("\"status\": \"not_found\""), out.toString());
        assertEquals(1, execute(new StringWriter(), new StringWriter(), "get", "EXISTS"));
    }

    @Test
    void pipedMultilineValuesRoundTripWithTheirLineEndings() {
        String value = "\r\nfirst\r\nsecond\n";
        java.io.InputStream original = System.in;
        try {
            System.setIn(new java.io.ByteArrayInputStream(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertEquals(0, execute(new StringWriter(), new StringWriter(), "set", "MULTILINE"));
        } finally {
            System.setIn(original);
        }

        StringWriter out = new StringWriter();
        assertEquals(0, execute(out, new StringWriter(), "get", "MULTILINE"));
        assertEquals(value, out.toString());
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
