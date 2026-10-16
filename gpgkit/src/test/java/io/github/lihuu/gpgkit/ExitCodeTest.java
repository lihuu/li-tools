package io.github.lihuu.gpgkit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC §2's exit code contract: 0 success, 1 failure with the reason on stderr, 2 usage error.
 *
 * <p>Worth a test of its own because these are the codes a release script branches on, and because
 * the boundary between the last two is the one that drifts: "you typed the command wrong" and "the
 * thing you asked for did not work" lead a caller to do different things, and a script that treats
 * both as "something failed" is a script that retries a typo for ever.
 *
 * <p>None of these tests need a keyring. Every one of them fails before the keyring is opened, which
 * is itself the property being tested: a malformed command line must not get far enough to look at
 * anything.
 */
class ExitCodeTest {
    @Test
    void genTreatsAnUnknownAlgorithmAsAUsageError(@TempDir Path work) {
        Cli.Result result = new Cli(work, work).run("gen", "--algo", "rsa2048");

        assertEquals(2, result.exitCode(), result.combined());
        assertTrue(result.stderr().contains("rsa2048"), result.stderr());
    }

    @Test
    void genTreatsAMissingOptionValueAsAUsageError(@TempDir Path work) {
        Cli.Result result = new Cli(work, work).run("gen", "--uid");

        assertEquals(2, result.exitCode(), result.combined());
        assertTrue(result.stderr().contains("--uid"), result.stderr());
    }

    @Test
    void genTreatsAnUnknownOptionAsAUsageError(@TempDir Path work) {
        Cli.Result result = new Cli(work, work).run("gen", "--publish-now");

        assertEquals(2, result.exitCode(), result.combined());
        assertTrue(result.stderr().contains("--publish-now"), result.stderr());
    }

    @Test
    void signTreatsAnUnsupportedChecksumAlgorithmAsAUsageError(@TempDir Path work) {
        Cli.Result result = new Cli(work, work).run("sign", "release.jar", "--checksums", "md5");

        assertEquals(2, result.exitCode(), result.combined());
        assertTrue(result.stderr().contains("md5"), result.stderr());
    }

    @Test
    void signTreatsAnEmptyFileListAsAUsageError(@TempDir Path work) {
        Cli.Result result = new Cli(work, work).run("sign");

        assertEquals(2, result.exitCode(), result.combined());
    }

    @Test
    void verifyTreatsAnythingOtherThanOneFileAsAUsageError(@TempDir Path work) {
        Cli cli = new Cli(work, work);

        assertEquals(2, cli.run("verify").exitCode());
        assertEquals(2, cli.run("verify", "a.jar", "b.jar").exitCode());
    }

    @Test
    void publishTreatsAKeyserverWithoutASchemeAsAUsageError(@TempDir Path work) {
        Cli.Result result = new Cli(work, work).run("publish", "--keyserver", "keyserver.ubuntu.com");

        assertEquals(2, result.exitCode(), result.combined());
        assertTrue(result.stderr().contains("scheme"), result.stderr());
    }

    @Test
    void aUsageErrorIsStillAUsageErrorUnderJson(@TempDir Path work) {
        Cli.Result result = new Cli(work, work).run("--json", "gen", "--algo", "rsa2048");

        assertEquals(2, result.exitCode(), result.combined());
    }

    @Test
    void signFailsWhenAFileCannotBeReadAndSaysWhichOne(@TempDir Path work) {
        Cli.Result result = new Cli(work, work).run("sign", "no-such-artifact.jar");

        assertEquals(1, result.exitCode(), result.combined());
        assertTrue(result.stderr().contains("no-such-artifact.jar"), result.stderr());
    }

    @Test
    void signFailsWhenTheOutputDirectoryDoesNotExist(@TempDir Path work) throws Exception {
        java.nio.file.Files.writeString(work.resolve("release.jar"), "artifact\n");

        Cli.Result result = new Cli(work, work)
                .run("sign", "release.jar", "--out", work.resolve("nowhere").toString());

        assertEquals(1, result.exitCode(), result.combined());
        assertTrue(result.stderr().contains("nowhere"), result.stderr());
    }

    @Test
    void verifyFailsWhenTheFileIsNotThere(@TempDir Path work) {
        Cli.Result result = new Cli(work, work).run("verify", "no-such-artifact.jar");

        assertEquals(1, result.exitCode(), result.combined());
        assertTrue(result.stderr().contains("no-such-artifact.jar"), result.stderr());
    }

    @Test
    void verifyFailsWhenThereIsNoSignatureBesideTheFile(@TempDir Path work) throws Exception {
        java.nio.file.Files.writeString(work.resolve("release.jar"), "artifact\n");

        Cli.Result result = new Cli(work, work).run("verify", "release.jar");

        assertEquals(1, result.exitCode(), result.combined());
        assertTrue(result.stderr().contains("release.jar.asc"), result.stderr());
    }

    @Test
    void genFailsWhenThePassphraseFileCannotBeRead(@TempDir Path work) {
        Cli.Result result = new Cli(work, work)
                .run("gen", "--uid", "Probe <probe@example.invalid>", "--passphrase-file",
                        work.resolve("absent").toString());

        assertEquals(1, result.exitCode(), result.combined());
        assertTrue(result.stderr().contains("absent"), result.stderr());
    }

    @Test
    void publishFailsWhenNoKeyMatchesAndSaysWhatToDoNext() {
        try (ScratchKeyring keyring = ScratchKeyring.create()) {
            Cli.Result result = keyring.cli().run("publish", "--key", "DEADBEEFDEADBEEF");

            assertEquals(1, result.exitCode(), result.combined());
            assertTrue(result.stderr().contains("DEADBEEFDEADBEEF"), result.stderr());
            assertTrue(result.stderr().contains("gpgkit status"),
                    "a failure should say where to find the fingerprints: " + result.stderr());
        }
    }
}
