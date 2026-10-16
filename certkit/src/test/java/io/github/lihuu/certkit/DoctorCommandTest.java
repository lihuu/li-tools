package io.github.lihuu.certkit;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

class DoctorCommandTest {

    @Test
    void doctorRunsAndReportsHealth() {
        ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(outStream));
            int exitCode = Main.run("doctor");
            assertEquals(0, exitCode, "doctor should pass when OpenSSL is installed");
            String output = outStream.toString();
            assertTrue(output.contains("certkit doctor — Environment & Health Diagnostics:"));
            assertTrue(output.contains("Operating System:"));
            assertTrue(output.contains("OpenSSL Engine:"));
            assertTrue(output.contains("System Trust Store:"));
            assertTrue(output.contains("Core cryptographic engine is ready!"));
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    void doctorProducesValidJson() {
        ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(outStream));
            int exitCode = Main.run("--json", "doctor");
            assertEquals(0, exitCode);
            String output = outStream.toString();
            assertTrue(output.contains("\"allReady\": true"));
            assertTrue(output.contains("\"openssl\""));
            assertTrue(output.contains("\"trustStore\""));
            assertTrue(output.contains("\"os\""));
        } finally {
            System.setOut(originalOut);
        }
    }
}
