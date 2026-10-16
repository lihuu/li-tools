package io.github.lihuu.gpgkit;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * SHA-256 digests, and the {@code SHA256SUMS} file that holds them.
 *
 * <p>In coreutils' own format — {@code <hex><two spaces><name>} — rather than a format of this
 * tool's own invention. A release checklist that says "run {@code sha256sum -c SHA256SUMS}" has
 * to keep working, and a manifest only {@code gpgkit} can read would quietly replace a tool
 * everybody has with one only its author has.
 *
 * <p>Parsing is deliberately dumb and validation lives with the report: a line that is not a
 * digest is not discarded here, because a discarded line is a file nobody checks. It becomes an
 * entry whose hash does not look like a digest, and the caller says so, by name.
 */
final class Checksums {
    /** What {@code --checksums sha256} is called on disk, and what coreutils would call it. */
    static final String MANIFEST_NAME = "SHA256SUMS";

    /** The detached signature over the manifest — the one file a consumer has to trust. */
    static final String MANIFEST_SIGNATURE_SUFFIX = ".asc";

    private static final int DIGEST_HEX_LENGTH = 64;

    private Checksums() {
    }

    /** @param hash lowercase hex, or anything else when the line was malformed */
    record Entry(String hash, String name) {
    }

    /** The lowercase hex SHA-256 of a file's contents. */
    static String of(Path file) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            // Every JDK is required to have SHA-256. Reaching here means the runtime is not a JDK,
            // in which case there is nothing useful to say beyond this.
            throw new IllegalStateException("this runtime has no SHA-256", impossible);
        }
        try (InputStream stream = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        } catch (IOException failure) {
            throw new Failure("could not read " + file + " to hash it: " + failure.getMessage());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** One line of a manifest, in the two-space text form coreutils writes and reads back. */
    static String line(String hash, String name) {
        return hash + "  " + name + "\n";
    }

    static List<Entry> parse(String text) {
        List<Entry> entries = new ArrayList<>();
        for (String raw : text.split("\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            int separator = indexOfWhitespace(line);
            // Only a first token that could actually be a digest makes this a manifest line. Anything
            // else keeps the whole line, because the useful thing to report about a line that is not a
            // checksum is the line — reporting its first word names a file that does not exist.
            if (separator < 0 || !wellFormed(line.substring(0, separator).toLowerCase(java.util.Locale.ROOT))) {
                entries.add(new Entry("", line));
                continue;
            }
            String hash = line.substring(0, separator);
            String name = line.substring(separator).strip();
            // coreutils marks a digest taken in binary mode with a leading '*'. Both forms mean the
            // same file, and a tool that refused one of them would fail on a manifest that
            // sha256sum itself wrote.
            if (name.startsWith("*")) {
                name = name.substring(1);
            }
            entries.add(new Entry(hash.toLowerCase(java.util.Locale.ROOT), name));
        }
        return entries;
    }

    /** Whether a hash could have come from {@link #of}: 64 hex digits and nothing else. */
    static boolean wellFormed(String hash) {
        if (hash.length() != DIGEST_HEX_LENGTH) {
            return false;
        }
        for (int index = 0; index < hash.length(); index++) {
            char character = hash.charAt(index);
            boolean hex = (character >= '0' && character <= '9') || (character >= 'a' && character <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    private static int indexOfWhitespace(String line) {
        for (int index = 0; index < line.length(); index++) {
            if (Character.isWhitespace(line.charAt(index))) {
                return index;
            }
        }
        return -1;
    }
}
