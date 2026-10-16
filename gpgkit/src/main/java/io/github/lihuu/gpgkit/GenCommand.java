package io.github.lihuu.gpgkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * {@code gpgkit gen} — SPEC §2.
 *
 * <p>The defaults are the contract: {@code rsa4096}, {@code 2y}, and <b>no passphrase</b>. That
 * last one is deliberate and is written down in SPEC §4 — the release pipeline signs headlessly
 * through {@code maven-gpg-plugin --pinentry-mode loopback}, and a key with a passphrase stops
 * that at a pinentry prompt. The price is that the private key file is itself the credential,
 * which is why this command does not finish by saying "done": it says back it up.
 *
 * <p>The key is generated from a batch parameter file rather than by {@code --quick-generate-key},
 * because {@code %no-protection} is the only documented way to produce a key with no passphrase
 * at all. Quick-generate with an empty passphrase produces something that behaves similarly and
 * is a different thing, and the difference would show up on the day someone tries to sign from a
 * script with no terminal.
 */
final class GenCommand {
    private static final Set<String> VALUE_OPTIONS = Set.of("--uid", "--expire", "--algo", "--passphrase-file");
    private static final Set<String> BOOLEAN_OPTIONS = Set.of("--no-passphrase", "--publish");

    private static final String DEFAULT_ALGORITHM = "rsa4096";
    private static final String DEFAULT_EXPIRE = "2y";

    private final GlobalOptions global;

    GenCommand(GlobalOptions global) {
        this.global = global;
    }

    int run(List<String> args) {
        Arguments.Result parsed = Arguments.parse(args, VALUE_OPTIONS, BOOLEAN_OPTIONS);
        if (!parsed.ok()) {
            System.err.println("gpgkit: gen: " + parsed.error());
            return Defaults.EXIT_USAGE;
        }
        Arguments options = parsed.arguments();
        if (!options.positional().isEmpty()) {
            System.err.println("gpgkit: gen takes no arguments, but got: " + options.positional().get(0));
            return Defaults.EXIT_USAGE;
        }
        if (options.has("--no-passphrase") && options.value("--passphrase-file") != null) {
            System.err.println("gpgkit: gen: --no-passphrase and --passphrase-file are opposites; give one");
            return Defaults.EXIT_USAGE;
        }

        String algorithm = options.value("--algo") == null ? DEFAULT_ALGORITHM : options.value("--algo");
        if (!algorithm.equals("rsa4096") && !algorithm.equals("ed25519")) {
            System.err.println("gpgkit: gen: unknown algorithm: " + algorithm
                    + " (SPEC §2 offers rsa4096 and ed25519)");
            return Defaults.EXIT_USAGE;
        }

        String expire = options.value("--expire") == null ? DEFAULT_EXPIRE : options.value("--expire");
        String expireProblem = expireProblem(expire);
        if (expireProblem != null) {
            System.err.println("gpgkit: gen: " + expireProblem);
            return Defaults.EXIT_USAGE;
        }

        String requestedUid = options.value("--uid");
        String userId;
        if (requestedUid != null) {
            userId = requestedUid.strip();
        } else {
            userId = GitIdentity.userId().orElseThrow(() -> new Failure(
                    "no --uid given and no identity to take one from: "
                            + (GitIdentity.available()
                                    ? "set `git config --global user.name \"Your Name\"` and "
                                            + "`git config --global user.email you@example.com`, or pass --uid"
                                    : "git is not on PATH, so pass --uid \"Your Name <you@example.com>\"")));
        }
        if (userId.isEmpty()) {
            System.err.println("gpgkit: gen: --uid is empty");
            return Defaults.EXIT_USAGE;
        }
        // A newline in a user id would end the Name-Real line and let the rest of the id be read
        // as another directive of the parameter file — including one that removes the expiry this
        // command just validated. The uid is the one input here that a person types.
        for (int index = 0; index < userId.length(); index++) {
            if (userId.charAt(index) < 0x20 || userId.charAt(index) == 0x7F) {
                System.err.println("gpgkit: gen: --uid contains a control character at position " + index);
                return Defaults.EXIT_USAGE;
            }
        }

        Path passphraseFile = null;
        if (options.value("--passphrase-file") != null) {
            passphraseFile = Path.of(options.value("--passphrase-file"));
            if (!Files.isRegularFile(passphraseFile) || !Files.isReadable(passphraseFile)) {
                throw new Failure("passphrase file is not a readable file: " + passphraseFile
                        + "; a passphrase file that cannot be read would otherwise surface as a gpg error "
                        + "after the key had already been made");
            }
        }

        Gpg gpg = new Gpg();
        if (!gpg.available()) {
            throw new Failure("gpg is not on PATH; install GnuPG first");
        }

        // From here on the keyring is about to change, so every check that can fail has already
        // run: the algorithm, the expiry, the uid, the passphrase file and gpg itself.
        String fingerprint = generate(gpg, userId, algorithm, expire, passphraseFile);
        KeyInfo key = describe(gpg, fingerprint);

        if (!global.json()) {
            printHuman(key, algorithm, expire, passphraseFile);
        } else {
            printJson(key, passphraseFile);
        }

        if (options.has("--publish")) {
            PublishCommand.publish(global, key, Defaults.KEYSERVER);
        }
        return Defaults.EXIT_OK;
    }

