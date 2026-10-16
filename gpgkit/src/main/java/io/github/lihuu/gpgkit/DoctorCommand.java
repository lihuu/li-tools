package io.github.lihuu.gpgkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * {@code gpgkit doctor} — SPEC §2.
 *
 * <p>One command that answers "can this machine still sign, and still publish". The rule that
 * shapes the whole file is that a check reports what it observed and never what it assumes:
 * "signing works" is not inferred from the presence of a key, it is established by signing
 * something and verifying the signature back. On a machine with no keys at all the checks still
 * run and still say something useful, which is the case SPEC §3.6 calls out.
 */
final class DoctorCommand {
    private static final Duration KEYSERVER_TIMEOUT = Duration.ofSeconds(10);

    /** Matches a server entry by id, and nothing else in the file. */
    private static final Pattern CENTRAL_SERVER =
            Pattern.compile("<\\s*id\\s*>\\s*central\\s*<\\s*/\\s*id\\s*>");

    private final GlobalOptions global;

    DoctorCommand(GlobalOptions global) {
        this.global = global;
    }

    int run(List<String> args) {
        if (!args.isEmpty()) {
            System.err.println("gpgkit: doctor takes no arguments");
            return Defaults.EXIT_USAGE;
        }

        Gpg gpg = new Gpg();
        List<Check> checks = new ArrayList<>();
        if (!gpg.available()) {
            // Everything below needs gpg, and five failures from one missing binary bury the one
            // thing the user has to fix. The maven check reads a file and still runs.
            checks.add(Check.fail("gpg", "not found on PATH; install GnuPG, then run this again"));
            checks.add(checkMavenCentral());
            return render(checks);
        }

        checks.add(checkGpg(gpg));
        List<KeyInfo> keys = new Keyring(gpg).list();
        KeyInfo signingKey = firstUsableSigningKey(keys);
        checks.add(checkSigningKey(signingKey, keys));
        checks.add(checkSignRoundTrip(gpg, signingKey));
        checks.add(checkBackup(gpg));
        checks.add(checkKeyserver(signingKey));
        checks.add(checkMavenCentral());
        return render(checks);
    }

    private static KeyInfo firstUsableSigningKey(List<KeyInfo> keys) {
        for (KeyInfo key : keys) {
            if (key.canSign()) {
                return key;
            }
        }
        return null;
    }

    private static Check checkGpg(Gpg gpg) {
        Gpg.Result result = gpg.run(List.of("--version"));
        if (!result.ok()) {
            return Check.fail("gpg", gpg.executable() + " would not report a version: " + result.reason());
        }
        for (String line : result.out().split("\n")) {
            if (line.startsWith("gpg (")) {
                // The version line already begins with "gpg", and the path in front of it already
                // names which gpg this is; keeping both reads as "/opt/homebrew/bin/gpg gpg
                // (GnuPG) 2.5.24", which looks like a bug in a line whose whole job is to be
                // believed.
                return Check.pass("gpg", gpg.executable() + " " + line.strip().substring(4));
            }
        }
        return Check.pass("gpg", gpg.executable().toString());
    }

    private static Check checkSigningKey(KeyInfo signingKey, List<KeyInfo> keys) {
        if (signingKey == null) {
            if (keys.isEmpty()) {
                return Check.fail("signing key", "this keyring is empty; run `gpgkit gen` to make a key");
            }
            return Check.fail("signing key", "none of the " + keys.size()
                    + " keys here has a usable private signing key; import the secret key, or run `gpgkit gen`");
        }
        Long days = signingKey.daysRemaining();
        String detail = signingKey.fingerprint()
                + (days == null ? " (no expiry)" : " (" + days + " days left)");
        if (days != null && days < Defaults.EXPIRY_WARN_DAYS) {
            return Check.warn("signing key", detail + "; expires within "
                    + Defaults.EXPIRY_WARN_DAYS + " days, so run `gpgkit extend` now");
        }
        return Check.pass("signing key", detail);
    }

