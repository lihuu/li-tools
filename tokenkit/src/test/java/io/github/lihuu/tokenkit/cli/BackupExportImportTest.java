package io.github.lihuu.tokenkit.cli;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import io.github.lihuu.tokenkit.core.crypto.CryptoEngine;
import io.github.lihuu.tokenkit.core.crypto.EncryptedPayload;
import io.github.lihuu.tokenkit.core.key.MasterKeyProvider;
import io.github.lihuu.tokenkit.core.model.Token;
import io.github.lihuu.tokenkit.core.storage.SqliteTokenRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupExportImportTest {

    private SqliteTokenRepository sourceRepo;
    private MasterKeyProvider sourceKeyProvider;
    private byte[] sourceMasterKey;
    private Path sourceDbPath;
    private OutputStyle outputStyle;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        this.sourceMasterKey = CryptoEngine.generateRandomBytes(32);
        this.sourceKeyProvider = () -> sourceMasterKey;
        this.sourceDbPath = tempDir.resolve("source.db");
        this.sourceRepo = new SqliteTokenRepository(sourceDbPath, sourceKeyProvider);
        this.sourceRepo.initializeSchema();
        this.outputStyle = OutputStyle.plain();

        // Seed source tokens
        EncryptedPayload p1 = CryptoEngine.encrypt("secret-alpha".getBytes(StandardCharsets.UTF_8), sourceMasterKey);
        sourceRepo.saveToken(new Token("id-1", "default", "KEY_A", "generic", p1.nonce(), p1.ciphertext(), "first key", "work", null, 1000L, 1000L));

        EncryptedPayload p2 = CryptoEngine.encrypt("secret-beta".getBytes(StandardCharsets.UTF_8), sourceMasterKey);
        sourceRepo.saveToken(new Token("id-2", "default", "KEY_B", "generic", p2.nonce(), p2.ciphertext(), "second key", "infra", null, 1000L, 1000L));
    }

    @AfterEach
    void tearDown() {
        if (sourceRepo != null) {
            sourceRepo.close();
        }
    }

    @Test
    void shouldExportEncryptedBundleAndImportWithConflictResolution(@TempDir Path tempDir) throws Exception {
        Path bundlePath = tempDir.resolve("export.tkbundle");

        // 1. Export from source
        StringWriter exportOut = new StringWriter();
        ExportCommand exportCmd = new ExportCommand(
                sourceRepo,
                sourceKeyProvider,
                outputStyle,
                new PrintWriter(exportOut, true),
                new PrintWriter(new StringWriter(), true)
        );
        CommandLine exportCli = new CommandLine(exportCmd);
        int exportExit = exportCli.execute("--out", bundlePath.toString(), "--passphrase", "testpass123");
        assertEquals(0, exportExit);
        assertTrue(Files.exists(bundlePath));
        assertTrue(exportOut.toString().contains("export.tkbundle"));

        // 2. Set up target repo on a different simulated machine with different master key
        byte[] targetMasterKey = CryptoEngine.generateRandomBytes(32);
        MasterKeyProvider targetKeyProvider = () -> targetMasterKey;
        Path targetDbPath = tempDir.resolve("target.db");
        try (SqliteTokenRepository targetRepo = new SqliteTokenRepository(targetDbPath, targetKeyProvider)) {
            targetRepo.initializeSchema();

            // Target already has KEY_A with existing value
            EncryptedPayload existingPayload = CryptoEngine.encrypt("target-original".getBytes(StandardCharsets.UTF_8), targetMasterKey);
            targetRepo.saveToken(new Token("target-id", "default", "KEY_A", "generic", existingPayload.nonce(), existingPayload.ciphertext(), "orig", null, null, 2000L, 2000L));

            // 3. Import with default --skip behavior
            StringWriter importOut = new StringWriter();
            ImportCommand importCmd = new ImportCommand(
                    targetRepo,
                    targetKeyProvider,
                    outputStyle,
                    new PrintWriter(importOut, true),
                    new PrintWriter(new StringWriter(), true)
            );
            CommandLine importCli = new CommandLine(importCmd);
            int importExit = importCli.execute(bundlePath.toString(), "--passphrase", "testpass123");
            assertEquals(0, importExit);

            String importOutput = importOut.toString();
            assertTrue(importOutput.contains("冲突跳过: 1") || importOutput.contains("跳过: 1"));
            assertTrue(importOutput.contains("新增导入: 1"));

            // Verify KEY_A kept original value
            Token tokenA = targetRepo.findToken("default", "KEY_A").orElseThrow();
            byte[] decA = CryptoEngine.decrypt(tokenA.nonce(), tokenA.ciphertext(), targetMasterKey);
            assertEquals("target-original", new String(decA, StandardCharsets.UTF_8));

            // Verify KEY_B imported with secret-beta
            Token tokenB = targetRepo.findToken("default", "KEY_B").orElseThrow();
            byte[] decB = CryptoEngine.decrypt(tokenB.nonce(), tokenB.ciphertext(), targetMasterKey);
            assertEquals("secret-beta", new String(decB, StandardCharsets.UTF_8));

            // 4. Import with --overwrite
            StringWriter overwriteOut = new StringWriter();
            ImportCommand overwriteCmd = new ImportCommand(
                    targetRepo,
                    targetKeyProvider,
                    outputStyle,
                    new PrintWriter(overwriteOut, true),
                    new PrintWriter(new StringWriter(), true)
            );
            CommandLine overwriteCli = new CommandLine(overwriteCmd);
            int overwriteExit = overwriteCli.execute(bundlePath.toString(), "--passphrase", "testpass123", "--overwrite");
            assertEquals(0, overwriteExit);

            String overwriteOutput = overwriteOut.toString();
            assertTrue(overwriteOutput.contains("覆盖更新: 2") || overwriteOutput.contains("覆盖: 2"));

            // Verify KEY_A updated to secret-alpha
            Token updatedA = targetRepo.findToken("default", "KEY_A").orElseThrow();
            byte[] decUpdatedA = CryptoEngine.decrypt(updatedA.nonce(), updatedA.ciphertext(), targetMasterKey);
            assertEquals("secret-alpha", new String(decUpdatedA, StandardCharsets.UTF_8));
        }
    }

    @Test
    void shouldExportAndImportUnencryptedJson(@TempDir Path tempDir) throws Exception {
        Path jsonPath = tempDir.resolve("export.json");

        StringWriter exportOut = new StringWriter();
        ExportCommand exportCmd = new ExportCommand(
                sourceRepo,
                sourceKeyProvider,
                outputStyle,
                new PrintWriter(exportOut, true),
                new PrintWriter(new StringWriter(), true)
        );
        CommandLine exportCli = new CommandLine(exportCmd);
        int exportExit = exportCli.execute("--unencrypted", "--out", jsonPath.toString());
        assertEquals(0, exportExit);
        assertTrue(Files.exists(jsonPath));

        String jsonContent = Files.readString(jsonPath);
        assertTrue(jsonContent.contains("KEY_A"));
        assertTrue(jsonContent.contains("secret-alpha"));

        // Import into fresh target repo
        byte[] targetMasterKey = CryptoEngine.generateRandomBytes(32);
        Path targetDbPath = tempDir.resolve("target-json.db");
        try (SqliteTokenRepository targetRepo = new SqliteTokenRepository(targetDbPath, () -> targetMasterKey)) {
            targetRepo.initializeSchema();

            StringWriter importOut = new StringWriter();
            ImportCommand importCmd = new ImportCommand(
                    targetRepo,
                    () -> targetMasterKey,
                    outputStyle,
                    new PrintWriter(importOut, true),
                    new PrintWriter(new StringWriter(), true)
            );
            CommandLine importCli = new CommandLine(importCmd);
            int importExit = importCli.execute(jsonPath.toString());
            assertEquals(0, importExit);

            Optional<Token> importedA = targetRepo.findToken("default", "KEY_A");
            assertTrue(importedA.isPresent());
            byte[] dec = CryptoEngine.decrypt(importedA.get().nonce(), importedA.get().ciphertext(), targetMasterKey);
            assertEquals("secret-alpha", new String(dec, StandardCharsets.UTF_8));
        }
    }

    @Test
    void shouldCreateAndRestoreBackup(@TempDir Path tempDir) throws Exception {
        Path backupFile = tempDir.resolve("manual-backup.db");

        StringWriter backupOut = new StringWriter();
        CommandLine backupCli = BackupCommand.createCommandLine(
                sourceRepo,
                sourceDbPath,
                outputStyle,
                new PrintWriter(backupOut, true),
                new PrintWriter(new StringWriter(), true)
        );
        int backupExit = backupCli.execute("create", "--out", backupFile.toString());
        assertEquals(0, backupExit);
        assertTrue(Files.exists(backupFile));

        // Delete KEY_A in sourceRepo
        sourceRepo.deleteToken("default", "KEY_A");
        assertFalse(sourceRepo.findToken("default", "KEY_A").isPresent());

        // Restore
        StringWriter restoreOut = new StringWriter();
        CommandLine restoreCli = BackupCommand.createCommandLine(
                sourceRepo,
                sourceDbPath,
                outputStyle,
                new PrintWriter(restoreOut, true),
                new PrintWriter(new StringWriter(), true)
        );
        int restoreExit = restoreCli.execute("restore", backupFile.toString());
        assertEquals(0, restoreExit);

        // Verify KEY_A restored
        assertTrue(sourceRepo.findToken("default", "KEY_A").isPresent());
    }
}
