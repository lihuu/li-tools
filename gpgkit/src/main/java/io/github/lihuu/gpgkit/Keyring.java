package io.github.lihuu.gpgkit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * The local keyring, read into {@link KeyInfo} records.
 *
 * <p>Everything goes through {@code --with-colons}. That format is what gpg's own documentation
 * calls machine readable and what its callers are expected to parse; the human listing is a
 * display decision that has changed between releases, and a tool that scrapes it breaks on
 * somebody else's gpg version rather than on its own bug.
 *
 * <p>Both listings are read and then merged, because a key can be public here and secret here at
 * the same time: the private half is what {@code status} has to report, and only
 * {@code --list-secret-keys} knows about it.
 */
final class Keyring {
    private final Gpg gpg;

    Keyring(Gpg gpg) {
        this.gpg = gpg;
    }

    List<KeyInfo> list() {
        Map<String, Builder> keys = new LinkedHashMap<>();
        read("--list-keys", keys, false);
        read("--list-secret-keys", keys, true);
        List<KeyInfo> result = new ArrayList<>(keys.size());
        for (Builder builder : keys.values()) {
            result.add(builder.build());
        }
        return result;
    }

    private void read(String listingFlag, Map<String, Builder> keys, boolean secret) {
        Gpg.Result result = gpg.run(List.of(listingFlag, "--with-colons"));
        if (!result.ok()) {
            throw new Failure("gpg " + listingFlag + " failed: " + result.reason());
        }

        // A key is a block of records: pub (or sec), then fpr, then uid, sub and further fpr
        // lines until the next pub/sec. The builder collects one block and is merged when the
        // block ends, so subkey records never have to be matched back to their parent.
        Builder current = null;
        for (String line : result.out().split("\n")) {
            String[] fields = line.split(":", -1);
            String type = fields[0];
            if (type.equals("pub") || type.equals("sec")) {
                merge(keys, current);
                current = new Builder(fields, secret);
            } else if (current != null) {
                current.absorb(type, fields);
            }
        }
        merge(keys, current);
    }

    private static void merge(Map<String, Builder> keys, Builder builder) {
        if (builder == null || builder.fingerprint == null) {
            // No fingerprint means no way to know which key this is, and guessing from the key id
            // is what collisions are made of. Dropping the block is the safe half of a trade where
            // the other half is reporting a key we cannot name.
            return;
        }
        Builder known = keys.get(builder.fingerprint);
        if (known == null) {
            keys.put(builder.fingerprint, builder);
            return;
        }
        known.secret |= builder.secret;
        for (String userId : builder.userIds) {
            if (!known.userIds.contains(userId)) {
                known.userIds.add(userId);
            }
        }
    }

    private static final class Builder {
        /** The order SPEC §2 prints usage in: sign, certify, encrypt, authenticate. */
        private static final String CAPABILITY_ORDER = "SCEA";

        private final LinkedHashSet<Character> capabilities = new LinkedHashSet<>();
        private final List<String> userIds = new ArrayList<>();
        private final String keyId;
        private final String algorithm;
        private final long createdEpoch;
        private final Long expiresEpoch;
        private String fingerprint;
        private boolean secret;

        Builder(String[] fields, boolean secret) {
            this.keyId = field(fields, 4);
            this.algorithm = algorithmLabel(field(fields, 3), field(fields, 2), field(fields, 16));
            this.createdEpoch = epoch(fields, 5);
            this.expiresEpoch = optionalEpoch(fields, 6);
            // `sec` records carry the S/N of the private key in field 15: "+" when the material is
            // here, "#" when it is a stub — a smartcard that is not plugged in, or a key whose
            // secret half was never imported. A stub is listed by --list-secret-keys and cannot
            // sign anything, so it must not be reported as a private key we hold.
            this.secret = secret && !field(fields, 14).equals("#");
            addCapabilities(field(fields, 11));
        }

        void absorb(String type, String[] fields) {
            switch (type) {
                case "fpr" -> {
                    if (fingerprint == null) {
                        fingerprint = field(fields, 9);
                    }
                }
                case "uid" -> {
                    String userId = unescape(field(fields, 9));
                    if (!userId.isEmpty() && !userIds.contains(userId)) {
                        userIds.add(userId);
                    }
                }
                case "sub", "ssb" -> {
                    // A subkey's capability belongs to the key it hangs off — an ed25519 key signs
                    // with the primary and encrypts with a cv25519 subkey, and reporting only the
                    // primary would say "SC" about a key that does both.
                    String validity = field(fields, 1);
                    if (!validity.equals("e") && !validity.equals("r")) {
                        addCapabilities(field(fields, 11));
                    }
                }
                default -> {
                }
            }
        }

        KeyInfo build() {
            StringBuilder usage = new StringBuilder(CAPABILITY_ORDER.length());
            for (int index = 0; index < CAPABILITY_ORDER.length(); index++) {
                char letter = CAPABILITY_ORDER.charAt(index);
                if (capabilities.contains(letter)) {
                    usage.append(letter);
                }
            }
            return new KeyInfo(fingerprint, keyId, algorithm, usage.toString(),
                    createdEpoch, expiresEpoch, List.copyOf(userIds), secret);
        }

        private void addCapabilities(String field) {
            for (int index = 0; index < field.length(); index++) {
                char letter = field.charAt(index);
                // Lowercase letters are what the key can actually do; uppercase is what it was
                // created for. Only the former is a statement about right now.
                if (letter >= 'a' && letter <= 'z') {
                    capabilities.add(Character.toUpperCase(letter));
                }
            }
        }

        private static String algorithmLabel(String algorithm, String bits, String curve) {
            return switch (algorithm) {
                case "1" -> "rsa" + bits;
                case "17" -> "dsa" + bits;
                // Elliptic curve keys are named by their curve and not by their size: "255" would
                // be a worse answer than "ed25519" for the one curve SPEC §2 offers to generate.
                case "18", "19", "22" -> curve.isEmpty() ? "ecc" + bits : curve;
                default -> "algo" + algorithm;
            };
        }

        private static String field(String[] fields, int index) {
            return index < fields.length ? fields[index] : "";
        }

        private static long epoch(String[] fields, int index) {
            try {
                return Long.parseLong(field(fields, index));
            } catch (NumberFormatException missing) {
                return 0L;
            }
        }

        private static Long optionalEpoch(String[] fields, int index) {
            String value = field(fields, index);
            if (value.isEmpty()) {
                return null;
            }
            try {
                return Long.valueOf(value);
            } catch (NumberFormatException malformed) {
                return null;
            }
        }

        /**
         * gpg escapes the characters that would break its own format — a colon in a user id, say —
         * as a backslash and two hex digits.
         */
        private static String unescape(String value) {
            if (value.indexOf('\\') < 0) {
                return value;
            }
            StringBuilder decoded = new StringBuilder(value.length());
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if (character == '\\' && index + 3 < value.length() && value.charAt(index + 1) == 'x') {
                    try {
                        decoded.append((char) Integer.parseInt(value.substring(index + 2, index + 4), 16));
                        index += 3;
                        continue;
                    } catch (NumberFormatException notAnEscape) {
                        // Fall through and keep the backslash as written.
                    }
                }
                decoded.append(character);
            }
            return decoded.toString();
        }
    }
}
