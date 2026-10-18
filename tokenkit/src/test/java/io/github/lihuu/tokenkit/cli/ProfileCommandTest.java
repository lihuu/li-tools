package io.github.lihuu.tokenkit.cli;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import io.github.lihuu.tokenkit.core.crypto.CryptoEngine;
import io.github.lihuu.tokenkit.core.key.MasterKeyProvider;
import io.github.lihuu.tokenkit.core.model.Profile;
import io.github.lihuu.tokenkit.core.storage.SqliteTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileCommandTest {

    private SqliteTokenRepository repository;
    private OutputStyle outputStyle;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        byte[] masterKey = CryptoEngine.generateRandomBytes(32);
        MasterKeyProvider keyProvider = () -> masterKey;
        this.repository = new SqliteTokenRepository(tempDir.resolve("tokens.db"), keyProvider);
        this.repository.initializeSchema();
        this.outputStyle = OutputStyle.plain();
    }

    private CommandLine createProfileCli(StringWriter out, StringWriter err) {
        return ProfileCommand.createCommandLine(
                repository,
                outputStyle,
                new PrintWriter(out, true),
                new PrintWriter(err, true)
        );
    }

    @Test
    void shouldListDefaultProfileAsActiveByDefault() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cl = createProfileCli(out, err);

        int exitCode = cl.execute("list");
        assertEquals(0, exitCode);
        assertTrue(out.toString().contains("* default (active)"));
    }

    @Test
    void shouldCreateNewProfileAndListIt() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cl = createProfileCli(out, err);

        int exitCode = cl.execute("create", "work", "--desc", "Work profile");
        assertEquals(0, exitCode);
        assertTrue(out.toString().contains("Created profile: work"));

        StringWriter listOut = new StringWriter();
        CommandLine listCli = createProfileCli(listOut, new StringWriter());
        listCli.execute("list");
        String listOutput = listOut.toString();
        assertTrue(listOutput.contains("work"));
        assertTrue(listOutput.contains("Work profile"));
    }

    @Test
    void shouldSwitchActiveProfileWithUseCommand() throws Exception {
        repository.saveProfile(new Profile("staging", "Staging env", false, System.currentTimeMillis()));

        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cl = createProfileCli(out, err);

        int exitCode = cl.execute("use", "staging");
        assertEquals(0, exitCode);
        assertTrue(out.toString().contains("Switched to profile: staging"));
        assertEquals("staging", repository.getActiveProfileName());

        StringWriter listOut = new StringWriter();
        CommandLine listCli = createProfileCli(listOut, new StringWriter());
        listCli.execute("list");
        assertTrue(listOut.toString().contains("* staging (active)"));
    }

    @Test
    void shouldFailWhenUsingNonExistentProfile() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cl = createProfileCli(out, err);

        int exitCode = cl.execute("use", "nonexistent");
        assertEquals(1, exitCode);
        assertTrue(err.toString().contains("not found"));
    }

    @Test
    void shouldPreventDeletingDefaultProfile() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cl = createProfileCli(out, err);

        int exitCode = cl.execute("rm", "default");
        assertEquals(1, exitCode);
        assertTrue(err.toString().contains("cannot be deleted"));
    }

    @Test
    void shouldDeleteNonDefaultProfileAndFallbackToDefaultIfActive() throws Exception {
        repository.saveProfile(new Profile("work", "Work env", true, System.currentTimeMillis()));
        assertEquals("work", repository.getActiveProfileName());

        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cl = createProfileCli(out, err);

        int exitCode = cl.execute("rm", "work");
        assertEquals(0, exitCode);
        assertTrue(out.toString().contains("Removed profile: work"));
        assertEquals("default", repository.getActiveProfileName());
    }

    @Test
    void shouldOutputJsonWhenRequested() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cl = createProfileCli(out, err);

        int exitCode = cl.execute("list", "--json");
        assertEquals(0, exitCode);
        String json = out.toString();
        assertTrue(json.contains("\"name\": \"default\""));
        assertTrue(json.contains("\"active\": true"));
    }
}
