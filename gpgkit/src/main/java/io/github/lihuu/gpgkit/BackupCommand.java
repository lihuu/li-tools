package io.github.lihuu.gpgkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code gpgkit backup} — SPEC §2, and the command the rest of the tool exists to support.
 *
 * <p>The order of work is the contract, not an implementation detail: resolve and check everything
 * that can fail, export, encrypt, decrypt the ciphertext back and compare it with the export byte
 * for byte, and only then write anything to a destination (SPEC §3.2, §3.4). A backup that fails
 * leaves no half-written destination to be mistaken for a good one.
 *
 * <p>Three things this command deliberately does not offer. There is no way to skip the loopback
 * check, none to skip encryption, and no passphrase on the command line: SPEC §3 makes the first
 * two hard constraints, and a passphrase typed into a shell ends up in the shell history.
 */
final class BackupCommand {
    /** SPEC §2 step 2: the default and, without a flag, the only cipher. */
    private static final String CIPHER = "AES256";

    private static final String USAGE = """
            usage: gpgkit backup [--key <id|all>] [--out DIR]
                                 [--passphrase-file F | --generate-passphrase-to F]
                                 [--dest <spec>]...""";

    private final GlobalOptions global;

    BackupCommand(GlobalOptions global) {
        this.global = global;
    }

    int run(List<String> args) {
        try {
            return execute(args);
        } catch (UsageError usage) {
            System.err.println("gpgkit: backup: " + usage.getMessage());
            System.err.println(USAGE);
            return Defaults.EXIT_USAGE;
        }
    }

