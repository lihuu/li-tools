package io.github.lihuu.gpgkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The loopback check: the ciphertext is decrypted again and compared with the export byte for byte
 * (SPEC §2 step 3, and hard constraint §3.2).
 *
 * <p>This is the reason the tool exists. A backup that was never decrypted is a file of unknown
 * content — the passphrase file may have been written with a newline that gpg does not strip, the
 * cipher could have been left at a default nobody wrote down, the export could have been empty.
 * None of that is visible until the day someone needs the key, which is the worst possible day to
 * find out. So the check happens while the key is still here, and a backup that fails it does not
 * reach a destination at all.
 *
 * <p>The comparison is by file content, not by digest: a matching digest proves the same bytes
 * came back, and comparing the bytes directly is one less thing that has to be trusted.
 */
final class Loopback {
    /** What the check measured, for the line the user reads. */
    record Verified(long bytes) {
    }

    private Loopback() {
    }

    /**
     * Decrypts {@code ciphertext} and compares it with {@code export}.
     *
     * @throws Failure when the ciphertext does not come back as the export, which the caller must
     *                 report without writing anything to a destination
     */
    static Verified check(Gpg gpg, byte[] ciphertext, Path export, Passphrase.Source passphrase, Path scratch) {
        Path copy = scratch.resolve("loopback-ciphertext.asc.gpg");
        Path decrypted = scratch.resolve("loopback-decrypted.asc");
        try {
            Files.write(copy, ciphertext);
        } catch (IOException failure) {
            throw new Failure("could not place the ciphertext for the loopback check: " + failure.getMessage());
        }

        // Passphrase arguments before the filename, which must stay last: gpg 2.5 ignores options
        // that follow a filename, and an ignored `--passphrase-fd` is a decryption that fails for a
        // reason the user cannot see.
        List<String> arguments = new ArrayList<>(List.of(
                "--decrypt", "--output", decrypted.toString()));
        arguments.addAll(passphrase.arguments());
        arguments.add(copy.toString());
        Gpg.Result result = gpg.run(arguments, passphrase.stdin());
        if (!result.ok()) {
            throw new Failure("loopback check failed: the ciphertext this run just produced does not decrypt"
                    + " (" + GpgOutput.reason(result) + "). Nothing was written to any destination;"
                    + " nothing was backed up");
        }

        try {
            long difference = Files.mismatch(decrypted, export);
            if (difference >= 0) {
                throw new Failure("loopback check failed: the decrypted bytes are not the export — they differ"
                        + " at byte " + difference + " of " + Files.size(export) + ". Nothing was written to any"
                        + " destination; do not trust this backup");
            }
            return new Verified(Files.size(export));
        } catch (IOException failure) {
            throw new Failure("loopback check failed: could not compare the decrypted bytes with the export: "
                    + failure.getMessage());
        }
    }
}
