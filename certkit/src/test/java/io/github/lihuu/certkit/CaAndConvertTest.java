package io.github.lihuu.certkit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CaAndConvertTest {

    @Test
    void fullCaWorkflowAndFormatConversion(@TempDir Path tempDir) {
        Path caDir = tempDir.resolve("ca");
        Path certsDir = tempDir.resolve("certs");

        // 1. Generate Root CA
        int caExit = Main.run("ca", "--out", caDir.toString(), "--name", "My Enterprise Dev Root CA");
        assertEquals(0, caExit, "certkit ca should succeed");
        Path caCert = caDir.resolve("ca.crt");
        Path caKey = caDir.resolve("ca.key");
        assertTrue(Files.exists(caCert));
        assertTrue(Files.exists(caKey));

        // 2. Generate child cert signed by this CA
        int genExit = Main.run(
            "gen",
            "--out", certsDir.toString(),
            "--ca-cert", caCert.toString(),
            "--ca-key", caKey.toString(),
            "-d", "internal.api,localhost",
            "--ip", "10.0.0.1,127.0.0.1"
        );
        assertEquals(0, genExit, "certkit gen with CA should succeed");
        Path certFile = certsDir.resolve("cert.pem");
        Path keyFile = certsDir.resolve("key.pem");
        Path fullchainFile = certsDir.resolve("fullchain.pem");
        assertTrue(Files.exists(certFile));
        assertTrue(Files.exists(keyFile));
        assertTrue(Files.exists(fullchainFile));

        // 3. Inspect child cert: Issuer must match CA
        try {
            CertInfo childInfo = CertParser.parse(certFile);
            assertTrue(childInfo.issuer().contains("My Enterprise Dev Root CA"));
            assertTrue(childInfo.sans().contains("DNS:internal.api"));
            assertTrue(childInfo.sans().contains("IP:10.0.0.1"));
        } catch (Exception e) {
            fail("Failed to parse child certificate: " + e.getMessage());
        }

        // 4. Test format conversions: PEM -> P12 -> PEM
        Path p12File = certsDir.resolve("bundle.p12");
        int toP12Exit = Main.run("convert", "--in", certFile.toString(), "--key", keyFile.toString(), "--out", p12File.toString(), "--pass", "secret123");
        assertEquals(0, toP12Exit, "converting to p12 should succeed");
        assertTrue(Files.exists(p12File));

        Path restoredPem = certsDir.resolve("restored.pem");
        int fromP12Exit = Main.run("convert", "--in", p12File.toString(), "--out", restoredPem.toString(), "--pass", "secret123");
        assertEquals(0, fromP12Exit, "converting p12 back to pem should succeed");
        assertTrue(Files.exists(restoredPem));

        // 5. Test PEM -> DER
        Path derFile = certsDir.resolve("cert.der");
        int toDerExit = Main.run("convert", "--in", certFile.toString(), "--out", derFile.toString());
        assertEquals(0, toDerExit, "converting pem to der should succeed");
        assertTrue(Files.exists(derFile));

        // 6. Test trust check behavior (non-existent or not-yet-installed)
        int trustCheckExit = Main.run("trust", "check", caCert.toString());
        // Since it's a freshly generated temp CA not installed in system keychain, check should exit with 1 gracefully without crash
        assertEquals(1, trustCheckExit);
    }
}
