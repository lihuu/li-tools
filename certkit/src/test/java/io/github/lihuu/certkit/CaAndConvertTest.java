package io.github.lihuu.certkit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CaAndConvertTest {

    @Test
    void fullCaWorkflowAndFormatConversion(@TempDir Path tempDir) throws Exception {
        Path caDir = tempDir.resolve("ca");
        Path certsDir = tempDir.resolve("certs");

        // 1. Generate Root CA with no-trust flag for clean test isolation
        int caExit = Main.run("ca", "--out", caDir.toString(), "--name", "My Enterprise Dev Root CA", "--no-trust");
        assertEquals(0, caExit, "certkit ca should succeed");
        Path caCert = caDir.resolve("ca.crt");
        Path caKey = caDir.resolve("ca.key");
        assertTrue(Files.exists(caCert));
        assertTrue(Files.exists(caKey));

        // 2. Generate child cert signed by this CA using positional hostnames & IPs
        int genExit = Main.run(
            "gen",
            "--out", certsDir.toString(),
            "--ca-cert", caCert.toString(),
            "--ca-key", caKey.toString(),
            "internal.api", "localhost", "10.0.0.1", "127.0.0.1"
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

        // 4. Test format conversions with pure positional arguments and auto key detection!
        Path p12File = certsDir.resolve("bundle.p12");
        int toP12Exit = Main.run("convert", certFile.toString(), p12File.toString(), "-p", "secret123");
        assertEquals(0, toP12Exit, "converting to p12 should succeed with auto-detected key");
        assertTrue(Files.exists(p12File));

        Path restoredPem = certsDir.resolve("restored.pem");
        int fromP12Exit = Main.run("convert", p12File.toString(), restoredPem.toString(), "-p", "secret123");
        assertEquals(0, fromP12Exit, "converting p12 back to pem should succeed");
        assertTrue(Files.exists(restoredPem));

        // 5. Test PEM -> DER
        Path derFile = certsDir.resolve("cert.der");
        int toDerExit = Main.run("convert", certFile.toString(), derFile.toString());
        assertEquals(0, toDerExit, "converting pem to der should succeed");
        assertTrue(Files.exists(derFile));

        // 6. Test ultra-simple single-argument convert (auto-generate cert.p12 with same name)
        Files.deleteIfExists(certsDir.resolve("cert.p12"));
        int singleArgExit = Main.run("convert", certFile.toString());
        assertEquals(0, singleArgExit, "single argument convert should produce cert.p12");
        assertTrue(Files.exists(certsDir.resolve("cert.p12")), "cert.p12 should exist");

        // 7. Test --all flag (generate both .p12 and .der in one go)
        Files.deleteIfExists(certsDir.resolve("cert.p12"));
        Files.deleteIfExists(certsDir.resolve("cert.der"));
        int allExit = Main.run("convert", certFile.toString(), "--all");
        assertEquals(0, allExit, "convert --all should succeed");
        assertTrue(Files.exists(certsDir.resolve("cert.p12")));
        assertTrue(Files.exists(certsDir.resolve("cert.der")));

        // 8. Test raw passthrough command (escape hatch)
        int rawExit = Main.run("raw", "version");
        assertEquals(0, rawExit, "certkit raw version should exit with 0");

        // 9. Test trust check behavior
        int trustCheckExit = Main.run("trust", "check", caCert.toString());
        assertEquals(1, trustCheckExit);
    }
}
