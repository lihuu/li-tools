package io.github.lihuu.gpgkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code gpgkit restore} — SPEC §2.
 *
 * <p>A restore is not finished when the files are in place. It is finished when the key that came
 * back has signed something and that signature verified (SPEC §3.3): an import can succeed with
 * material that cannot really sign, and the machine this matters on is a machine nobody is
 * watching, being set up months after the key was last used. Every step is therefore reported by
 * name, and a failure says which step broke — decryption, import, ownertrust, or the sign/verify
 * round trip — because "restore failed" leaves nothing to act on.
 */
final class RestoreCommand {
    private static final String USAGE = """
            usage: gpgkit restore FILE [--passphrase-file F] [--keyring DIR] [--yes]""";

    private final GlobalOptions global;

    RestoreCommand(GlobalOptions global) {
        this.global = global;
    }

    int run(List<String> args) {
        try {
            return execute(args);
        } catch (UsageError usage) {
            System.err.println("gpgkit: restore: " + usage.getMessage());
            System.err.println(USAGE);
            return Defaults.EXIT_USAGE;
        }
    }

    private int execute(List<String> args) {
        Path backupFile = null;
        Path passphraseFile = null;
        Path keyring = null;
        boolean assumeYes = false;
        for (int index = 0; index < args.size(); index++) {
            String argument = args.get(index);
            if (argument.equals("--passphrase-file")) {
                passphraseFile = Path.of(value(args, index, "--passphrase-file"));
                index++;
            } else if (argument.equals("--keyring")) {
                keyring = Path.of(value(args, index, "--keyring"));
                index++;
            } else if (argument.equals("--yes")) {
                assumeYes = true;
            } else if (argument.startsWith("-")) {
                throw new UsageError("unknown option: " + argument);
            } else if (backupFile == null) {
                backupFile = Path.of(argument);
            } else {
                throw new UsageError("restore takes one backup file, and was given a second: " + argument);
            }
        }
        if (backupFile == null) {
            throw new UsageError("restore needs the backup file to restore");
        }
        if (!Files.isReadable(backupFile) || Files.isDirectory(backupFile)) {
            throw new Failure("no readable backup file at " + backupFile
                    + "; `gpgkit backup` prints the exact path to restore from, and manifest.json names it too");
        }

        Gpg gpg = new Gpg();
        if (!gpg.available()) {
            throw new Failure("gpg is not on PATH; install GnuPG first");
        }
        Gpg target = keyring == null ? gpg : new Gpg(prepareKeyring(keyring));
        Passphrase.Source passphrase = resolvePassphrase(passphraseFile);

        List<KeyInfo> restored = new ArrayList<>();
        List<String> signed = new ArrayList<>();
        int trustEntries;
        try (TempDir scratch = TempDir.create()) {
            Path payload = scratch.path().resolve("payload.asc");
            decrypt(target, backupFile, payload, passphrase);
            report("decrypt", "OK: " + backupFile + " decrypted (" + size(payload) + " bytes of export)");

            List<String> fingerprints = secretFingerprints(target, payload);
            if (fingerprints.isEmpty()) {
                throw new Failure("this backup decrypted, but it holds no private key: nothing here could ever sign."
                        + " It may be a backup of the public half alone");
            }
            refuseToOverwrite(target, fingerprints, assumeYes);

            List<String> trust = BackupPayload.ownertrustLines(payload);
            importPayload(target, payload);
            report("import", "OK: " + fingerprints.size() + " secret key(s) imported into " + keyringName(keyring));
            trustEntries = importOwnertrust(target, trust, scratch.path());
            report("ownertrust", trustEntries == 0
                    ? "none in this backup; trust values for these keys stay as they are"
                    : "OK: " + trustEntries + " trust entr" + (trustEntries == 1 ? "y" : "ies") + " restored");

            Keyring keyringReader = new Keyring(target);
            for (String fingerprint : fingerprints) {
                KeyInfo key = find(keyringReader.list(), fingerprint);
                if (key == null || !key.secret()) {
                    throw new Failure("the import reported success, but " + fingerprint
                            + " is not in the keyring as a private key; the sign/verify check cannot run,"
                            + " and this keyring must not be treated as restored");
                }
                restored.add(key);
            }

            for (KeyInfo key : restored) {
                SignVerify.Verified verified = SignVerify.roundTrip(target, key, scratch.path());
                signed.add(verified.keyId());
                report("sign/verify", "OK: signed a temporary payload with " + key.fingerprint()
                        + " and verified the signature back (" + verified.bytes() + " bytes)");
            }
        }

        for (KeyInfo key : restored) {
            report("fingerprint", key.fingerprint());
            for (String userId : key.userIds()) {
                report("uid", userId);
            }
            if (key.expiresEpoch() == null) {
                report("expires", "never");
            } else {
                report("expires", Dates.localDate(key.expiresEpoch()) + " (in " + Dates.daysRemaining(key.expiresEpoch()) + " days)");
            }
        }
        for (String next : nextSteps(restored)) {
            report("next", next);
        }

        if (global.json()) {
            printJson(restored, keyring, signed, trustEntries);
        } else if (global.quiet()) {
            for (KeyInfo key : restored) {
                System.out.println(key.fingerprint());
            }
        }
        return Defaults.EXIT_OK;
    }