    private int execute(List<String> args) {
        String keyOption = null;
        Path out = null;
        Path passphraseFile = null;
        Path generateTo = null;
        List<String> destSpecs = new ArrayList<>();
        for (int index = 0; index < args.size(); index++) {
            String argument = args.get(index);
            if (argument.equals("--key")) {
                keyOption = value(args, index, "--key");
                index++;
            } else if (argument.equals("--out")) {
                out = Path.of(value(args, index, "--out"));
                index++;
            } else if (argument.equals("--passphrase-file")) {
                passphraseFile = Path.of(value(args, index, "--passphrase-file"));
                index++;
            } else if (argument.equals("--generate-passphrase-to")) {
                generateTo = Path.of(value(args, index, "--generate-passphrase-to"));
                index++;
            } else if (argument.equals("--dest")) {
                destSpecs.add(value(args, index, "--dest"));
                index++;
            } else {
                throw new UsageError("unknown option: " + argument);
            }
        }
        if (passphraseFile != null && generateTo != null) {
            throw new UsageError("--passphrase-file and --generate-passphrase-to cannot both be given;"
                    + " one says where the passphrase already is, the other says where to put a new one");
        }

        Gpg gpg = new Gpg();
        if (!gpg.available()) {
            throw new Failure("gpg is not on PATH; install GnuPG first");
        }

        List<KeyInfo> keys = select(new Keyring(gpg).list(), keyOption);

        // --out is always a destination (SPEC §2 step 4); --dest adds the others. Both are checked
        // before the export, so an unwritable destination is a refusal and not a half-done backup.
        Path backupDirectory = out != null ? out.toAbsolutePath() : Defaults.backupDir();
        List<String> specs = new ArrayList<>();
        specs.add("file:" + backupDirectory);
        specs.addAll(destSpecs);
        List<Destinations.Destination> destinations = Destinations.parse(specs);
        Destinations.check(destinations);

        Passphrase.Source passphrase = resolvePassphrase(passphraseFile, generateTo);
        String ciphertextName = ciphertextName(keys);
        String backedUpAt = Manifest.now();

        Path primaryCiphertext;
        String sha256;
        List<String> copies = new ArrayList<>();
        try (TempDir scratch = TempDir.create()) {
            Path export = scratch.path().resolve("export.asc");
            long exportBytes = BackupPayload.write(gpg, keys, export);
            report("export", describe(keys) + " — " + exportBytes + " bytes of secret, public and ownertrust data");
            report("encrypt", CIPHER + ", " + passphraseDescription(passphrase));

            byte[] ciphertext = encrypt(gpg, export, ciphertextName, passphrase, scratch.path());
            long plaintextBytes = Loopback.check(gpg, ciphertext, export, passphrase, scratch.path()).bytes();
            sha256 = Digests.sha256(ciphertext);
            report("loopback", "OK: the ciphertext decrypts back to the export byte for byte ("
                    + plaintextBytes + " bytes, ciphertext sha256 " + sha256 + ")");

            // The generated passphrase is written now, after the backup has proved it can be
            // decrypted and before any destination has a ciphertext: a run that fails before this
            // leaves no passphrase file that decrypts nothing.
            if (passphrase.secret().length > 0) {
                Passphrase.write(passphrase);
            }

            Destinations.prepare(destinations);
            for (Destinations.Destination destination : destinations) {
                Path ciphertextPath = destination.directory().resolve(ciphertextName);
                writeCiphertext(ciphertextPath, ciphertext);
                copies.add(ciphertextPath.toString());
            }
            // A second pass, because the manifest lists every copy of this backup and that list is
            // only complete once all of them are on disk.
            for (Destinations.Destination destination : destinations) {
                Path ciphertextPath = destination.directory().resolve(ciphertextName);
                Path manifestPath = destination.directory().resolve(Defaults.MANIFEST_NAME);
                Manifest.write(manifestPath, Manifest.document(keys, CIPHER, sha256, backedUpAt,
                        passphrase.storage(), copies, restoreCommand(ciphertextPath, passphrase.file())).toString());
                report("destination", destination.spec() + "  →  " + ciphertextPath + " ("
                        + ciphertext.length + " bytes)");
                report("manifest", manifestPath.toString());
            }
            primaryCiphertext = destinations.get(0).directory().resolve(ciphertextName);
        }

        String restoreCommand = restoreCommand(primaryCiphertext, passphrase.file());
        if (passphrase.secret().length > 0) {
            report("passphrase", "written to " + passphrase.file()
                    + " (mode 0600, never printed). Move it into a password manager, and keep the ciphertext"
                    + " and the passphrase on separate paths — see docs/backup-placement.md");
        }
        if (destSpecs.isEmpty()) {
            report("destination", "nothing but " + backupDirectory + " is configured; a backup on the machine it"
                    + " backs up is not a backup. Add --dest onedrive:<folder> for a second copy");
        }

        if (global.json()) {
            // The same document that was written beside the copy, so --json and manifest.json
            // cannot drift apart into two descriptions of one backup.
            System.out.println(Manifest.document(keys, CIPHER, sha256, backedUpAt,
                    passphrase.storage(), copies, restoreCommand));
        } else if (global.quiet()) {
            System.out.println(restoreCommand);
        } else {
            System.out.println();
            System.out.println("restore with:");
            System.out.println("  " + restoreCommand);
        }
        return Defaults.EXIT_OK;
    }

    /**
     * Which keys this run backs up.
     *
     * <p>Without {@code --key} it is the usable signing keys, because that is what a lost machine
     * costs: the key that signs releases and commits. {@code --key all} widens it to every key
     * whose private half is here, including keys that only encrypt.
     */
    private static List<KeyInfo> select(List<KeyInfo> keys, String option) {
        if (option == null) {
            List<KeyInfo> signing = KeySelection.signing(keys);
            if (signing.isEmpty()) {
                throw new Failure("no usable signing key in this keyring, so there is nothing worth backing up;"
                        + " `gpgkit status` lists what is here, and `gpgkit gen` makes a key");
            }
            return signing;
        }
        if (option.equals("all")) {
            List<KeyInfo> withSecret = KeySelection.withSecret(keys);
            if (withSecret.isEmpty()) {
                throw new Failure("no key in this keyring has its private half here; there is nothing to back up");
            }
            return withSecret;
        }
        KeyInfo key = KeySelection.unique(keys, option);
        if (!key.secret()) {
            throw new Failure(key.fingerprint() + " has no private key in this keyring; backing up the public half"
                    + " alone would restore a key that cannot sign. Use --key all for the keys that are here");
        }
        return List.of(key);
    }

