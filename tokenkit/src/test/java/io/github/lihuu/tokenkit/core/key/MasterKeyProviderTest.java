package io.github.lihuu.tokenkit.core.key;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import io.github.lihuu.tokenkit.core.crypto.CryptoEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MasterKeyProviderTest {

    @Test
    void envProviderShouldTakePrecedenceWhenConfigured() throws GeneralSecurityException {
        byte[] expectedKey = CryptoEngine.generateRandomBytes(32);
        String hexKey = HexFormat.of().formatHex(expectedKey);

        EnvMasterKeyProvider provider = new EnvMasterKeyProvider(Map.of("TOKENKIT_MASTER_KEY", hexKey));
        byte[] key = provider.getOrCreateMasterKey();

        assertNotNull(key);
        assertEquals(32, key.length);
        assertArrayEquals(expectedKey, key);
    }

    @Test
    void envProviderShouldThrowIfKeyInvalidHex() {
        EnvMasterKeyProvider provider = new EnvMasterKeyProvider(Map.of("TOKENKIT_MASTER_KEY", "invalid-hex-non-32-byte")); // gitleaks:allow
        assertThrows(GeneralSecurityException.class, provider::getOrCreateMasterKey);
    }

    @Test
    void linuxKeyfileProviderShouldCreateAndPersistMasterKey(@TempDir Path tempDir) throws GeneralSecurityException, IOException {
        Path keyFile = tempDir.resolve(".master.key");
        LinuxKeyfileProvider provider = new LinuxKeyfileProvider(keyFile);

        byte[] key1 = provider.getOrCreateMasterKey();
        assertEquals(32, key1.length);
        assertTrue(Files.exists(keyFile));

        // Read second time from disk
        byte[] key2 = provider.getOrCreateMasterKey();
        assertArrayEquals(key1, key2);

        // Check file permissions on POSIX systems
        if (keyFile.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(keyFile);
            assertTrue(permissions.contains(PosixFilePermission.OWNER_READ));
            assertTrue(permissions.contains(PosixFilePermission.OWNER_WRITE));
            assertFalse(permissions.contains(PosixFilePermission.GROUP_READ));
            assertFalse(permissions.contains(PosixFilePermission.OTHERS_READ));
        }
    }

    @Test
    void compositeProviderShouldFallbackToKeyfileWhenEnvAbsent(@TempDir Path tempDir) throws GeneralSecurityException {
        CompositeMasterKeyProvider provider = new CompositeMasterKeyProvider(
                Map.of(),
                "Linux",
                tempDir
        );

        byte[] key = provider.getOrCreateMasterKey();
        assertEquals(32, key.length);
    }

    @Test
    void compositeProviderShouldPrioritizeEnvOverOs(@TempDir Path tempDir) throws GeneralSecurityException {
        byte[] expectedKey = CryptoEngine.generateRandomBytes(32);
        String hexKey = HexFormat.of().formatHex(expectedKey);

        CompositeMasterKeyProvider provider = new CompositeMasterKeyProvider(
                Map.of("TOKENKIT_MASTER_KEY", hexKey),
                "Mac OS X",
                tempDir
        );

        byte[] key = provider.getOrCreateMasterKey();
        assertArrayEquals(expectedKey, key);
    }
}
