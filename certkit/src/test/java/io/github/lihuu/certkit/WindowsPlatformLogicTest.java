package io.github.lihuu.certkit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WindowsPlatformLogicTest {

    @Test
    void windowsTrustStorePlatformRecognized() {
        TrustStore windowsTs = new TrustStore(TrustStore.Platform.WINDOWS);
        assertEquals(TrustStore.Platform.WINDOWS, windowsTs.getPlatform());
    }

    @Test
    void windowsInstallCommandStructure(@TempDir Path tempDir) throws Exception {
        // Generate a dummy valid certificate for parser
        Openssl openssl = new Openssl();
        Path certPath = tempDir.resolve("win-test-ca.crt");
        Path keyPath = tempDir.resolve("win-test-ca.key");
        openssl.generateCa("Win Dev CA", 365, "ecdsa", certPath, keyPath);

        TrustStore windowsTs = new TrustStore(TrustStore.Platform.WINDOWS);

        // In macOS environment, running certutil.exe will fail to start process gracefully and return a TrustResult with command advice
        var userResult = windowsTs.install(certPath, false);
        assertNotNull(userResult);
        // Should have attempted certutil.exe
        assertFalse(userResult.success()); // Because we're currently on macOS runner, certutil.exe is not installed

        var sysResult = windowsTs.install(certPath, true);
        assertNotNull(sysResult);
        assertFalse(sysResult.success());
    }
}
