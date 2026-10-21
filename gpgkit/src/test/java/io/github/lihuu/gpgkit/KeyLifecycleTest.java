package io.github.lihuu.gpgkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The checks SPEC §5 asks for, run against the real command line.
 *
 * <p>JUnit is not in this project and will not be: the build has no dependencies on purpose, and
 * one test framework is a reflection surface in the native image for a handful of assertions. So
 * the harness is a class with a main, run with
 *
 * <pre>mvn -q package &amp;&amp; java -cp target/classes:target/test-classes io.github.lihuu.gpgkit.KeyLifecycleTest</pre>
 *
 * <p>Every case goes through {@code Main} in a child JVM over a throwaway {@code GNUPGHOME} inside
 * a {@code -Duser.home} sandbox. Three things follow from that, and all three are the point: the
 * developer's own keys are never in the same keyring as a test, the backup directory and the
 * OneDrive path resolve inside the sandbox rather than into someone's real home, and the exit codes
 * asserted below are the ones a shell would see — not a return value read by a test harness that
 * shares a JVM with the code under test.
 */
public final class KeyLifecycleTest {
    private static final List<String> FAILURES = new ArrayList<>();
    private static int checks;

    private Path home;
    private Path keyring;
    private Path oneDrive;

    public static void main(String[] args) throws IOException {
        if (Gpg.locate().isEmpty()) {
            // Refusing to run is the honest answer, and not a failure: everything here needs gpg,
            // and there is nothing to assert about a tool whose engine is missing.
            System.out.println("SKIP: gpg is not on PATH, so nothing here can be checked");
            return;
        }

        try (TempDir sandbox = TempDir.create()) {
            KeyLifecycleTest test = new KeyLifecycleTest();
            test.prepare(sandbox.path());
            test.runAll();
        }
        System.out.println();
        System.out.println(checks + " checks, " + FAILURES.size() + " failed");
        for (String failure : FAILURES) {
            System.out.println("FAILED: " + failure);
        }
        System.exit(FAILURES.isEmpty() ? 0 : 1);
    }

    private void prepare(Path sandbox) throws IOException {
        home = sandbox;
        keyring = sandbox.resolve("gnupg");
        Files.createDirectory(keyring);
        Files.setPosixFilePermissions(keyring, PosixFilePermissions.fromString("rwx------"));
        // The onedrive: destination resolves under the sandboxed user.home, so a test backup can
        // never reach the real OneDrive directory — it does not even resolve to it.
        oneDrive = sandbox.resolve("Library/CloudStorage/OneDrive-Personal");
        Files.createDirectories(oneDrive);
    }

    private void runAll() throws IOException {
        String fingerprint = createKey(keyring, "Kit Test <kit@invalid>", "2y");
        backupWritesTwoCopiesAndAManifest(fingerprint);
        loopbackDetectsACorruptedCiphertext(fingerprint);
        restoreOfACorruptedCiphertextFailsWithoutImporting(fingerprint);
        restoreSignsAndVerifies(fingerprint);
        restoreOfAKeyThatCannotSignFails(fingerprint);
        extendMovesTheExpiryAndKeepsTheFingerprint(fingerprint);
        usageErrorsExitTwo();
        aKeyringWithNothingInItIsAFailureNotACrash();
    }

