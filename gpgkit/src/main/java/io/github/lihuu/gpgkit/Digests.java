package io.github.lihuu.gpgkit;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Checksums, in the one form people compare by eye. */
final class Digests {
    private Digests() {
    }

    /**
     * Lowercase hex SHA-256 of a byte block.
     *
     * <p>The manifest records this over the ciphertext (SPEC §2), and it is what someone compares
     * against a file they downloaded from a cloud drive: the point is that it can be checked from
     * outside this tool, so it is plain hex and not this project's own encoding.
     */
    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException impossible) {
            // Every JDK is required to ship SHA-256; there is no fallback worth writing.
            throw new IllegalStateException("this JDK has no SHA-256", impossible);
        }
    }
}
