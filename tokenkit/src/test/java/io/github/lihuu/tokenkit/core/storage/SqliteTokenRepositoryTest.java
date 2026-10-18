package io.github.lihuu.tokenkit.core.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Optional;
import io.github.lihuu.tokenkit.core.crypto.CryptoEngine;
import io.github.lihuu.tokenkit.core.crypto.EncryptedPayload;
import io.github.lihuu.tokenkit.core.model.Profile;
import io.github.lihuu.tokenkit.core.model.Token;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteTokenRepositoryTest {

    @Test
    void shouldInitializeSchemaAndCreateDefaultProfile(@TempDir Path tempDir) throws Exception {
        Path dbPath = tempDir.resolve("tokens.db");
        byte[] masterKey = CryptoEngine.generateRandomBytes(32);
        SqliteTokenRepository repo = new SqliteTokenRepository(dbPath, () -> masterKey);

        repo.initializeSchema();
        assertTrue(Files.exists(dbPath));

        List<Profile> profiles = repo.listProfiles();
        assertEquals(1, profiles.size());
        assertEquals("default", profiles.getFirst().name());
        assertTrue(profiles.getFirst().active());
        assertEquals("default", repo.getActiveProfileName());
    }

    @Test
    void shouldPerformTokenCrudOperations(@TempDir Path tempDir) throws Exception {
        Path dbPath = tempDir.resolve("tokens.db");
        byte[] masterKey = CryptoEngine.generateRandomBytes(32);
        SqliteTokenRepository repo = new SqliteTokenRepository(dbPath, () -> masterKey);
        repo.initializeSchema();

        byte[] nonce = CryptoEngine.generateRandomBytes(12);
        byte[] ciphertext = "encrypted-payload-data".getBytes();
        Token token = new Token(
                "id-123",
                "default",
                "OPENAI_API_KEY",
                "generic",
                nonce,
                ciphertext,
                "API Key",
                "ai,dev",
                null,
                1700000000000L,
                1700000000000L
        );

        repo.saveToken(token);

        Optional<Token> found = repo.findToken("default", "OPENAI_API_KEY");
        assertTrue(found.isPresent());
        assertEquals("id-123", found.get().id());
        assertEquals("OPENAI_API_KEY", found.get().name());
        assertArrayEquals(nonce, found.get().nonce());
        assertArrayEquals(ciphertext, found.get().ciphertext());

        List<Token> defaultTokens = repo.findTokensByProfile("default");
        assertEquals(1, defaultTokens.size());

        boolean deleted = repo.deleteToken("default", "OPENAI_API_KEY");
        assertTrue(deleted);
        assertFalse(repo.findToken("default", "OPENAI_API_KEY").isPresent());
    }

    @Test
    void shouldManageProfilesAndCascadeDelete(@TempDir Path tempDir) throws Exception {
        Path dbPath = tempDir.resolve("tokens.db");
        byte[] masterKey = CryptoEngine.generateRandomBytes(32);
        SqliteTokenRepository repo = new SqliteTokenRepository(dbPath, () -> masterKey);
        repo.initializeSchema();

        repo.saveProfile(new Profile("work", "Work team", false, 1700000000000L));
        assertEquals(2, repo.listProfiles().size());

        // Switch active profile
        assertTrue(repo.setActiveProfile("work"));
        assertEquals("work", repo.getActiveProfileName());

        // Save token in work profile
        Token token = new Token(
                "id-work-1",
                "work",
                "WORK_TOKEN",
                "generic",
                new byte[12],
                new byte[16],
                null,
                null,
                null,
                1000L,
                1000L
        );
        repo.saveToken(token);
        assertEquals(1, repo.findTokensByProfile("work").size());

        // Delete profile cascades tokens
        assertTrue(repo.deleteProfile("work"));
        assertFalse(repo.findProfile("work").isPresent());
        assertEquals(0, repo.findTokensByProfile("work").size());
    }

    @Test
    void shouldPerformOnlineBackup(@TempDir Path tempDir) throws Exception {
        Path dbPath = tempDir.resolve("tokens.db");
        Path backupPath = tempDir.resolve("backup.db");
        byte[] masterKey = CryptoEngine.generateRandomBytes(32);
        SqliteTokenRepository repo = new SqliteTokenRepository(dbPath, () -> masterKey);
        repo.initializeSchema();

        repo.saveToken(new Token(
                "id-bak-1",
                "default",
                "BAK_TOKEN",
                "generic",
                new byte[12],
                new byte[16],
                null,
                null,
                null,
                1000L,
                1000L
        ));

        repo.backupTo(backupPath);
        assertTrue(Files.exists(backupPath));

        // Read backup via fresh repo instance
        SqliteTokenRepository backupRepo = new SqliteTokenRepository(backupPath, () -> masterKey);
        assertTrue(backupRepo.findToken("default", "BAK_TOKEN").isPresent());
    }
}
