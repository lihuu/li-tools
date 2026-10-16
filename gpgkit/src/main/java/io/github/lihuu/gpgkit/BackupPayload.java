package io.github.lihuu.gpgkit;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The plaintext a backup carries, and how it is taken apart again.
 *
 * <p>Three things go in, in the order SPEC §2 lists them: the secret keys, the public keys, and
 * the ownertrust entries for those keys. The trust part is easy to leave out and expensive to
 * leave out — a restored key that cannot be trusted signs nothing that anyone accepts, and
 * re-establishing trust by hand is the step people do not know exists.
 *
 * <p>The file this writes lives in a 0700 scratch directory, is deleted when the command ends,
 * and is never printed, copied into the report, or left on a destination: only gpg reads it back
 * (SPEC §3.1). What is written to disk for the user is the ciphertext of it.
 */
final class BackupPayload {
    private static final Pattern OWNERTRUST_LINE = Pattern.compile("^[0-9A-Fa-f]{40}:[0-9]+:$");

    /**
     * The one string that proves gpg exported private material. Asking for a key whose private
     * half is not here is not an error to gpg: it exits 0 and writes nothing, so a backup that
     * checked only the exit code would produce a perfectly valid, useless backup.
     */
    private static final String PRIVATE_BLOCK = "-----BEGIN PGP PRIVATE KEY BLOCK-----";

    private BackupPayload() {
    }

    /** Assembles the payload for the selected keys and returns its size in bytes. */
    static long write(Gpg gpg, List<KeyInfo> keys, Path file) {
        StringBuilder header = new StringBuilder();
        header.append("# gpgkit backup payload v1\n");
        header.append("# keys: ");
        for (KeyInfo key : keys) {
            header.append(key.fingerprint()).append(' ');
        }
        header.append("\n# This file holds private key material. gpgkit never prints it and never writes\n");
        header.append("# it anywhere but inside the encrypted backup.\n\n");

        try {
            createOwnerOnly(file);
            try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.WRITE)) {
                out.write(header.toString().getBytes(StandardCharsets.UTF_8));
                for (KeyInfo key : keys) {
                    out.write(export(gpg, "--export-secret-keys", key).getBytes(StandardCharsets.UTF_8));
                    out.write('\n');
                }
                for (KeyInfo key : keys) {
                    out.write(export(gpg, "--export", key).getBytes(StandardCharsets.UTF_8));
                    out.write('\n');
                }
                out.write(ownertrust(gpg, keys).getBytes(StandardCharsets.UTF_8));
            }
            return Files.size(file);
        } catch (IOException failure) {
            throw new Failure("could not write the export into the scratch directory: " + failure.getMessage());
        }
    }

    /**
     * The ownertrust lines this backup carries.
     *
     * <p>Read line by line and filtered in place: the decrypted payload is the private key block,
     * and the only lines kept from it are the trust entries, so a restore never holds more of that
     * material than the one line it needs (SPEC §3.1).
     */
    static List<String> ownertrustLines(Path payload) {
        List<String> lines = new ArrayList<>();
        try (Stream<String> content = Files.lines(payload, StandardCharsets.UTF_8)) {
            content.forEach(line -> {
                String stripped = line.strip();
                if (OWNERTRUST_LINE.matcher(stripped).matches()) {
                    lines.add(stripped);
                }
            });
        } catch (IOException failure) {
            throw new Failure("could not read the decrypted backup: " + failure.getMessage());
        }
        return lines;
    }

    private static String export(Gpg gpg, String flag, KeyInfo key) {
        Gpg.Result result = gpg.run(List.of("--armor", flag, key.fingerprint()));
        if (!result.ok()) {
            String reason = GpgOutput.reason(result);
            if (reason.contains("batchmode")) {
                // gpg refuses to export a passphrase-protected secret key when nothing can answer
                // its prompt, and this tool never prompts (SPEC §3.5). The passphrase flags SPEC §2
                // gives `backup` encrypt the backup; they do not unlock the key, so saying which is
                // which here is the difference between a fixable message and a dead end.
                throw new Failure("gpg will not export the private key for " + key.fingerprint()
                        + " in batch mode: that key has its own passphrase (" + reason + "). gpgkit's"
                        + " passphrase options encrypt the backup and do not unlock the key, so this key"
                        + " cannot be backed up here. Export it by hand with `gpg --export-secret-keys`"
                        + " and encrypt that file yourself");
            }
            throw new Failure("gpg " + flag + " " + key.fingerprint() + " failed: " + reason);
        }
        String block = result.out();
        if (flag.equals("--export-secret-keys") && !block.contains(PRIVATE_BLOCK)) {
            throw new Failure("gpg exported no private key for " + key.fingerprint()
                    + ": the secret half of that key is not in this keyring, and a backup of the public half alone"
                    + " cannot sign anything after a restore");
        }
        if (block.isBlank()) {
            throw new Failure("gpg exported nothing for " + key.fingerprint());
        }
        return block;
    }

    /**
     * The trust entries for the selected keys only.
     *
     * <p>Filtered rather than taken whole: the keyring's own trust values for other people's keys
     * are not part of this backup, and importing them on another machine would assert trust
     * relationships that machine never agreed to.
     */
    private static String ownertrust(Gpg gpg, List<KeyInfo> keys) {
        Gpg.Result result = gpg.run(List.of("--export-ownertrust"));
        if (!result.ok()) {
            throw new Failure("gpg --export-ownertrust failed: " + result.reason());
        }
        StringBuilder trust = new StringBuilder("# ownertrust (gpg --import-ownertrust)\n");
        for (String line : result.out().split("\n")) {
            String stripped = line.strip();
            for (KeyInfo key : keys) {
                if (stripped.startsWith(key.fingerprint() + ":")) {
                    trust.append(stripped).append('\n');
                }
            }
        }
        return trust.toString();
    }

    private static void createOwnerOnly(Path file) throws IOException {
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException notPosix) {
            Files.createFile(file);
        }
    }
}
