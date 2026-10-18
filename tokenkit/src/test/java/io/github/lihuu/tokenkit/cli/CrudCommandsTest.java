package io.github.lihuu.tokenkit.cli;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Map;
import io.github.lihuu.tokenkit.core.crypto.CryptoEngine;
import io.github.lihuu.tokenkit.core.key.MasterKeyProvider;
import io.github.lihuu.tokenkit.core.storage.SqliteTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrudCommandsTest {

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
    }

    @Test
    void shouldSetAndGetToken() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();

        SetCommand setCmd = new SetCommand(repository, keyProvider, outputStyle, new PrintWriter(out), new PrintWriter(err));
        CommandLine setCl = new CommandLine(setCmd);
        int setExit = setCl.execute("OPENAI_KEY", "demo-secret-value-123", "--desc", "Test key", "--tags", "ai,test");
        assertEquals(0, setExit);
        assertTrue(out.toString().contains("OPENAI_KEY"));

        StringWriter getOut = new StringWriter();
        GetCommand getCmd = new GetCommand(repository, keyProvider, outputStyle, new PrintWriter(getOut), new PrintWriter(new StringWriter()));
        CommandLine getCl = new CommandLine(getCmd);
        int getExit = getCl.execute("OPENAI_KEY");
        assertEquals(0, getExit);
        assertEquals("demo-secret-value-123", getOut.toString().trim());
    }

    @Test
    void shouldFailGetWhenTokenNotFound() {
        StringWriter err = new StringWriter();
        GetCommand getCmd = new GetCommand(repository, keyProvider, outputStyle, new PrintWriter(new StringWriter()), new PrintWriter(err));
        CommandLine getCl = new CommandLine(getCmd);
        int exit = getCl.execute("NON_EXISTENT_TOKEN");
        assertEquals(1, exit);
        assertTrue(err.toString().contains("NON_EXISTENT_TOKEN"));
    }

    @Test
    void shouldListTokensInTableAndFilterByProfile() {
        SetCommand setCmd1 = new SetCommand(repository, keyProvider, outputStyle, new PrintWriter(new StringWriter()), new PrintWriter(new StringWriter()));
        new CommandLine(setCmd1).execute("TOKEN_A", "secret-a-value");

        SetCommand setCmd2 = new SetCommand(repository, keyProvider, outputStyle, new PrintWriter(new StringWriter()), new PrintWriter(new StringWriter()));
        new CommandLine(setCmd2).execute("TOKEN_B", "secret-b-value");

        StringWriter listOut = new StringWriter();
        ListCommand listCmd = new ListCommand(repository, keyProvider, outputStyle, new PrintWriter(listOut), new PrintWriter(new StringWriter()));
        int exit = new CommandLine(listCmd).execute();
        assertEquals(0, exit);
        assertTrue(listOut.toString().contains("TOKEN_A"));
        assertTrue(listOut.toString().contains("TOKEN_B"));
    }

    @Test
    void shouldRemoveToken() throws Exception {
        SetCommand setCmd = new SetCommand(repository, keyProvider, outputStyle, new PrintWriter(new StringWriter()), new PrintWriter(new StringWriter()));
        new CommandLine(setCmd).execute("TO_DELETE", "some-value");

        StringWriter rmOut = new StringWriter();
        RmCommand rmCmd = new RmCommand(repository, outputStyle, new PrintWriter(rmOut), new PrintWriter(new StringWriter()));
        int exit = new CommandLine(rmCmd).execute("TO_DELETE", "-y");
        assertEquals(0, exit);
        assertTrue(rmOut.toString().contains("TO_DELETE"));

        assertFalse(repository.findToken("default", "TO_DELETE").isPresent());
    }
}
