package io.github.lihuu.certkit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CliCommandTest {

    @Test
    void genAndCheckWorkflow(@TempDir Path tempDir) {
        Path certsDir = tempDir.resolve("certs");

        // 1. Run certkit gen
        int genExit = Main.run("gen", "--out", certsDir.toString(), "-d", "localhost,dev.local", "--ip", "127.0.0.1,10.0.0.1");
        assertEquals(0, genExit, "certkit gen should succeed");
        assertTrue(Files.exists(certsDir.resolve("cert.pem")));
        assertTrue(Files.exists(certsDir.resolve("key.pem")));

        // 2. Run certkit check on local cert file
        Path certFile = certsDir.resolve("cert.pem");
        int checkExit = Main.run("check", certFile.toString());
        assertEquals(0, checkExit, "certkit check should succeed");

        // 3. Run certkit check with --json
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(outContent));
            int jsonExit = Main.run("--json", "check", certFile.toString());
            assertEquals(0, jsonExit);
            String jsonOutput = outContent.toString();
            assertTrue(jsonOutput.contains("\"subjectCn\": \"localhost\""));
            assertTrue(jsonOutput.contains("DNS:dev.local"));
            assertTrue(jsonOutput.contains("IP:10.0.0.1"));
            assertTrue(jsonOutput.contains("\"isExpired\": false"));
        } finally {
            System.setOut(originalOut);
        }

        // 4. Run certkit check on non-existent file
        int failExit = Main.run("check", tempDir.resolve("nonexistent.pem").toString());
        assertEquals(1, failExit, "checking non-existent file should exit with code 1");
    }
}
