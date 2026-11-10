package io.github.lihuu.mitmkit.core;

import io.github.lihuu.clicommon.system.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TrustStoreTest {

    @Test
    void missingCertIsReported() {
        TrustStore trust = new TrustStore(Platform.OS.MACOS);
        TrustStore.TrustResult result = trust.install(Path.of("/nonexistent/ca.crt"));
        assertFalse(result.success());
        assertTrue(result.message().contains("does not exist"), result.message());
    }

    @Test
    void unsupportedPlatformIsReportedGracefully(@TempDir Path tempDir) throws Exception {
        Path caCert = tempDir.resolve("ca.crt");
        Files.writeString(caCert, "placeholder");
        TrustStore trust = new TrustStore(Platform.OS.UNKNOWN);
        TrustStore.TrustResult result = trust.install(caCert);
        assertFalse(result.success());
        assertTrue(result.message().contains("Unsupported"), result.message());
    }

    @Test
    void macosInstallFailureCarriesRemediationCommand(@TempDir Path tempDir) throws Exception {
        CertAuthority authority = new CertAuthority(tempDir.resolve("ca"));
        authority.ensureCa();
        Path caCert = tempDir.resolve("ca").resolve("ca.crt");

        // On a non-macOS machine, security does not exist; the install must fail
        // gracefully and still hand back a copy-pasteable command.
        boolean isMac = System.getProperty("os.name", "").toLowerCase().contains("mac");
        TrustStore trust = new TrustStore(Platform.OS.MACOS);
        TrustStore.TrustResult result = trust.install(caCert);
        if (isMac) {
            // On macOS without admin rights the keychain write is denied.
            if (!result.success()) {
                assertNotNull(result.suggestedCommand(), result.message());
            }
        } else {
            assertFalse(result.success());
            assertNotNull(result.suggestedCommand(), "failure should carry a remediation command");
            assertTrue(result.suggestedCommand().contains("security"), result.suggestedCommand());
        }
        authority.close();
    }

    @Test
    void windowsInstallFailureCarriesRemediationCommand(@TempDir Path tempDir) throws Exception {
        CertAuthority authority = new CertAuthority(tempDir.resolve("ca"));
        authority.ensureCa();
        Path caCert = tempDir.resolve("ca").resolve("ca.crt");

        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        TrustStore trust = new TrustStore(Platform.OS.WINDOWS);
        TrustStore.TrustResult result = trust.install(caCert);
        if (!isWindows) {
            assertFalse(result.success());
            assertNotNull(result.suggestedCommand(), "failure should carry a remediation command");
            assertTrue(result.suggestedCommand().contains("certutil"), result.suggestedCommand());
        }
        authority.close();
    }

    @Test
    void checkNeverThrows(@TempDir Path tempDir) throws Exception {
        CertAuthority authority = new CertAuthority(tempDir.resolve("ca"));
        authority.ensureCa();
        Path caCert = tempDir.resolve("ca").resolve("ca.crt");
        TrustStore trust = new TrustStore(Platform.OS.MACOS);
        // Whether the CA is trusted depends on the machine; the contract is that
        // the check always returns a result with a human-readable message.
        TrustStore.TrustResult result = trust.check(caCert);
        assertNotNull(result.message());
        assertFalse(result.message().isBlank());
        authority.close();
    }
}
