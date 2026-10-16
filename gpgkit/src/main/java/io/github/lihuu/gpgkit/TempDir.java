package io.github.lihuu.gpgkit;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * A scratch directory that removes itself, including when the command fails.
 *
 * <p>Needed rather than convenient: the checks that matter here are the ones that produce
 * something a mistake would leave behind — a keyring a keyserver probe imported a key into, a
 * signed payload that proves signing works. A check that litters is a check people stop running.
 */
final class TempDir implements AutoCloseable {
    /**
     * Deliberately short, and deliberately not {@code Files.createTempDirectory}.
     *
     * <p>That method appends a nineteen-digit random number to its prefix, and on macOS the temp
     * directory is already about fifty characters long ({@code /var/folders/xx/…/T/}). Add
     * gpg's {@code /S.gpg-agent} socket and the path reaches the 104-byte {@code sun_path} limit.
     *
     * <p>What happens then is worth writing down, because it does not look like a failure:
     * {@code gpg --recv-keys} imports the key, and only then reports
     * {@code gpg: can't connect to the gpg-agent: IPC connect call failed} and exits 2. A probe
     * that reads the exit code sees "the keyserver is unreachable"; a probe that reads the output
     * sees a successful import. Measured here, the agent starts at a socket path of 91 characters
     * and fails at 101.
     */
    private static final String PREFIX = "gk-";

    /** Bound on a 32-bit hex suffix: a collision means a retry, so the odds only need to be good. */
    private static final int ATTEMPTS = 32;

    private final Path path;

    private TempDir(Path path) {
        this.path = path;
    }

    static TempDir create() {
        Path root = Path.of(System.getProperty("java.io.tmpdir"));
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            Path candidate = root.resolve(PREFIX + Long.toHexString(
                    ThreadLocalRandom.current().nextLong() & 0xFFFF_FFFFL));
            try {
                // Creating the directory by hand rather than with the attribute-carrying overload:
                // the permissions below are POSIX, and the attribute form has no equivalent on
                // filesystems that are not.
                Files.createDirectory(candidate);
                restrictToOwner(candidate);
                return new TempDir(candidate);
            } catch (FileAlreadyExistsException taken) {
                // Somebody else's scratch directory. Try another name.
            } catch (IOException failure) {
                throw new Failure("could not create a temporary directory: " + failure.getMessage());
            }
        }
        throw new Failure("could not find a free name for a temporary directory in " + root);
    }

    Path path() {
        return path;
    }

    @Override
    public void close() {
        try (Stream<Path> entries = Files.walk(path)) {
            entries.sorted(Comparator.reverseOrder()).forEach(TempDir::deleteQuietly);
        } catch (IOException ignored) {
            // Best effort, and deliberately silent: this runs on the failure path too, where a
            // second exception would replace the real error with a message about the scratch
            // directory — which is not the thing that went wrong.
        }
    }

    private static void restrictToOwner(Path directory) {
        try {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        } catch (IOException | UnsupportedOperationException notPosix) {
            // gpg warns about a home directory other people can read and carries on. A warning is
            // the right outcome here: the directory holds nothing but a key that was just fetched
            // in public, and refusing to run over it would be worse.
        }
    }

    private static void deleteQuietly(Path entry) {
        try {
            Files.deleteIfExists(entry);
        } catch (IOException ignored) {
            // Same reason as above.
        }
    }
}