    /**
     * Generates the key and returns the fingerprint gpg reports for it.
     *
     * <p>Taken from gpg's own {@code KEY_CREATED} status line rather than from the newest entry in
     * a re-read listing: "the newest key" is a guess that is wrong the moment anything else writes
     * to the keyring at the same time, and it is wrong in the expensive direction — the report
     * would name a key that was not the one just made.
     */
    private static String generate(Gpg gpg, String userId, String algorithm, String expire,
                                   Path passphraseFile) {
        try (TempDir scratch = TempDir.create()) {
            Path parameters = scratch.path().resolve("gen-key.params");
            try {
                Files.writeString(parameters, parameterFile(userId, algorithm, expire, passphraseFile),
                        StandardCharsets.UTF_8);
            } catch (IOException failure) {
                throw new Failure("could not write the gpg batch parameters: " + failure.getMessage());
            }

            java.util.ArrayList<String> arguments = new java.util.ArrayList<>();
            arguments.add("--status-fd");
            arguments.add("1");
            if (passphraseFile != null) {
                // The passphrase is read by gpg from the file the user named. It is never read here:
                // this process cannot leak what it never held (SPEC §3.1).
                arguments.add("--passphrase-file");
                arguments.add(passphraseFile.toAbsolutePath().toString());
            }
            arguments.add("--gen-key");
            arguments.add(parameters.toAbsolutePath().toString());

            Gpg.Result result = gpg.run(List.copyOf(arguments));
            String fingerprint = createdFingerprint(result);
            if (fingerprint == null) {
                throw new Failure("gpg did not generate a key: " + result.reason());
            }
            return fingerprint;
        }
    }

    /** The batch parameter file. Never contains the passphrase itself, only whether there is one. */
    private static String parameterFile(String userId, String algorithm, String expire, Path passphraseFile) {
        String name = userId;
        String email = "";
        int open = userId.lastIndexOf('<');
        if (open >= 0 && userId.endsWith(">")) {
            name = userId.substring(0, open).strip();
            email = userId.substring(open + 1, userId.length() - 1).strip();
        }

        StringBuilder parameters = new StringBuilder();
        // %no-protection has to be the first line, and it is the whole point: it is what makes the
        // private key file usable without a pinentry dialog (SPEC §4).
        if (passphraseFile == null) {
            parameters.append("%no-protection\n");
        }
        if (algorithm.equals("ed25519")) {
            // EdDSA for signing, and a separate Curve25519 subkey for encryption. A key that could
            // only sign could not receive anything, and the encrypted-message case is the one
            // people reach for a public key for.
            parameters.append("Key-Type: EDDSA\n");
            parameters.append("Key-Curve: ed25519\n");
            parameters.append("Key-Usage: sign\n");
            parameters.append("Subkey-Type: ECDH\n");
            parameters.append("Subkey-Curve: cv25519\n");
            parameters.append("Subkey-Usage: encrypt\n");
        } else {
            parameters.append("Key-Type: RSA\n");
            parameters.append("Key-Length: 4096\n");
            parameters.append("Key-Usage: sign\n");
            parameters.append("Subkey-Type: RSA\n");
            parameters.append("Subkey-Length: 4096\n");
            parameters.append("Subkey-Usage: encrypt\n");
        }
        parameters.append("Name-Real: ").append(name).append('\n');
        if (!email.isEmpty()) {
            parameters.append("Name-Email: ").append(email).append('\n');
        }
        parameters.append("Expire-Date: ").append(expire).append('\n');
        parameters.append("%commit\n");
        return parameters.toString();
    }

