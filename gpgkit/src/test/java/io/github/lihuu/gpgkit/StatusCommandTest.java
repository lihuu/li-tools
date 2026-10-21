package io.github.lihuu.gpgkit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusCommandTest {

    private static Path installStubGpg(Path directory, Path log) throws IOException {
        Path stub = Files.createDirectories(directory).resolve("gpg");
        Path real = Gpg.locate().orElseThrow();
        String script = """
                #!/bin/sh
                printf '%%s\\n' "$*" >> "%s"
                for argument in "$@"; do
                  case "$argument" in
                    --recv-keys) printf 'gpg: keyserver receive failed: No data\\n' >&2; exit 2 ;;
                  esac
                done
                exec "%s" "$@"
                """.formatted(log, real);
        Files.writeString(stub, script, StandardCharsets.UTF_8);
        assertTrue(stub.toFile().setExecutable(true), "could not make the stub executable");
        return stub;
    }

    private static List<String> recordedCalls(Path log) throws IOException {
        if (!Files.exists(log)) {
            return List.of();
        }
        return Files.readAllLines(log, StandardCharsets.UTF_8);
    }

    @Test
    void statusAcceptsRefreshFlag() {
        try (ScratchKeyring keyring = ScratchKeyring.create()) {
            Cli.Result result = keyring.cli().run("status", "--refresh");
            assertEquals(0, result.exitCode(), result.combined());
        }
    }

    @Test
    void statusTreatsUnknownArgumentAsUsageError(@TempDir Path work) {
        Cli.Result result = new Cli(work, work).run("status", "--unknown");
        assertEquals(2, result.exitCode(), result.combined());
        assertTrue(result.stderr().contains("--unknown"), result.stderr());
    }

    @Test
    void statusUsesCachedProbeWithoutInvokingRecvKeys(@TempDir Path work) throws IOException {
        try (ScratchKeyring keyring = ScratchKeyring.create()) {
            Path bin = work.resolve("bin");
            Path log = work.resolve("gpg.log");
            installStubGpg(bin, log);

            // Populate cache directly in sandboxed user.home:
            Path cacheFile = work.resolve(".gpgkit").resolve("keyserver-cache");
            KeyserverCache cache = new KeyserverCache(cacheFile);
            cache.put(keyring.fingerprint(), new Keyserver.Probe(Keyserver.Visibility.VISIBLE, "cached ok"));

            Cli cli = new Cli(work, keyring.home(), bin, Map.of("GPGKIT_KEYSERVER_CACHE", cacheFile.toString()));
            Cli.Result result = cli.run("status");

            assertEquals(0, result.exitCode(), result.combined());
            assertTrue(result.stdout().contains("keyserver  visible"), result.stdout());

            // Verify --recv-keys was NOT called because cache was hit
            List<String> calls = recordedCalls(log);
            boolean calledRecvKeys = calls.stream().anyMatch(call -> call.contains("--recv-keys"));
            assertFalse(calledRecvKeys, "status should hit cache and avoid --recv-keys: " + calls);
        }
    }

    @Test
    void statusRefreshBypassesCacheAndInvokesRecvKeys(@TempDir Path work) throws IOException {
        try (ScratchKeyring keyring = ScratchKeyring.create()) {
            Path bin = work.resolve("bin");
            Path log = work.resolve("gpg.log");
            installStubGpg(bin, log);

            Path cacheFile = work.resolve(".gpgkit").resolve("keyserver-cache");
            KeyserverCache cache = new KeyserverCache(cacheFile);
            cache.put(keyring.fingerprint(), new Keyserver.Probe(Keyserver.Visibility.VISIBLE, "cached ok"));

            Cli cli = new Cli(work, keyring.home(), bin, Map.of("GPGKIT_KEYSERVER_CACHE", cacheFile.toString()));
            Cli.Result result = cli.run("status", "--refresh");

            assertEquals(0, result.exitCode(), result.combined());

            // Verify --recv-keys WAS called because of --refresh
            List<String> calls = recordedCalls(log);
            boolean calledRecvKeys = calls.stream().anyMatch(call -> call.contains("--recv-keys"));
            assertTrue(calledRecvKeys, "status --refresh must query keyserver: " + calls);
        }
    }
}
