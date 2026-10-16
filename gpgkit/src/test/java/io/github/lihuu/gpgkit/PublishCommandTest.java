package io.github.lihuu.gpgkit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code gpgkit publish}: what it actually asks a keyserver to do.
 *
 * <p>This is the one command here whose effect cannot be undone — a keyserver keeps what it is given —
 * so the test does not call one. It puts a recording {@code gpg} in front of the real one on PATH and
 * reads back the arguments. That is also the only way to check the thing that matters: that the
 * argument after {@code --send-keys} is a public key's full fingerprint, and that nothing resembling
 * {@code --export-secret-keys} is ever passed.
 *
 * <p>The stub forwards everything except the two keyserver verbs to the real gpg, so key resolution
 * runs against a real keyring; only the network is taken out.
 */
class PublishCommandTest {
    private static final String KEYSERVER = "hkps://keys.example.invalid";

    /**
     * A shell script, because this project is POSIX-only in the same way {@code build-native.sh} is:
     * there is no {@code /bin/sh} on Windows and no macOS JAVA_HOME lookup there either.
     */
    private static Path installStubGpg(Path directory, Path log) throws IOException {
        Path stub = Files.createDirectories(directory).resolve("gpg");
        Path real = Gpg.locate().orElseThrow();
        String script = """
                #!/bin/sh
                # Test stub: records what it was asked to do, and answers the two keyserver verbs itself
                # so that no test ever sends anything anywhere.
                printf '%%s\\n' "$*" >> "%s"
                for argument in "$@"; do
                  case "$argument" in
                    --send-keys) exit 0 ;;
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
        List<String> calls = new ArrayList<>();
        for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                calls.add(line);
            }
        }
        return calls;
    }

    @Test
    void publishSendsTheFullPublicFingerprintAndNothingElse(@TempDir Path scratch) throws Exception {
        try (ScratchKeyring keyring = ScratchKeyring.create()) {
            Path log = scratch.resolve("gpg-calls.log");
            Path stubDirectory = scratch.resolve("bin");
            installStubGpg(stubDirectory, log);

            Cli cli = new Cli(keyring.work(), keyring.home(), stubDirectory,
                    Map.of("GPGKIT_STUB_LOG", log.toString()));
            // Named by its short key id on purpose: the tool has to resolve it and then hand gpg the
            // full fingerprint, because a keyserver asked for a key id is a keyserver asked imprecisely.
            String shortId = keyring.fingerprint().substring(keyring.fingerprint().length() - 8);
            Cli.Result result = cli.run("publish", "--key", shortId, "--keyserver", KEYSERVER);

            assertEquals(0, result.exitCode(), result.combined());

            String send = null;
            for (String call : recordedCalls(log)) {
                assertFalse(call.contains("--export-secret-keys"),
                        "a private key must never be exported for a keyserver: " + call);
                if (call.contains("--send-keys")) {
                    send = call;
                }
            }
            assertTrue(send != null, "no --send-keys call was made:\n" + recordedCalls(log));
            List<String> words = List.of(send.split(" "));
            assertEquals(keyring.fingerprint(),
                    words.get(words.indexOf("--send-keys") + 1),
                    "the argument after --send-keys must be the full fingerprint: " + send);
            assertEquals(KEYSERVER, words.get(words.indexOf("--keyserver") + 1),
                    "the requested keyserver must be the one used: " + send);
            assertTrue(result.stdout().contains(keyring.fingerprint()), result.stdout());
        }
    }

    @Test
    void publishFailsWhenThereIsNothingToPublish(@TempDir Path scratch) throws Exception {
        try (ScratchKeyring keyring = ScratchKeyring.create()) {
            Path log = scratch.resolve("gpg-calls.log");
            Path stubDirectory = scratch.resolve("bin");
            installStubGpg(stubDirectory, log);

            Path empty = Files.createDirectories(scratch.resolve("empty-keyring"));
            Cli cli = new Cli(keyring.work(), empty, stubDirectory, Map.of());

            Cli.Result result = cli.run("publish", "--keyserver", KEYSERVER);

            assertEquals(1, result.exitCode(), result.combined());
            assertTrue(result.stderr().contains("gpgkit gen"),
                    "the failure should say how to get a key: " + result.stderr());
            for (String call : recordedCalls(log)) {
                assertFalse(call.contains("--send-keys"), "nothing should have been sent: " + call);
            }
        }
    }
}