    /**
     * Decrypts the backup, naming this step when it fails.
     *
     * <p>The likely causes are worth spelling out here rather than in a manual: a wrong passphrase
     * and a damaged ciphertext both land on this line, and manifest.json is the file that says
     * which copy was taken when and what its sha256 was.
     */
    private static void decrypt(Gpg gpg, Path backupFile, Path payload, Passphrase.Source passphrase) {
        List<String> arguments = new ArrayList<>(List.of("--decrypt", "--output", payload.toString()));
        arguments.addAll(passphrase.arguments());
        arguments.add(backupFile.toString());
        Gpg.Result result = gpg.run(arguments, passphrase.stdin());
        if (!result.ok()) {
            throw new Failure("decryption failed: " + GpgOutput.reason(result) + ". Either the passphrase in "
                    + passphrase.file() + " is not the one this backup was encrypted with, or the ciphertext is"
                    + " damaged — compare its sha256 with the one in " + Defaults.MANIFEST_NAME + " beside it."
                    + " Nothing was imported");
        }
    }

    /**
     * The fingerprints the payload holds private keys for.
     *
     * <p>Asked with {@code --import-options show-only}, which makes gpg describe what it would
     * import without touching the keyring. Reading the header gpgkit writes instead would be
     * cheaper and wrong: a backup made by hand from {@code gpg --export-secret-keys} has no header,
     * and it is exactly the kind of backup someone restores once, in a hurry.
     */
    private static List<String> secretFingerprints(Gpg gpg, Path payload) {
        Gpg.Result result = gpg.run(List.of(
                "--with-colons", "--import-options", "show-only", "--import", payload.toString()));
        if (!result.ok()) {
            throw new Failure("gpg could not read the decrypted backup: " + result.reason());
        }
        List<String> fingerprints = new ArrayList<>();
        boolean secretBlock = false;
        for (String line : result.out().split("\n")) {
            String[] fields = line.split(":", -1);
            if (fields[0].equals("sec")) {
                secretBlock = true;
            } else if (fields[0].equals("fpr") && secretBlock) {
                fingerprints.add(fields[9]);
                secretBlock = false;
            }
        }
        return fingerprints;
    }

    /**
     * Refuses to import over a private key that is already in the target keyring unless
     * {@code --yes} says that is what was meant.
     *
     * <p>Restoring onto the machine the key came from is the one case where this command has
     * nothing to fix and something to disturb. Nothing is damaged either way — gpg merges rather
     * than replaces — so this is a question asked before the fact, not a safety mechanism
     * pretending to be one.
     */
    private static void refuseToOverwrite(Gpg target, List<String> fingerprints, boolean assumeYes) {
        if (assumeYes) {
            return;
        }
        List<KeyInfo> present = new Keyring(target).list();
        for (String fingerprint : fingerprints) {
            KeyInfo key = find(present, fingerprint);
            if (key != null && key.secret()) {
                throw new Failure("this keyring already has the private half of " + fingerprint
                        + ". Restoring it again changes nothing; if that is what you meant, pass --yes."
                        + " `gpgkit status` shows what is here");
            }
        }
    }