    /** SPEC §2 steps 1-6: two destinations, a manifest beside each, and a copyable restore line. */
    private void backupWritesTwoCopiesAndAManifest(String fingerprint) throws IOException {
        Path backups = home.resolve("backups");
        Path passphrase = home.resolve("passphrase.txt");
        Result backup = cli("backup", "--out", backups.toString(),
                "--dest", "onedrive:gpgkit",
                "--generate-passphrase-to", passphrase.toString());

        check("backup exits 0", backup.exitCode() == 0, backup.describe());
        check("backup prints the loopback line", backup.stdout().contains("loopback")
                && backup.stdout().contains("byte for byte"), backup.stdout());

        List<Path> ciphertexts = ciphertextsIn(backups);
        List<Path> copies = ciphertextsIn(oneDrive.resolve("gpgkit"));
        check("the local destination holds one ciphertext", ciphertexts.size() == 1, ciphertexts.toString());
        check("the onedrive destination holds one ciphertext", copies.size() == 1, copies.toString());
        if (ciphertexts.isEmpty() || copies.isEmpty()) {
            return;
        }

        Path ciphertext = ciphertexts.get(0);
        check("both copies are the same bytes",
                java.util.Arrays.equals(Files.readAllBytes(ciphertext), Files.readAllBytes(copies.get(0))),
                "the two copies differ");

        Path manifest = backups.resolve(Defaults.MANIFEST_NAME);
        check("a manifest sits beside each copy",
                Files.exists(manifest) && Files.exists(oneDrive.resolve("gpgkit").resolve(Defaults.MANIFEST_NAME)),
                "manifest.json missing");
        String document = Files.readString(manifest, StandardCharsets.UTF_8);
        for (String key : List.of("fingerprint", "userIds", "algorithm", "created", "expires", "cipher",
                "sha256", "backedUpAt", "passphraseStorage", "restoreCommand")) {
            check("the manifest records " + key, document.contains("\"" + key + "\""), document);
        }
        check("the manifest names the key", document.contains(fingerprint), document);
        check("the manifest records AES256",
                document.contains("\"cipher\": \"AES256\"") || document.contains("\"cipher\":\"AES256\""), document);
        check("the manifest's sha256 is the ciphertext's sha256",
                document.contains("\"sha256\": \"" + sha256Of(ciphertext) + "\"")
                        || document.contains("\"sha256\":\"" + sha256Of(ciphertext) + "\""),
                "manifest says " + document + ", openssl says " + sha256Of(ciphertext));
        check("the manifest records a restore command for its own copy",
                document.contains(ciphertext.getFileName().toString()), document);

        check("backup prints a restore command to copy", backup.stdout().contains("gpgkit restore ")
                && backup.stdout().contains(ciphertext.toString()), backup.stdout());
        check("the ciphertext and the passphrase are not in the output",
                !backup.stdout().contains("BEGIN PGP PRIVATE KEY BLOCK")
                        && !backup.stdout().contains("BEGIN PGP SECRET KEY BLOCK")
                        && !backup.stdout().contains(Files.readString(passphrase, StandardCharsets.UTF_8).strip()),
                "the report leaked key material or the passphrase");
        check("the passphrase file is readable by its owner only",
                PosixFilePermissions.toString(Files.getPosixFilePermissions(passphrase)).equals("rw-------"),
                PosixFilePermissions.toString(Files.getPosixFilePermissions(passphrase)));
    }

    /**
     * SPEC §5 ①: the loopback check has to notice a ciphertext that came back wrong.
     *
     * <p>Both halves are checked: corrupted input must be refused, and the untouched ciphertext
     * must pass — a check that fails everything would satisfy the first half alone.
     */
    private void loopbackDetectsACorruptedCiphertext(String fingerprint) throws IOException {
        Path ciphertext = ciphertextsIn(home.resolve("backups")).get(0);
        Path passphrase = home.resolve("passphrase.txt");
        Gpg gpg = new Gpg(keyring);
        Passphrase.Source source = Passphrase.fromFile(passphrase);
        Path export = home.resolve("export.asc");
        BackupPayload.write(gpg, new Keyring(gpg).list(), export);

        try (TempDir scratch = TempDir.create()) {
            byte[] good = Files.readAllBytes(ciphertext);
            Loopback.Verified verified = Loopback.check(gpg, good, export, source, scratch.path());
            check("the loopback check passes an untouched ciphertext", verified.bytes() > 0,
                    "the check refused a ciphertext it had just produced");

            byte[] corrupted = good.clone();
            corrupted[corrupted.length / 2] ^= 0x01;
            check("the loopback check refuses a ciphertext with one byte changed",
                    refusedByLoopback(gpg, corrupted, export, source, scratch.path()),
                    "a damaged ciphertext passed the loopback check, or failed for another reason");
        }
    }