    /**
     * Signs a throwaway payload and verifies the signature back.
     *
     * <p>This is the check worth having. A key that is present, unexpired and marked as a signing
     * key can still fail to sign — a stub whose smartcard is absent, an agent that wants a
     * passphrase nobody can supply, a gpg that cannot write its own keyring. Every one of those
     * passes a "do we have a signing key" test and fails the first real publish.
     *
     * <p>The signature is made over a temporary file, in a temporary directory, and neither is
     * printed: the point is the round trip, not the artifact.
     */
    private static Check checkSignRoundTrip(Gpg gpg, KeyInfo signingKey) {
        if (signingKey == null) {
            return Check.fail("sign/verify", "not attempted: there is no signing key to test with");
        }
        try (TempDir scratch = TempDir.create()) {
            Path payload = scratch.path().resolve("payload.txt");
            Path signature = scratch.path().resolve("payload.txt.asc");
            Files.writeString(payload, "gpgkit doctor " + Instant.now() + "\n", StandardCharsets.UTF_8);

            Gpg.Result sign = gpg.run(List.of(
                    "--local-user", signingKey.fingerprint(),
                    "--armor", "--detach-sign",
                    "--output", signature.toString(),
                    payload.toString()));
            if (!sign.ok()) {
                return Check.fail("sign/verify", "signing failed: " + sign.reason());
            }

            Gpg.Result verify = gpg.run(List.of("--verify", signature.toString(), payload.toString()));
            if (!verify.ok()) {
                return Check.fail("sign/verify", "the signature did not verify: " + verify.reason());
            }
            return Check.pass("sign/verify", "signed a temporary payload with " + signingKey.keyId()
                    + " and verified the signature back");
        } catch (IOException failure) {
            return Check.fail("sign/verify", "could not write the test payload: " + failure.getMessage());
        }
    }