    private Passphrase.Source resolvePassphrase(Path passphraseFile, Path generateTo) {
        if (passphraseFile != null) {
            return Passphrase.fromFile(passphraseFile);
        }
        if (generateTo != null) {
            return Passphrase.generate(generateTo);
        }
        // `Defaults` answers with the environment variable when it is set and the standard path
        // otherwise, in one place, because `doctor` has to look at the same path this writes to.
        Path standard = Defaults.passphraseFile();
        // An existing passphrase file is reused rather than replaced. Generating a new one would
        // leave every earlier backup in that directory encrypted with a passphrase nothing on this
        // machine knows any more — which is the failure this tool exists to prevent.
        if (Files.exists(standard)) {
            return Passphrase.fromFile(standard);
        }
        return Passphrase.generate(standard);
    }

    private static byte[] encrypt(Gpg gpg, Path export, String name, Passphrase.Source passphrase, Path scratch) {
        Path output = scratch.resolve(name);
        // gpg 2.5 stops reading options at the first filename, so the passphrase arguments have to
        // come before the export and the export has to be last. With the order swapped, gpg reports
        // a usage error and the failure looks like a bug in this command line.
        List<String> arguments = new ArrayList<>(List.of(
                "--symmetric", "--cipher-algo", CIPHER, "--armor", "--output", output.toString()));
        arguments.addAll(passphrase.arguments());
        arguments.add(export.toString());
        Gpg.Result result = gpg.run(arguments, passphrase.stdin());
        if (!result.ok()) {
            throw new Failure("gpg could not encrypt the export: " + result.reason());
        }
        try {
            return Files.readAllBytes(output);
        } catch (IOException failure) {
            throw new Failure("could not read back the ciphertext gpg produced: " + failure.getMessage());
        }
    }

    private static void writeCiphertext(Path file, byte[] ciphertext) {
        try {
            Files.write(file, ciphertext, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException failure) {
            throw new Failure("could not write " + file + ": " + failure.getMessage());
        }
    }

    /**
     * The ciphertext file name: the key it holds and when it was taken.
     *
     * <p>The fingerprint is shortened to the long key id it already ends with, because a directory
     * of forty-character file names is unreadable, and the full fingerprint is one field away in
     * the manifest beside it.
     */
    private static String ciphertextName(List<KeyInfo> keys) {
        String prefix = keys.size() == 1 ? keys.get(0).keyId() : "all";
        return prefix + "-" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .format(java.time.LocalDateTime.now()) + ".asc.gpg";
    }

    /**
     * The command that restores this exact copy (SPEC §2 step 6) — the line people are meant to
     * copy now, while they still remember which file this was.
     */
    private static String restoreCommand(Path ciphertext, Path passphraseFile) {
        return "gpgkit restore " + quoted(ciphertext) + " --passphrase-file " + quoted(passphraseFile);
    }

    /**
     * Quoted only when it has to be: a path with a space in it, copied from this output into a
     * shell, is otherwise two arguments — and the second one is named "Files".
     */
    private static String quoted(Path path) {
        String text = path.toString();
        if (text.indexOf(' ') < 0 && text.indexOf('\'') < 0) {
            return text;
        }
        return "'" + text.replace("'", "'\\''") + "'";
    }

    private static String describe(List<KeyInfo> keys) {
        StringBuilder description = new StringBuilder();
        for (KeyInfo key : keys) {
            description.append(description.isEmpty() ? "" : "; ").append(key.fingerprint());
            if (!key.userIds().isEmpty()) {
                description.append(" \"").append(key.userIds().get(0)).append('"');
            }
            description.append(" (").append(key.algorithm());
            if (key.expiresEpoch() == null) {
                description.append(", no expiry");
            } else {
                description.append(", expires ").append(Dates.localDate(key.expiresEpoch()));
            }
            description.append(')');
        }
        return description.toString();
    }

    private static String passphraseDescription(Passphrase.Source passphrase) {
        if (passphrase.secret().length > 0) {
            return "passphrase to be generated into " + passphrase.file() + " (never printed)";
        }
        return "passphrase read from " + passphrase.file();
    }

    private void report(String label, String detail) {
        if (global.json() || global.quiet()) {
            return;
        }
        System.out.printf("%-12s  %s%n", label, detail);
    }

    private static String value(List<String> args, int index, String option) {
        if (index + 1 >= args.size()) {
            throw new UsageError(option + " needs a value");
        }
        return args.get(index + 1);
    }
}