    /** SPEC §5 ③ and §3.4: a failure must leave the target keyring exactly as it was. */
    private void restoreOfACorruptedCiphertextFailsWithoutImporting(String fingerprint) throws IOException {
        Path good = ciphertextsIn(home.resolve("backups")).get(0);
        Path corrupted = home.resolve("corrupted.asc.gpg");
        byte[] bytes = Files.readAllBytes(good);
        bytes[bytes.length / 2] ^= 0x01;
        Files.write(corrupted, bytes);

        Path target = home.resolve("keyring-from-corrupt");
        Result restore = cli("restore", corrupted.toString(), "--keyring", target.toString(),
                "--passphrase-file", home.resolve("passphrase.txt").toString());
        check("restoring a corrupted ciphertext exits 1", restore.exitCode() == 1, restore.describe());
        check("the failure names decryption", restore.stderr().contains("decryption failed"), restore.describe());
        check("nothing was imported from a corrupted ciphertext",
                secretKeysIn(target).isEmpty(), "the target keyring gained a key: " + secretKeysIn(target));
    }

    /** SPEC §5 ② and §3.3: a restore is only done when the key has really signed something. */
    private void restoreSignsAndVerifies(String fingerprint) throws IOException {
        Path target = home.resolve("keyring-restored");
        Result restore = cli("restore", ciphertextsIn(home.resolve("backups")).get(0).toString(),
                "--keyring", target.toString(),
                "--passphrase-file", home.resolve("passphrase.txt").toString());
        check("restore into an empty keyring exits 0", restore.exitCode() == 0, restore.describe());
        check("restore reports the sign/verify round trip",
                restore.stdout().contains("sign/verify") && restore.stdout().contains("verified the signature back"),
                restore.stdout());
        check("restore reports the restored fingerprint", restore.stdout().contains(fingerprint), restore.stdout());

        List<String> restored = secretKeysIn(target);
        check("the restored keyring holds the private key", restored.contains(fingerprint), restored.toString());

        // Checked again with gpg alone, outside this tool: if the keyring only works through the
        // path that just claimed it works, the claim is worth nothing.
        Gpg restoredGpg = new Gpg(target);
        Path payload = home.resolve("outside-payload.txt");
        Path signature = home.resolve("outside-payload.txt.asc");
        Files.writeString(payload, "signed outside gpgkit\n", StandardCharsets.UTF_8);
        Gpg.Result sign = restoredGpg.run(List.of("--local-user", fingerprint, "--armor",
                "--detach-sign", "--output", signature.toString(), payload.toString()));
        check("the restored keyring signs with gpg directly", sign.ok(), sign.reason());
        Gpg.Result verify = restoredGpg.run(List.of("--verify", signature.toString(), payload.toString()));
        check("that signature verifies with gpg directly", verify.ok(), verify.reason());
    }

    /**
     * The half-done restore: the key imports, and it still cannot sign.
     *
     * <p>Built with an expired key, which is a key that is present, has its private half, and is
     * refused by gpg at signing time. This is the case the acceptance check exists for: every step
     * before it reports success.
     */
    private void restoreOfAKeyThatCannotSignFails(String fingerprint) throws IOException {
        Path expiredKeyring = home.resolve("gnupg-expired");
        Files.createDirectory(expiredKeyring);
        Files.setPosixFilePermissions(expiredKeyring, PosixFilePermissions.fromString("rwx------"));
        String expired = createKey(expiredKeyring, "Kit Expired <expired@invalid>", "seconds=2");
        sleep(3);

        Path backups = home.resolve("expired-backups");
        // Run in the keyring that holds the expired key: a backup backs up the keyring it is
        // pointed at, and pointing this at the other fixture keyring would test nothing.
        Result backup = cliIn(expiredKeyring, "backup", "--key", expired, "--out", backups.toString(),
                "--generate-passphrase-to", home.resolve("expired-passphrase.txt").toString());
        check("an expired key can still be exported and backed up", backup.exitCode() == 0, backup.describe());
        List<Path> ciphertexts = ciphertextsIn(backups);
        if (ciphertexts.isEmpty()) {
            check("the expired key's backup produced a ciphertext", false, backup.describe());
            return;
        }

        Path target = home.resolve("keyring-expired");
        Result restore = cli("restore", ciphertexts.get(0).toString(), "--keyring", target.toString(),
                "--passphrase-file", home.resolve("expired-passphrase.txt").toString());
        check("a restore that cannot sign exits 1", restore.exitCode() == 1, restore.describe());
        check("the failure names the sign/verify step", restore.stderr().contains("cannot sign"), restore.describe());
        check("the restore does not claim the sign/verify round trip",
                !restore.stdout().contains("verified the signature back"), restore.stdout());
        check("the key did import, so the failure is the sign step and not the import",
                secretKeysIn(target).contains(expired), secretKeysIn(target).toString());
    }

