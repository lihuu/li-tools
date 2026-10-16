package io.github.lihuu.gpgkit;

import java.util.List;

/**
 * One key as this project understands it, built from gpg's machine-readable listing.
 *
 * @param fingerprint  the full fingerprint; a short key id cannot tell two keys apart, and cannot
 *                     name one to gpg when a collision exists
 * @param algorithm    the label SPEC §2 prints, e.g. {@code rsa4096} or {@code ed25519}
 * @param usage        which of sign, certify, encrypt, authenticate the key can do, in that order
 * @param expiresEpoch null when the key has no expiry at all: "never expires" is an answer, not a
 *                     missing value
 * @param secret       whether the private half is actually here — the difference between a key
 *                     that can sign on this machine and one that merely exists
 */
record KeyInfo(String fingerprint, String keyId, String algorithm, String usage,
               long createdEpoch, Long expiresEpoch, List<String> userIds, boolean secret) {

    /** Days until this key expires, or null when it never does. */
    Long daysRemaining() {
        return expiresEpoch == null ? null : Dates.daysRemaining(expiresEpoch);
    }

    boolean expired() {
        return expiresEpoch != null && Dates.expired(expiresEpoch);
    }

    /**
     * Whether this key can produce a signature right now: the private half is here, the key is
     * allowed to sign, and it has not expired.
     *
     * <p>An expired key still advertises the signing capability, which is why expiry is part of
     * the test rather than a warning printed beside it. "There is no usable signing key" and "your
     * signing key expired" are the same problem to whoever is trying to publish.
     */
    boolean canSign() {
        return secret && usage.indexOf('S') >= 0 && !expired();
    }
}
