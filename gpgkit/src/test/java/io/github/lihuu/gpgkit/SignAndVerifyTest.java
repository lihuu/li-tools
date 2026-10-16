package io.github.lihuu.gpgkit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code gpgkit sign} and {@code gpgkit verify}: the pair that has to agree about a release.
 *
 * <p>The two properties worth more than the rest of this file are that a failed signing run leaves
 * nothing behind, and that a file which changed after it was signed is named. Everything else here
 * is the report being accurate about what it produced.
 */
class SignAndVerifyTest {
    /** The report line that names this file, so its byte count can be read back off it. */
    private static String lineNaming(String stdout, String fileName) {
        for (String line : stdout.split("\n")) {
            if (line.contains(fileName)) {
                return line;
            }
        }
        throw new AssertionError("no line in the report names " + fileName + ":\n" + stdout);
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
    }

    @Test
    void signsEveryFileAndReportsEachOneWithItsSize() throws Exception {
        try (ScratchKeyring keyring = ScratchKeyring.create()) {
            keyring.write("alpha-1.0.jar", "alpha\n");
            keyring.write("beta-1.0.jar", "beta\n");

            Cli.Result result = keyring.cli().run("sign", "alpha-1.0.jar", "beta-1.0.jar");

            assertEquals(0, result.exitCode(), result.combined());
            for (String name : List.of("alpha-1.0.jar", "beta-1.0.jar")) {
                Path signature = keyring.work().resolve(name + ".asc");
                assertTrue(Files.isRegularFile(signature), "no signature for " + name);
                String line = lineNaming(result.stdout(), name + ".asc");
                assertTrue(line.endsWith(Files.size(signature) + " bytes"),
                        "the report should carry the real byte count of " + name + ": " + line);
            }
            // The signature has to be a real one, checked by gpg rather than by this tool.
            Path signature = keyring.work().resolve("alpha-1.0.jar.asc");
            Gpg.Result verified = new Gpg(keyring.home()).run(List.of("--status-fd", "1", "--verify",
                    signature.toString(), keyring.work().resolve("alpha-1.0.jar").toString()));
            assertEquals(0, verified.exitCode(), verified.stderr());
            assertTrue(verified.out().contains("[GNUPG:] VALIDSIG " + keyring.fingerprint()),
                    verified.out());
        }
    }

    @Test
    void theChecksumManifestIsSha256sumFormatAndIsSignedInTurn() throws Exception {
        try (ScratchKeyring keyring = ScratchKeyring.create()) {
            keyring.write("alpha-1.0.jar", "alpha\n");
            keyring.write("beta-1.0.jar", "beta\n");

            Cli.Result result = keyring.cli().run("sign", "alpha-1.0.jar", "beta-1.0.jar",
                    "--checksums", "sha256");

            assertEquals(0, result.exitCode(), result.combined());
            Path manifest = keyring.work().resolve(Checksums.MANIFEST_NAME);
            Path manifestSignature = keyring.work().resolve(
                    Checksums.MANIFEST_NAME + Checksums.MANIFEST_SIGNATURE_SUFFIX);
            assertTrue(Files.isRegularFile(manifestSignature), "SHA256SUMS.asc was not produced");

            List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
            assertEquals(2, lines.size(), "one line per signed artifact: " + lines);
            assertEquals(sha256(keyring.work().resolve("alpha-1.0.jar")) + "  alpha-1.0.jar", lines.get(0));
            assertEquals(sha256(keyring.work().resolve("beta-1.0.jar")) + "  beta-1.0.jar", lines.get(1));

            // The manifest's own signature is over the manifest, not over the artifacts — that is what
            // makes trusting one file enough.
            Gpg.Result verified = new Gpg(keyring.home()).run(List.of("--status-fd", "1", "--verify",
                    manifestSignature.toString(), manifest.toString()));
            assertEquals(0, verified.exitCode(), verified.stderr());
            assertTrue(verified.out().contains("[GNUPG:] VALIDSIG " + keyring.fingerprint()),
                    verified.out());

            assertTrue(lineNaming(result.stdout(), Checksums.MANIFEST_NAME)
                    .endsWith(Files.size(manifest) + " bytes"), result.stdout());
        }
    }

