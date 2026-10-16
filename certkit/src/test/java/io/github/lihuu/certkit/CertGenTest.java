package io.github.lihuu.certkit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CertGenTest {

    @Test
    void generatesAndVerifiesSelfSignedCertificate(@TempDir Path tempDir) throws Exception {
        Openssl openssl = new Openssl();
        Path certOut = tempDir.resolve("cert.pem");
        Path keyOut = tempDir.resolve("key.pem");

        CertInfo info = openssl.generateSelfSigned(
            List.of("localhost", "mydev.local"),
            List.of("127.0.0.1", "192.168.1.100"),
            365,
            "ecdsa",
            certOut,
            keyOut
        );

        assertTrue(Files.exists(certOut), "cert.pem should exist");
        assertTrue(Files.exists(keyOut), "key.pem should exist");

        // Verify parsed contents
        assertEquals("localhost", info.subjectCn());
        assertTrue(info.sans().contains("DNS:localhost"));
        assertTrue(info.sans().contains("DNS:mydev.local"));
        assertTrue(info.sans().contains("IP:127.0.0.1"));
        assertTrue(info.sans().contains("IP:192.168.1.100"));
        assertNotNull(info.sha256Fingerprint());
        assertTrue(info.daysRemaining() >= 364 && info.daysRemaining() <= 366);
        assertFalse(info.isExpired());

        // Verify key permissions on POSIX
        try {
            var perms = Files.getPosixFilePermissions(keyOut);
            assertEquals("rw-------", PosixFilePermissions.toString(perms));
        } catch (UnsupportedOperationException ignored) {
            // Not a POSIX filesystem
        }
    }
}