    private static String createdFingerprint(Gpg.Result result) {
        for (String line : result.out().split("\n")) {
            String marker = "[GNUPG:] KEY_CREATED ";
            if (line.startsWith(marker)) {
                String[] fields = line.substring(marker.length()).strip().split(" ");
                if (fields.length >= 2) {
                    return fields[1];
                }
            }
        }
        return null;
    }

    private static KeyInfo describe(Gpg gpg, String fingerprint) {
        for (KeyInfo key : new Keyring(gpg).list()) {
            if (key.fingerprint().equalsIgnoreCase(fingerprint)) {
                return key;
            }
        }
        throw new Failure("gpg reported that it created " + fingerprint
                + ", but the key is not in the keyring now; do not run this again before looking at `gpg --list-keys`");
    }

    /**
     * @return why the expiry cannot be used, or null when it can
     */
    private static String expireProblem(String expire) {
        if (expire.matches("[0-9]+[dwmy]") || expire.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            return null;
        }
        return "unrecognised --expire value: " + expire
                + " (use a duration like 2y, 52w, 365d, 12m, or a date like 2030-01-01)";
    }

    private void printHuman(KeyInfo key, String algorithm, String expire, Path passphraseFile) {
        System.out.println("generated    " + key.fingerprint());
        for (String userId : key.userIds()) {
            System.out.println("  uid        " + userId);
        }
        System.out.println("  algorithm  " + key.algorithm() + (key.algorithm().equals(algorithm) ? "" : " (asked for " + algorithm + ")"));
        System.out.println("  usage      " + key.usage());
        System.out.println("  created    " + Dates.localDate(key.createdEpoch()));
        System.out.println("  expires    " + expiry(key, expire));
        System.out.println("  passphrase " + (passphraseFile == null
                ? "none — the private key file is the credential (SPEC §4)"
                : "from " + passphraseFile));
        System.out.println();
        // SPEC §2: "no backup, no leaving". This is the sentence that carries it.
        System.out.println("nothing is backed up yet, and this key exists in exactly one place.");
        System.out.println("next         gpgkit backup --key " + key.fingerprint());
        if (passphraseFile == null) {
            System.out.println("             a key with no passphrase is only as safe as its backup: "
                    + "backing up now is what makes an unprotected key a deliberate choice instead of a gamble");
        }
    }

    private void printJson(KeyInfo key, Path passphraseFile) {
        Json json = new Json();
        json.beginObject();
        json.name("fingerprint").value(key.fingerprint());
        json.name("userIds").beginArray();
        for (String userId : key.userIds()) {
            json.value(userId);
        }
        json.endArray();
        json.name("algorithm").value(key.algorithm());
        json.name("usage").value(key.usage());
        json.name("created").value(Dates.localDate(key.createdEpoch()));
        if (key.expiresEpoch() == null) {
            json.name("expires").nullValue();
            json.name("daysRemaining").nullValue();
        } else {
            json.name("expires").value(Dates.localDate(key.expiresEpoch()));
            json.name("daysRemaining").value(Dates.daysRemaining(key.expiresEpoch()));
        }
        json.name("passphrase").value(passphraseFile == null ? "none" : "file");
        json.name("backedUp").value(false);
        json.name("backupCommand").value("gpgkit backup --key " + key.fingerprint());
        json.endObject();
        System.out.println(json);
    }

    private static String expiry(KeyInfo key, String asked) {
        if (key.expiresEpoch() == null) {
            // Asked for something and got no expiry: say so rather than printing "never" as though
            // it were what was requested.
            return "never" + (asked.equals("0") ? "" : " (asked for " + asked + ")");
        }
        return Dates.localDate(key.expiresEpoch()) + " (" + Dates.daysRemaining(key.expiresEpoch())
                + " days left)";
    }
}