    /** SPEC §2 `extend`: the dates move, the fingerprint does not. */
    private void extendMovesTheExpiryAndKeepsTheFingerprint(String fingerprint) throws IOException {
        long before = primaryExpiryOf(fingerprint);
        Result extend = cli("extend", "--expire", "4y");
        check("extend exits 0", extend.exitCode() == 0, extend.describe());
        check("extend prints old to new", extend.stdout().contains("→"), extend.stdout());
        check("extend keeps the fingerprint", extend.stdout().contains(fingerprint), extend.stdout());
        check("extend prints the date gpg actually held before the change",
                extend.stdout().contains(Dates.localDate(before)), extend.stdout());

        long after = primaryExpiryOf(fingerprint);
        check("the key now expires later than it did", after > before,
                "before " + Dates.localDate(before) + ", after " + Dates.localDate(after));
        check("the printed new date is the date gpg holds",
                extend.stdout().contains(Dates.localDate(after)), extend.stdout());

        Result quiet = cli("--quiet", "extend", "--expire", "5y");
        check("a second renewal moves the date again",
                quiet.exitCode() == 0 && primaryExpiryOf(fingerprint) > after,
                "quiet said: " + quiet.describe());
    }

    /** SPEC §2: 2 is a usage error, and 1 is an operation that did not work. */
    private void usageErrorsExitTwo() throws IOException {
        check("an unknown option is a usage error",
                cli("backup", "--no-verify").exitCode() == 2, "backup accepted an option SPEC does not define");
        check("a malformed destination is a usage error",
                cli("backup", "--dest", "dropbox:x").exitCode() == 2, "backup accepted an unknown destination");
        check("a relative file: destination is a usage error",
                cli("backup", "--dest", "file:relative/path").exitCode() == 2, "a relative path was accepted");
        check("a restore without a file is a usage error",
                cli("restore").exitCode() == 2, "restore ran without a backup file");
        check("an expiry gpg cannot parse is a usage error",
                cli("extend", "--expire", "soon").exitCode() == 2, "extend accepted `--expire soon`");
    }

    /** SPEC §3.6: a machine with no keys has to produce a sentence, not a stack trace. */
    private void aKeyringWithNothingInItIsAFailureNotACrash() throws IOException {
        Path empty = home.resolve("gnupg-empty");
        Files.createDirectory(empty);
        Result backup = cliIn(empty, "backup", "--out", home.resolve("nothing").toString());
        check("backup in a keyring with no keys exits 1", backup.exitCode() == 1, backup.describe());
        check("it says there is no signing key rather than failing obscurely",
                backup.stderr().contains("no usable signing key"), backup.describe());
        check("nothing was written", !Files.exists(home.resolve("nothing")), "a destination was created anyway");
    }

    private Result cli(String... arguments) {
        return cliIn(keyring, arguments);
    }

