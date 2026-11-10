package io.github.lihuu.mitmkit.core;

import io.github.lihuu.mitmkit.core.CertAuthority;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class CertAuthorityTest {

    @Test
    void generatesCaAndSignsLeafWithSan(@TempDir Path tempDir) throws Exception {
        Path caDir = tempDir.resolve("ca");
        CertAuthority authority = new CertAuthority(caDir);
        authority.ensureCa();

        Path caCert = caDir.resolve("ca.crt");
        Path caKey = caDir.resolve("ca.key");
        assertTrue(Files.exists(caCert), "CA certificate should be written");
        assertTrue(Files.exists(caKey), "CA key should be written");

        X509Certificate ca = authority.caCertificate();
        assertEquals("mitmkit local CA", commonName(ca));
        assertTrue(ca.getBasicConstraints() >= 0, "CA certificate must have CA basic constraint");
        assertTrue(ca.getNotAfter().after(new Date()), "CA must not be expired");

        X509Certificate leaf = authority.leafCertificate("example.test");
        assertTrue(leaf.getIssuerX500Principal().getName().contains("mitmkit local CA"),
                "leaf issuer must be the local CA");
        assertTrue(hasSan(leaf, "example.test"), "leaf must carry SAN DNS:example.test");
        assertTrue(leaf.getNotAfter().after(new Date()), "leaf must not be expired");

        authority.close();
    }

    @Test
    void reusesExistingCaOnSecondEnsure(@TempDir Path tempDir) throws Exception {
        Path caDir = tempDir.resolve("ca");
        CertAuthority first = new CertAuthority(caDir);
        first.ensureCa();
        X509Certificate caBefore = first.caCertificate();
        first.close();

        CertAuthority second = new CertAuthority(caDir);
        second.ensureCa();
        assertEquals(caBefore.getSerialNumber(), second.caCertificate().getSerialNumber(),
                "ensureCa must reuse the existing CA, not regenerate it");
        second.close();
    }

    @Test
    void signsIpSan(@TempDir Path tempDir) throws Exception {
        CertAuthority authority = new CertAuthority(tempDir.resolve("ca"));
        authority.ensureCa();
        X509Certificate leaf = authority.leafCertificate("127.0.0.1");
        assertTrue(hasSan(leaf, "127.0.0.1"), "leaf must carry SAN IP:127.0.0.1");
        authority.close();
    }

    private static String commonName(X509Certificate certificate) {
        String dn = certificate.getSubjectX500Principal().getName();
        for (String part : dn.split(",")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("CN=")) {
                return trimmed.substring(3);
            }
        }
        return dn;
    }

    private static boolean hasSan(X509Certificate certificate, String expected) throws Exception {
        var sans = certificate.getSubjectAlternativeNames();
        if (sans == null) return false;
        for (var san : sans) {
            Object value = san.get(1);
            if (expected.equals(value)) return true;
        }
        return false;
    }
}