    private static void importPayload(Gpg target, Path payload) {
        Gpg.Result result = target.run(List.of("--import", payload.toString()));
        if (!result.ok()) {
            throw new Failure("the import failed: " + result.reason()
                    + ". The backup decrypted, so the ciphertext and the passphrase are right;"
                    + " gpg itself refused the key material");
        }
    }

    /** Restores the trust entries the backup carries, one file of them, and says how many landed. */
    private static int importOwnertrust(Gpg target, List<String> trust, Path scratch) {
        if (trust.isEmpty()) {
            return 0;
        }
        Path file = scratch.resolve("ownertrust.txt");
        try {
            Files.write(file, trust, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException failure) {
            throw new Failure("could not stage the ownertrust entries: " + failure.getMessage());
        }
        Gpg.Result result = target.run(List.of("--import-ownertrust", file.toString()));
        if (!result.ok()) {
            throw new Failure("the ownertrust import failed: " + result.reason()
                    + ". The keys are imported; only the trust values are missing");
        }
        return trust.size();
    }

    private Passphrase.Source resolvePassphrase(Path passphraseFile) {
        if (passphraseFile != null) {
            return Passphrase.fromFile(passphraseFile);
        }
        Path standard = Defaults.passphraseFile();
        if (Files.exists(standard)) {
            return Passphrase.fromFile(standard);
        }
        throw new Failure("this backup is encrypted, and no passphrase file was named: pass --passphrase-file F,"
                + " set " + Defaults.PASSPHRASE_FILE_ENV + ", or put one at " + standard
                + ". manifest.json beside the ciphertext records where the passphrase was put");
    }

    private static Path prepareKeyring(Path keyring) {
        try {
            if (!Files.exists(keyring)) {
                Files.createDirectories(keyring);
            }
            Files.setPosixFilePermissions(keyring, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException notPosix) {
            // gpg warns about a keyring others can read; refusing to restore over a filesystem
            // without POSIX permissions would be the worse answer.
        } catch (IOException failure) {
            throw new Failure("could not use " + keyring + " as a keyring: " + failure.getMessage());
        }
        if (!Files.isDirectory(keyring)) {
            throw new Failure(keyring + " is not a directory");
        }
        return keyring;
    }

    private static KeyInfo find(List<KeyInfo> keys, String fingerprint) {
        for (KeyInfo key : keys) {
            if (key.fingerprint().equalsIgnoreCase(fingerprint)) {
                return key;
            }
        }
        return null;
    }

    private static List<String> nextSteps(List<KeyInfo> restored) {
        List<String> steps = new ArrayList<>();
        for (KeyInfo key : restored) {
            steps.add("git config --global user.signingkey " + key.fingerprint()
                    + "  (then sign one real commit and check it with git log --show-signature)");
        }
        steps.add("gpgkit publish  — the keyserver still has the old public key for this fingerprint");
        steps.add("gpgkit doctor  — confirm this machine can really sign and really publish");
        return steps;
    }

    private String keyringName(Path keyring) {
        return keyring == null ? "this machine's default keyring" : keyring.toString();
    }

    private void printJson(List<KeyInfo> restored, Path keyring, List<String> signed, int trustEntries) {
        Json json = new Json();
        json.beginObject();
        json.name("fingerprint").beginArray();
        for (KeyInfo key : restored) {
            json.value(key.fingerprint());
        }
        json.endArray();
        json.name("userIds").beginArray();
        for (KeyInfo key : restored) {
            for (String userId : key.userIds()) {
                json.value(userId);
            }
        }
        json.endArray();
        json.name("keyring").value(keyringName(keyring));
        json.name("ownertrustEntries").value(trustEntries);
        json.name("signedAndVerified").beginArray();
        for (String keyId : signed) {
            json.value(keyId);
        }
        json.endArray();
        json.name("nextSteps").beginArray();
        for (String step : nextSteps(restored)) {
            json.value(step);
        }
        json.endArray();
        json.endObject();
        System.out.println(json);
    }

    private void report(String label, String detail) {
        if (global.json() || global.quiet()) {
            return;
        }
        System.out.printf("%-12s  %s%n", label, detail);
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException failure) {
            throw new Failure("could not measure " + file + ": " + failure.getMessage());
        }
    }

    private static String value(List<String> args, int index, String option) {
        if (index + 1 >= args.size()) {
            throw new UsageError(option + " needs a value");
        }
        return args.get(index + 1);
    }
}
