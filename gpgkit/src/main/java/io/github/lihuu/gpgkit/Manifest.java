package io.github.lihuu.gpgkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The note left beside every copy of a backup.
 *
 * <p>Six months later nobody remembers which file is the current one, what its passphrase was for,
 * or where that passphrase went. SPEC §2 step 5 answers all three on one page, and the field that
 * earns its place is {@code passphraseStorage}: it records where the passphrase is, never what it
 * is, and it is written to be edited by hand afterwards.
 *
 * <p>The keys are exactly the ones SPEC §2 names, plus where the copies of this backup are. A
 * manifest that cannot say where its own ciphertext went would be the one question left open at
 * the moment it matters.
 */
final class Manifest {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private Manifest() {
    }

    /** The moment this backup was taken, in the machine's own zone and with its offset named. */
    static String now() {
        return TIMESTAMP.format(OffsetDateTime.now());
    }

    /**
     * Builds the document for one destination.
     *
     * <p>Per destination and not per run, because the restore command it records points at that
     * destination's copy — the copy the person reading this manifest is holding.
     *
     * <p>When more than one key is in the backup, the per-key fields are arrays in the same order:
     * the alternative was a manifest that describes one key and quietly omits the others, and a
     * silent omission in the file that exists to prevent surprises is the worse trade.
     */
    static Json document(List<KeyInfo> keys, String cipher, String sha256, String backedUpAt,
                         String passphraseStorage, List<String> copies, String restoreCommand) {
        Json json = new Json();
        json.beginObject();
        if (keys.size() == 1) {
            KeyInfo key = keys.get(0);
            json.name("fingerprint").value(key.fingerprint());
        } else {
            json.name("fingerprint").beginArray();
            for (KeyInfo key : keys) {
                json.value(key.fingerprint());
            }
            json.endArray();
        }
        json.name("userIds").beginArray();
        for (KeyInfo key : keys) {
            for (String userId : key.userIds()) {
                json.value(userId);
            }
        }
        json.endArray();
        array(json, "algorithm", keys, true);
        array(json, "created", keys, false);
        // expires is the one field where null carries meaning: "this key never expires" is an
        // answer, and it has to be distinguishable from a field nobody filled in.
        if (keys.size() == 1) {
            expires(json, keys.get(0));
        } else {
            json.name("expires").beginArray();
            for (KeyInfo key : keys) {
                if (key.expiresEpoch() == null) {
                    json.nullValue();
                } else {
                    json.value(Dates.localDate(key.expiresEpoch()));
                }
            }
            json.endArray();
        }
        json.name("cipher").value(cipher);
        json.name("sha256").value(sha256);
        json.name("backedUpAt").value(backedUpAt);
        json.name("passphraseStorage").value(passphraseStorage);
        json.name("restoreCommand").value(restoreCommand);
        json.name("destinations").beginArray();
        for (String copy : copies) {
            json.value(copy);
        }
        json.endArray();
        json.endObject();
        return json;
    }

    /**
     * Writes the manifest, owner-readable only.
     *
     * <p>Tightened although the file holds no secret: it does hold the path of the file that does,
     * and a synced directory plus a world-readable note is how the two halves end up in one place.
     */
    static void write(Path file, String document) {
        try {
            Files.writeString(file, document + "\n", StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException notPosix) {
            // Same trade as everywhere else: a filesystem without POSIX permissions still gets a
            // manifest, and the user still gets told where the passphrase is.
        } catch (IOException failure) {
            throw new Failure("could not write " + file + ": " + failure.getMessage());
        }
    }

    private static void array(Json json, String name, List<KeyInfo> keys, boolean algorithm) {
        if (keys.size() == 1) {
            KeyInfo key = keys.get(0);
            json.name(name).value(algorithm ? key.algorithm() : Dates.localDate(key.createdEpoch()));
            return;
        }
        json.name(name).beginArray();
        for (KeyInfo key : keys) {
            json.value(algorithm ? key.algorithm() : Dates.localDate(key.createdEpoch()));
        }
        json.endArray();
    }

    private static void expires(Json json, KeyInfo key) {
        if (key.expiresEpoch() == null) {
            json.name("expires").nullValue();
        } else {
            json.name("expires").value(Dates.localDate(key.expiresEpoch()));
        }
    }
}
