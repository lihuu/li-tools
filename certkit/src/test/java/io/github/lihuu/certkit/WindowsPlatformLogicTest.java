package io.github.lihuu.certkit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        Openssl openssl = new Openssl();
        Path certPath = tempDir.resolve("win-test-ca.crt");
        Path keyPath = tempDir.resolve("win-test-ca.key");
        openssl.generateCa("Win Dev CA", 365, "ecdsa", certPath, keyPath);

        TrustStore windowsTs = new TrustStore(TrustStore.Platform.WINDOWS);

        boolean isRealWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (isRealWindows) {
            // On real Windows (including CI runners), verify check does not throw or hang
            var checkResult = windowsTs.check(certPath);
            assertNotNull(checkResult);
            assertFalse(checkResult.success()); // cert was never installed, so not trusted
        } else {
            // On non-Windows platforms, certutil.exe does not exist, so it fails gracefully with command advice
            var userResult = windowsTs.install(certPath, false);
            assertNotNull(userResult);
            assertFalse(userResult.success());
            assertNotNull(userResult.suggestedCommand());
            assertTrue(userResult.suggestedCommand().contains("certutil.exe"));

            var sysResult = windowsTs.install(certPath, true);
            assertNotNull(sysResult);
            assertFalse(sysResult.success());
            assertNotNull(sysResult.suggestedCommand());
            assertTrue(sysResult.suggestedCommand().contains("certutil.exe"));
        }
    }
}
