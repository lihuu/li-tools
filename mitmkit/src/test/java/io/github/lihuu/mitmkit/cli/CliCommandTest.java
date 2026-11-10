package io.github.lihuu.mitmkit.cli;

import io.github.lihuu.mitmkit.app.CliApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CliCommandTest {

    private static final class Result {
        final int exitCode;
        final String stdout;
        final String stderr;

        Result(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }

    private static Result run(String[] args, Map<String, String> env, boolean interactive) {
        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        int code = CliApplication.run(
                args,
                java.nio.file.Path.of(""),
                new PrintWriter(outBuffer, true, StandardCharsets.UTF_8),
                new PrintWriter(errBuffer, true, StandardCharsets.UTF_8),
                env,
                interactive,
                false,
                System.in);
        return new Result(code, outBuffer.toString(StandardCharsets.UTF_8),
                errBuffer.toString(StandardCharsets.UTF_8));
    }

    @Test
    void helpExitsZero() {
        Result result = run(new String[]{"--help"}, Map.of(), false);
        assertEquals(0, result.exitCode);
        assertTrue(result.stdout.contains("mitmkit"), result.stdout);
    }

    @Test
    void versionExitsZero() {
        Result result = run(new String[]{"--version"}, Map.of(), false);
        assertEquals(0, result.exitCode);
        assertTrue(result.stdout.contains("mitmkit"), result.stdout);
    }

    @Test
    void unknownCommandExitsTwo() {
        Result result = run(new String[]{"bogus"}, Map.of(), false);
        assertEquals(2, result.exitCode);
    }

    @Test
    void caGeneratesCaAndJsonReportsPaths(@TempDir Path tempDir) {
        Map<String, String> env = Map.of("MITMKIT_CA_DIR", tempDir.resolve("ca").toString());
        Result result = run(new String[]{"ca", "--json"}, env, false);
        assertEquals(0, result.exitCode, result.stderr);
        assertTrue(result.stdout.contains("\"caCert\""), result.stdout);
        assertTrue(result.stdout.contains("\"caKey\""), result.stdout);
        assertTrue(result.stdout.contains("\"subjectCn\":\"mitmkit local CA\""), result.stdout);
        assertTrue(Files.exists(tempDir.resolve("ca").resolve("ca.crt")), "CA cert should exist");
        assertTrue(Files.exists(tempDir.resolve("ca").resolve("ca.key")), "CA key should exist");
    }

    @Test
    void caRefusesToOverwriteWithoutForce(@TempDir Path tempDir) throws Exception {
        Path caDir = tempDir.resolve("ca");
        Files.createDirectories(caDir);
        Files.writeString(caDir.resolve("ca.crt"), "existing");
        Map<String, String> env = Map.of("MITMKIT_CA_DIR", caDir.toString());
        Result result = run(new String[]{"ca"}, env, false);
        assertEquals(1, result.exitCode);
        assertEquals("existing", Files.readString(caDir.resolve("ca.crt")), "existing CA must not be replaced");
    }

    @Test
    void caForceRebuilds(@TempDir Path tempDir) throws Exception {
        Path caDir = tempDir.resolve("ca");
        Files.createDirectories(caDir);
        Files.writeString(caDir.resolve("ca.crt"), "existing");
        Map<String, String> env = Map.of("MITMKIT_CA_DIR", caDir.toString());
        Result result = run(new String[]{"ca", "--force"}, env, false);
        assertEquals(0, result.exitCode, result.stderr);
        assertNotEquals("existing", Files.readString(caDir.resolve("ca.crt")), "CA should be rebuilt");
    }

    @Test
    void bareInvocationShowsHelpWhenInteractive() {
        Result result = run(new String[]{}, Map.of(), true);
        assertEquals(0, result.exitCode);
        assertTrue(result.stdout.contains("mitmkit"), result.stdout);
    }
}