    private Result cliIn(Path gnupgHome, String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Duser.home=" + home);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("io.github.lihuu.gpgkit.Main");
        command.addAll(List.of(arguments));

        ProcessBuilder builder = new ProcessBuilder(command);
        // The child's keyring and its idea of the user's home are the sandbox. The two GPGKIT
        // variables are removed rather than inherited: a developer who has them set would otherwise
        // change what these tests assert, which is the definition of a test that depends on whose
        // machine it runs on.
        builder.environment().put("GNUPGHOME", gnupgHome.toString());
        builder.environment().remove(Defaults.BACKUP_DIR_ENV);
        builder.environment().remove(Defaults.PASSPHRASE_FILE_ENV);

        try {
            Process process = builder.start();
            byte[] stdout;
            try (java.io.InputStream stream = process.getInputStream()) {
                stdout = stream.readAllBytes();
            }
            byte[] stderr;
            try (java.io.InputStream stream = process.getErrorStream()) {
                stderr = stream.readAllBytes();
            }
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("the command did not finish: " + command);
            }
            return new Result(process.exitValue(), new String(stdout, StandardCharsets.UTF_8),
                    new String(stderr, StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new IllegalStateException("could not run " + command + ": " + failure.getMessage(), failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running " + command, interrupted);
        }
    }

    /** Generates a throwaway key with an encryption subkey, in a keyring that is not the user's. */
    private static String createKey(Path gnupgHome, String userId, String expire) {
        Gpg gpg = new Gpg(gnupgHome);
        Gpg.Result generated = gpg.run(List.of("--passphrase", "", "--quick-generate-key",
                userId, "ed25519", "sign", expire));
        if (!generated.ok()) {
            throw new IllegalStateException("could not generate a fixture key: " + generated.reason());
        }
        String fingerprint = new Keyring(gpg).list().get(0).fingerprint();
        Gpg.Result subkey = gpg.run(List.of("--passphrase", "", "--quick-add-key",
                fingerprint, "cv25519", "encrypt", expire));
        if (!subkey.ok()) {
            throw new IllegalStateException("could not add a fixture subkey: " + subkey.reason());
        }
        return fingerprint;
    }

    private static boolean refusedByLoopback(Gpg gpg, byte[] ciphertext, Path export,
                                             Passphrase.Source passphrase, Path scratch) {
        try {
            Loopback.check(gpg, ciphertext, export, passphrase, scratch);
            return false;
        } catch (Failure expected) {
            return expected.getMessage().contains("loopback check failed");
        }
    }

    private static List<Path> ciphertextsIn(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (java.util.stream.Stream<Path> entries = Files.list(directory)) {
            return entries.filter(path -> path.getFileName().toString().endsWith(".asc.gpg")).sorted().toList();
        }
    }

    private static List<String> secretKeysIn(Path gnupgHome) {
        if (!Files.isDirectory(gnupgHome)) {
            return List.of();
        }
        Gpg gpg = new Gpg(gnupgHome);
        Gpg.Result result = gpg.run(List.of("--with-colons", "--list-secret-keys"));
        if (!result.ok()) {
            return List.of();
        }
        List<String> fingerprints = new ArrayList<>();
        boolean secret = false;
        for (String line : result.out().split("\n")) {
            String[] fields = line.split(":", -1);
            if (fields[0].equals("sec")) {
                secret = true;
            } else if (fields[0].equals("fpr") && secret) {
                fingerprints.add(fields[9]);
                secret = false;
            }
        }
        return fingerprints;
    }

    /** The primary key's expiry, asked of the sandbox keyring and never of the developer's. */
    private long primaryExpiryOf(String fingerprint) {
        Gpg.Result result = new Gpg(keyring).run(List.of("--with-colons", "--list-keys", fingerprint));
        for (String line : result.out().split("\n")) {
            String[] fields = line.split(":", -1);
            if (fields[0].equals("pub") && fields.length > 6 && !fields[6].isEmpty()) {
                return Long.parseLong(fields[6]);
            }
        }
        throw new IllegalStateException("gpg reported no expiry for " + fingerprint + ": " + result.out());
    }

    /** The ciphertext digest from openssl, so the manifest is checked against something else. */
    private static String sha256Of(Path file) {
        ProcessBuilder builder = new ProcessBuilder("openssl", "dgst", "-sha256", file.toString());
        try {
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("openssl did not finish");
            }
            // `openssl dgst` prints "SHA2-256(file)= <hex>" on older builds and
            // "SHA256(file)= <hex>" on newer ones; the digest is the last whitespace-separated word
            // either way, which is the only part worth depending on.
            String[] words = output.strip().split("\\s+");
            return words[words.length - 1];
        } catch (IOException failure) {
            throw new IllegalStateException("could not run openssl: " + failure.getMessage(), failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running openssl", interrupted);
        }
    }

    private static void sleep(long seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void check(String description, boolean passed, String detail) {
        checks++;
        if (passed) {
            System.out.println("PASS  " + description);
            return;
        }
        System.out.println("FAIL  " + description);
        System.out.println("      " + detail.strip().replace("\n", "\n      "));
        FAILURES.add(description);
    }

    /** What a shell would have seen. */
    private record Result(int exitCode, String stdout, String stderr) {
        String describe() {
            return "exit " + exitCode + "\nstdout:\n" + stdout + "\nstderr:\n" + stderr;
        }
    }
}
