package io.github.lihuu.gpgkit;

import java.time.Duration;
import java.util.List;

/**
 * The keyserver conversation, in one place.
 *
 * <p>{@code status} asks whether a key is visible, {@code doctor} asks whether the server answers
 * at all, and {@code publish} will ask it to accept a key. Three callers, three questions, one
 * protocol: keeping them apart is how one of them ends up importing into the user's real keyring.
 */
final class Keyserver {
    enum Visibility {
        /** The server handed the key back. */
        VISIBLE,
        /** The server answered, and does not know this key. Says nothing about the network. */
        NOT_PUBLISHED,
        /** The server never answered. Says nothing about the key. */
        UNREACHABLE
    }

    record Probe(Visibility visibility, String detail) {
    }

    private Keyserver() {
    }

    /**
     * Asks a keyserver whether it knows a fingerprint.
     *
     * <p>Deliberately not {@code --search-keys} and deliberately not the user's own keyring: this
     * fetches into a throwaway one. Importing into the real keyring would silently re-add a public
     * key the user removed on purpose, and a check must never change the thing it is checking.
     *
     * <p>A timeout is a result here, not an exception. "The server did not answer" is a line in a
     * report — and for {@code status}, where the answer is explicitly best-effort, it must not
     * change the exit code.
     */
    static Probe probe(String keyserver, String fingerprint, Duration timeout) {
        try (TempDir scratch = TempDir.create()) {
            Gpg gpg = new Gpg(scratch.path());
            Gpg.Result result = gpg.run(
                    List.of("--keyserver", keyserver, "--recv-keys", fingerprint), null, timeout);
            if (result.ok()) {
                return new Probe(Visibility.VISIBLE, "the server returned the key");
            }
            // gpg says "No data" when the server answered and simply does not have the key. Every
            // other failure (refused, timed out, TLS, proxy) means we cannot tell — and reporting
            // "not published" for a network problem would send someone to republish a key that is
            // already there. Matching on this wording is why Gpg pins the child process to LC_ALL=C.
            if (result.stderr().contains("No data")) {
                return new Probe(Visibility.NOT_PUBLISHED, "the server answered but does not know this key");
            }
            return new Probe(Visibility.UNREACHABLE, result.reason());
        }
    }
}