    @Test
    void aRunThatCannotFinishLeavesNoHalfWrittenOutput() throws Exception {
        try (ScratchKeyring keyring = ScratchKeyring.create()) {
            Path writable = Files.createDirectories(keyring.work().resolve("first"));
            Path locked = Files.createDirectories(keyring.work().resolve("second"));
            Files.writeString(writable.resolve("alpha.jar"), "alpha\n", StandardCharsets.UTF_8);
            // The second file exists and is readable; it is its directory that refuses new files, which
            // is the one kind of failure that cannot be caught before the first signature is made.
            Files.writeString(locked.resolve("beta.jar"), "beta\n", StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"));
            Assumptions.assumeFalse(Files.isWritable(locked),
                    "this filesystem does not enforce directory permissions (running as root?)");

            Cli.Result result = keyring.cli().run("sign",
                    writable.resolve("alpha.jar").toString(), locked.resolve("beta.jar").toString());

            assertEquals(1, result.exitCode(), result.combined());
            assertFalse(Files.exists(writable.resolve("alpha.jar.asc")),
                    "the first signature was already written, so a failed run left a signed artifact "
                            + "beside nothing to check it against");
            try (var entries = Files.list(writable)) {
                assertTrue(entries.noneMatch(entry -> entry.getFileName().toString().endsWith(".tmp")),
                        "a temporary file was left behind");
            }
        }
    }

    @Test
    void verifyAcceptsAGoodSignatureAndNamesTheSigningKeyInFull() {
        try (ScratchKeyring keyring = ScratchKeyring.create()) {
            keyring.write("alpha-1.0.jar", "alpha\n");
            assertEquals(0, keyring.cli().run("sign", "alpha-1.0.jar").exitCode());

            Cli.Result result = keyring.cli().run("verify", "alpha-1.0.jar");

            assertEquals(0, result.exitCode(), result.combined());
            assertTrue(result.stdout().contains(keyring.fingerprint()),
                    "verify should name the full fingerprint it trusted:\n" + result.stdout());
        }
    }

    @Test
    void verifyRejectsAFileThatChangedAfterItWasSignedAndSaysWhichFile() throws Exception {
        try (ScratchKeyring keyring = ScratchKeyring.create()) {
            keyring.write("alpha-1.0.jar", "alpha\n");
            assertEquals(0, keyring.cli().run("sign", "alpha-1.0.jar").exitCode());

            Path artifact = keyring.work().resolve("alpha-1.0.jar");
            Files.writeString(artifact, "alphb\n", StandardCharsets.UTF_8);

            Cli.Result result = keyring.cli().run("verify", "alpha-1.0.jar");

            assertEquals(1, result.exitCode(), result.combined());
            assertTrue(result.stderr().contains("alpha-1.0.jar"), result.stderr());
            assertTrue(result.stdout().contains("BAD SIGNATURE"), result.stdout());
        }
    }

    @Test
    void verifyNamesEveryManifestEntryThatDoesNotMatch() throws Exception {
        try (ScratchKeyring keyring = ScratchKeyring.create()) {
            keyring.write("alpha-1.0.jar", "alpha\n");
            keyring.write("beta-1.0.jar", "beta\n");
            // Signed first and separately, because `verify` always checks the detached signature over
            // the file it was given; the manifest is the second question, and this test is about that
            // one. The manifest below is then written by hand, so it deliberately does not match it.
            assertEquals(0, keyring.cli().run("sign", "alpha-1.0.jar").exitCode());
            Files.writeString(keyring.work().resolve(Checksums.MANIFEST_NAME),
                    sha256(keyring.work().resolve("alpha-1.0.jar")) + "  alpha-1.0.jar\n"
                            + "0000000000000000000000000000000000000000000000000000000000000000  beta-1.0.jar\n"
                            + sha256(keyring.work().resolve("alpha-1.0.jar")) + "  gamma-1.0.jar\n",
                    StandardCharsets.UTF_8);

            Cli.Result result = keyring.cli().run("verify", "alpha-1.0.jar",
                    "--checksums", Checksums.MANIFEST_NAME);

            assertEquals(1, result.exitCode(), result.combined());
            assertTrue(result.stderr().contains("beta-1.0.jar"), result.stderr());
            assertTrue(result.stderr().contains("gamma-1.0.jar"), result.stderr());
            // alpha matched, so it must not be accused of anything.
            assertFalse(result.stderr().contains("alpha-1.0.jar:"), result.stderr());
        }
    }

    @Test
    void verifySaysItCannotCheckRatherThanBlamingTheFileWhenThePublicKeyIsMissing() throws Exception {
        try (ScratchKeyring keyring = ScratchKeyring.create()) {
            keyring.write("alpha-1.0.jar", "alpha\n");
            assertEquals(0, keyring.cli().run("sign", "alpha-1.0.jar").exitCode());

            Path strangerHome = Files.createDirectories(keyring.work().resolve("stranger-keyring"));
            Files.setPosixFilePermissions(strangerHome, PosixFilePermissions.fromString("rwx------"));

            Cli.Result result = new Cli(keyring.work(), strangerHome).run("verify", "alpha-1.0.jar");

            assertEquals(1, result.exitCode(), result.combined());
            assertTrue(result.stdout().contains("not in this keyring"),
                    "a signature that cannot be checked is not a bad signature:\n" + result.stdout());
            assertFalse(result.stdout().contains("BAD SIGNATURE"), result.stdout());
        }
    }
}
