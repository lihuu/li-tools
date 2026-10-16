package io.github.lihuu.gpgkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;

/**
 * The passphrase that encrypts a backup, and the file it lives in.
 *
 * <p>Two rules shape this class. The passphrase is never printed (SPEC §3.1) — it only ever goes
 * from a file this tool wrote, or from the memory it was generated in, into gpg's stdin. And the
 * ciphertext and the passphrase must not travel together (docs/backup-placement.md), which is the
 * whole reason this tool generates one at all rather than reusing something the user typed.
 *
 * <p>{@link Source} carries gpg's own arguments for the passphrase rather than the passphrase
 * itself, so the common case where the user already has a passphrase file never reads that file
 * into this process: gpg opens it, and the secret stays in one place.
 */
final class Passphrase {
    /** 32 bytes of base64: long enough that guessing is not the cheapest way in. */
    private static final int RANDOM_BYTES = 32;

    private Passphrase() {
    }

    /**
     * @param arguments gpg arguments that tell it where the passphrase is. They must be placed
     *                  <b>before the input file</b> on the command line: since GnuPG 2.5 anything
     *                  after the first filename is not treated as an option at all, and the call
     *                  fails with `usage: gpg [options] --symmetric [filename]` — which reads like
     *                  a syntax error in this tool rather than a passphrase that never got read
     * @param stdin     what to write to gpg's stdin, or null when the passphrase comes from a file
     * @param file      where the passphrase is, or will be after a successful backup
     * @param secret    the generated passphrase, kept only until it is written; empty when the
     *                  passphrase came from the user's own file and this process never saw it
     * @param storage   the sentence the manifest records: where the passphrase is, never what it is
     */
    record Source(List<String> arguments, byte[] stdin, Path file, byte[] secret, String storage) {
    }

    /** A passphrase the user already has. The file is checked here and opened by gpg later. */
    static Source fromFile(Path file) {
        if (!Files.isReadable(file) || Files.isDirectory(file)) {
            throw new Failure("the passphrase file " + file + " cannot be read");
        }
        try {
            if (Files.size(file) == 0) {
                throw new Failure("the passphrase file " + file + " is empty; gpg would encrypt with an empty passphrase");
            }
        } catch (IOException failure) {
            throw new Failure("the passphrase file " + file + " cannot be read: " + failure.getMessage());
        }
        return new Source(List.of("--passphrase-file", file.toString()), null, file, new byte[0],
                "the passphrase file " + file + " (record here where you keep it; it must not live beside the ciphertext)");
    }

    /**
     * A fresh passphrase, generated now and written to {@code file} only after the backup has been
     * verified — so a run that fails leaves no passphrase file behind that decrypts nothing.
     *
     * <p>An existing file is refused rather than overwritten. Overwriting would silently make
     * every earlier backup undecryptable, which is the exact failure this tool exists to prevent.
     */
    static Source generate(Path file) {
        if (Files.exists(file)) {
            throw new Failure(file + " already exists; refusing to replace a passphrase that earlier backups"
                    + " may have been encrypted with. Pass it back with --passphrase-file " + file
                    + ", or name a different file");
        }
        byte[] secret = opensslRandom();
        return new Source(List.of("--passphrase-fd", "0"), secret, file, secret,
                "written to " + file + " by gpgkit and never printed; move it into a password manager and"
                        + " correct this line to say where it is (docs/backup-placement.md)");
    }

    /**
     * Writes the generated passphrase where the user asked for it.
     *
     * <p>Created with its permissions in one call rather than written and then tightened: a file
     * that is world-readable for even a moment is a file that a second process on the machine can
     * read, and this is the one secret in the whole tool.
     */
    static void write(Source source) {
        Path file = source.file();
        Path parent = file.toAbsolutePath().getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.createFile(file, PosixFilePermissions.asFileAttribute(ownerOnly()));
            byte[] content = new byte[source.secret().length + 1];
            System.arraycopy(source.secret(), 0, content, 0, source.secret().length);
            // The trailing newline is what makes this a text file a person can open and copy from;
            // gpg reads the first line either way, and reads the same bytes from stdin.
            content[content.length - 1] = '\n';
            Files.write(file, content, StandardOpenOption.WRITE);
        } catch (UnsupportedOperationException notPosix) {
            // A filesystem without permissions still has to be able to hold a backup. gpg warns
            // about a group-readable keyring and carries on; the same trade is taken here.
            writeWithoutPermissions(file, source.secret());
        } catch (IOException failure) {
            throw new Failure("could not write the passphrase to " + file + ": " + failure.getMessage());
        }
    }

    private static Set<PosixFilePermission> ownerOnly() {
        return PosixFilePermissions.fromString("rw-------");
    }

    private static void writeWithoutPermissions(Path file, byte[] secret) {
        byte[] content = new byte[secret.length + 1];
        System.arraycopy(secret, 0, content, 0, secret.length);
        content[content.length - 1] = '\n';
        try {
            Files.write(file, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException failure) {
            throw new Failure("could not write the passphrase to " + file + ": " + failure.getMessage());
        }
    }

    /**
     * A random passphrase from openssl, which is the one job SPEC §1 gives it: this tool does not
     * implement cryptography, and a passphrase is the one secret gpg will not generate for us.
     */
    private static byte[] opensslRandom() {
        ProcessBuilder builder = new ProcessBuilder("openssl", "rand", "-base64", Integer.toString(RANDOM_BYTES));
        Process process;
        try {
            process = builder.start();
        } catch (IOException missing) {
            throw new Failure("openssl is not on PATH, and it is what generates the backup passphrase;"
                    + " install it (macOS and most Linux systems already have it) or pass --passphrase-file");
        }
        byte[] output;
        try (java.io.InputStream stream = process.getInputStream()) {
            output = stream.readAllBytes();
            if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new Failure("openssl rand failed; pass --passphrase-file to use your own passphrase");
            }
        } catch (IOException failure) {
            throw new Failure("could not read the passphrase openssl generated: " + failure.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new Failure("interrupted while generating a passphrase");
        }
        String passphrase = new String(output, StandardCharsets.UTF_8).strip();
        if (passphrase.isEmpty()) {
            throw new Failure("openssl produced an empty passphrase; nothing was written");
        }
        return passphrase.getBytes(StandardCharsets.UTF_8);
    }
}
