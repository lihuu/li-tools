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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code gpgkit gen}: the defaults SPEC §2 fixes, and the one property the whole project is arranged
 * around — the key it makes can sign with no passphrase and no terminal.
 */
class GenCommandTest {
    /** The full fingerprint and nothing shorter, because a short id cannot name a key to a keyserver. */
    private static String fingerprintFrom(Cli.Result result) {
        for (String line : result.stdout().split("\n")) {
            if (line.startsWith("generated")) {
                String candidate = line.substring("generated".length()).strip();
                assertTrue(candidate.matches("[0-9A-F]{40}"),
                        "the report must carry the full fingerprint, not a short id: " + line);
                return candidate;
            }
        }
        throw new AssertionError("no fingerprint in the report:\n" + result.combined());
    }

    /** A CLI whose git identity comes from a file this test wrote, not from the developer's own. */
    private static Cli withGitIdentity(ScratchKeyring keyring, Path home, String gitConfig) throws IOException {
        Path config = home.resolve("gitconfig");
        Files.writeString(config, gitConfig, StandardCharsets.UTF_8);
        return new Cli(keyring.work(), keyring.home(), null, Map.of(
                "HOME", home.toString(),
                "GIT_CONFIG_GLOBAL", config.toString(),
                "GIT_CONFIG_NOSYSTEM", "1"));
    }

    /**
     * Signs a payload with gpg directly, with no passphrase supplied anywhere.
     *
     * <p>This is the SPEC §4 property under test: a release pipeline signs headlessly through
     * {@code --pinentry-mode loopback}, so a key this command says has no passphrase has to be usable
     * by a process that has no way to answer a prompt.
     */
    private static Gpg.Result signHeadlessly(ScratchKeyring keyring, String fingerprint) {
        Path payload = keyring.write("payload.bin", "headless signing probe\n");
        return new Gpg(keyring.home()).run(List.of(
                "--armor", "--detach-sign", "--local-user", fingerprint,
                "--output", payload.resolveSibling("payload.bin.asc").toString(),
                payload.toString()));
    }

    @Test
    void theDefaultsAreRsa4096TwoYearsAndNoPassphrase(@TempDir Path home) throws IOException {
        try (ScratchKeyring keyring = ScratchKeyring.create()) {
            Cli.Result result = withGitIdentity(keyring, home,
                    "[user]\n\tname = Default Probe\n\temail = default@example.invalid\n")
                    .run("gen");

            assertEquals(0, result.exitCode(), result.combined());
            String fingerprint = fingerprintFrom(result);
            assertTrue(result.stdout().contains("rsa4096"), result.stdout());
            assertTrue(result.stdout().contains("default@example.invalid"), result.stdout());
            // SPEC §2: the report has to send the user to `backup` before they walk away, because an
            // unprotected private key that exists in one place is one disk failure from being gone.
            assertTrue(result.stdout().contains("backup"), result.stdout());

            Long expires = null;
            for (KeyInfo key : new Keyring(new Gpg(keyring.home())).list()) {
                if (key.fingerprint().equals(fingerprint)) {
                    expires = key.expiresEpoch();
                }
            }
            assertNotNull(expires, "the key the report named is not in the keyring");
            long days = Dates.daysRemaining(expires);
            // Two years, and 'y' in gpg means 365 days each — 730, give or take the leap day the
            // exact answer depends on. Anything outside this band is a different expiry.
            assertTrue(days >= 729 && days <= 731,
                    "expected roughly two years, got " + days + " days (" + Dates.localDate(expires) + ")");

            assertEquals(0, signHeadlessly(keyring, fingerprint).exitCode(),
                    "a key generated with the defaults must sign without a passphrase or a terminal");
        }
    }

    @Test
    void theUidDefaultsToTheGitIdentity(@TempDir Path home) throws IOException {
        try (ScratchKeyring keyring = ScratchKeyring.create()) {
            Cli.Result result = withGitIdentity(keyring, home,
                    "[user]\n\tname = Config Probe\n\temail = config-probe@example.invalid\n")
                    .run("gen");

            assertEquals(0, result.exitCode(), result.combined());
            assertTrue(result.stdout().contains("Config Probe <config-probe@example.invalid>"),
                    "the uid should have come from git config:\n" + result.stdout());
        }
    }

    @Test
    void genSaysWhatToDoWhenThereIsNoIdentityToTakeAUidFrom(@TempDir Path home) throws IOException {
        try (ScratchKeyring keyring = ScratchKeyring.create()) {
            Cli.Result result = withGitIdentity(keyring, home, "[core]\n\tautocrlf = false\n").run("gen");

            assertEquals(1, result.exitCode(), result.combined());
            assertTrue(result.stderr().contains("--uid"),
                    "the failure should name the way out: " + result.stderr());
        }
    }

    @Test
    void anExplicitUidWinsOverTheGitIdentity(@TempDir Path home) throws IOException {
        try (ScratchKeyring keyring = ScratchKeyring.create()) {
            Cli.Result result = withGitIdentity(keyring, home,
                    "[user]\n\tname = Config Probe\n\temail = config-probe@example.invalid\n")
                    .run("gen", "--uid", "Release Bot <release@example.invalid>");

            assertEquals(0, result.exitCode(), result.combined());
            assertTrue(result.stdout().contains("Release Bot <release@example.invalid>"), result.stdout());
            assertTrue(!result.stdout().contains("Config Probe"), result.stdout());
        }
    }

    @Test
    void ed25519IsHonouredWhenAskedFor(@TempDir Path home) throws IOException {
        try (ScratchKeyring keyring = ScratchKeyring.create()) {
            Cli.Result result = withGitIdentity(keyring, home, "[user]\n\tname = Ed Probe\n\temail = ed@example.invalid\n")
                    .run("gen", "--algo", "ed25519", "--expire", "1y");

            assertEquals(0, result.exitCode(), result.combined());
            assertTrue(result.stdout().contains("ed25519"), result.stdout());
            assertEquals(0, signHeadlessly(keyring, fingerprintFrom(result)).exitCode());
        }
    }

    @Test
    void aKeyGeneratedWithAPassphraseCannotSignWithoutOne(@TempDir Path home) throws IOException {
        try (ScratchKeyring keyring = ScratchKeyring.create()) {
            Path passphrase = home.resolve("passphrase");
            Files.writeString(passphrase, "a passphrase the tool must never print\n", StandardCharsets.UTF_8);

            Cli.Result result = withGitIdentity(keyring, home, "[user]\n\tname = Locked Probe\n\temail = locked@example.invalid\n")
                    .run("gen", "--passphrase-file", passphrase.toString());

            assertEquals(0, result.exitCode(), result.combined());
            String fingerprint = fingerprintFrom(result);
            assertNotEquals(0, signHeadlessly(keyring, fingerprint).exitCode(),
                    "the passphrase was asked for, so signing without it must not succeed");
            assertTrue(!result.combined().contains("a passphrase the tool must never print"),
                    "the passphrase is not the tool's to print");
        }
    }
}