    private static Check checkBackup(Gpg gpg) {
        Path directory = Defaults.backupDir();
        if (!Files.isDirectory(directory)) {
            return Check.warn("backup", "no backups at " + directory + "; run `gpgkit backup`");
        }

        List<Path> ciphertexts = new ArrayList<>();
        try (Stream<Path> entries = Files.list(directory)) {
            entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".gpg"))
                    .forEach(ciphertexts::add);
        } catch (IOException failure) {
            return Check.fail("backup", "could not read " + directory + ": " + failure.getMessage());
        }
        if (ciphertexts.isEmpty()) {
            return Check.warn("backup", "nothing encrypted in " + directory + "; run `gpgkit backup`");
        }
        ciphertexts.sort(Comparator.comparingLong(DoctorCommand::lastModified).reversed());

        Path passphraseFile = Defaults.passphraseFile();
        // No passphrase file is not a failed backup, only a check that cannot go further: the
        // ciphertext may well be fine and this machine simply is not where its passphrase lives.
        // The path is named so that "where would it be" is answered without reading the source.
        if (!Files.exists(passphraseFile)) {
            return Check.pass("backup", ciphertexts.size() + " backup(s) in " + directory
                    + "; not decrypted (no passphrase file at " + passphraseFile + ")");
        }
        if (!Files.isReadable(passphraseFile)) {
            return Check.fail("backup", passphraseFile + " exists but cannot be read");
        }

        Path newest = ciphertexts.get(0);
        Gpg.Result decrypt = gpg.run(List.of(
                "--passphrase-file", passphraseFile.toString(),
                "--decrypt", newest.toString()));
        if (!decrypt.ok()) {
            return Check.fail("backup", newest.getFileName() + " does not decrypt: " + decrypt.reason());
        }
        // A backup that decrypts is a backup that can be restored. Only the exit code is looked at:
        // stdout holds the private key block, and nothing in this program ever prints, logs or
        // writes it (SPEC §3.1). There is no way to check a backup without handling that material
        // for an instant, so the handling is confined to this one expression.
        return Check.pass("backup", ciphertexts.size() + " backup(s) in " + directory
                + "; the newest one decrypts");
    }

    private static Check checkKeyserver(KeyInfo signingKey) {
        if (signingKey == null) {
            return Check.warn("keyserver", "no key to look up; there is nothing to publish either");
        }
        Keyserver.Probe probe = Keyserver.probe(Defaults.KEYSERVER, signingKey.fingerprint(), KEYSERVER_TIMEOUT);
        return switch (probe.visibility()) {
            case VISIBLE -> Check.pass("keyserver", Defaults.KEYSERVER + " returned this key");
            case NOT_PUBLISHED -> Check.warn("keyserver", Defaults.KEYSERVER
                    + " does not know this key yet; run `gpgkit publish`");
            case UNREACHABLE -> Check.fail("keyserver", Defaults.KEYSERVER
                    + " did not answer (" + probe.detail() + "); try another keyserver, or check the network and any proxy");
        };
    }

    /**
     * Whether Maven Central's credentials are configured — and only whether.
     *
     * <p>Settings files hold an upload token in the same element this looks at the id of, so the
     * answer is a yes or a no and the file's contents never reach stdout, stderr, a message, or
     * this program's memory beyond the match (SPEC §3.1). Even the failure branch reports that the
     * file could not be read rather than what it said.
     */
    private static Check checkMavenCentral() {
        Path settings = Path.of(System.getProperty("user.home"), ".m2", "settings.xml");
        if (!Files.isReadable(settings)) {
            return Check.warn("maven-central", settings + " is not there; maven-gpg-plugin has no server to sign for");
        }
        try {
            String content = Files.readString(settings, StandardCharsets.UTF_8);
            if (CENTRAL_SERVER.matcher(content).find()) {
                return Check.pass("maven-central", settings + " declares a server with <id>central</id>");
            }
            return Check.warn("maven-central", settings + " has no server with <id>central</id>");
        } catch (IOException failure) {
            return Check.warn("maven-central", settings + " could not be read");
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException unreadable) {
            return 0L;
        }
    }

    private int render(List<Check> checks) {
        int failed = 0;
        int warned = 0;
        for (Check check : checks) {
            if (check.status().equals("FAIL")) {
                failed++;
            } else if (check.status().equals("WARN")) {
                warned++;
            }
        }
        String verdict = failed == 0 ? "OK" : "FAIL";

        if (global.json()) {
            Json json = new Json();
            json.beginObject();
            json.name("checks").beginArray();
            for (Check check : checks) {
                json.beginObject();
                json.name("name").value(check.name());
                json.name("status").value(check.status());
                json.name("detail").value(check.detail());
                json.endObject();
            }
            json.endArray();
            json.name("failed").value(failed);
            json.name("warned").value(warned);
            json.name("verdict").value(verdict);
            json.endObject();
            System.out.println(json);
        } else {
            for (Check check : checks) {
                if (global.quiet()) {
                    System.out.println(check.status() + "  " + check.name());
                } else {
                    System.out.printf("%-4s  %-14s  %s%n", check.status(), check.name(), check.detail());
                }
            }
            System.out.println("verdict: " + verdict + " (" + failed + " failed, " + warned + " warning"
                    + (warned == 1 ? ")" : "s)"));
        }
        // Warnings are worth reading and not worth failing on. Only a check that observed the
        // thing not working is a failure (SPEC §2).
        return failed == 0 ? Defaults.EXIT_OK : Defaults.EXIT_FAILURE;
    }

    private record Check(String status, String name, String detail) {
        static Check pass(String name, String detail) {
            return new Check("PASS", name, detail);
        }

        static Check warn(String name, String detail) {
            return new Check("WARN", name, detail);
        }

        static Check fail(String name, String detail) {
            return new Check("FAIL", name, detail);
        }
    }
}
