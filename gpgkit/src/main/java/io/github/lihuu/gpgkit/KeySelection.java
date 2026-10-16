package io.github.lihuu.gpgkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Picking keys out of the keyring, by the names people actually type.
 *
 * <p>Whatever the user writes for {@code --key} — a full fingerprint, a long or short key id, or
 * a fragment of a user id — it has to end at exactly one key. Two matches are reported rather
 * than resolved by taking the first: renewing, backing up, signing or publishing the wrong key is
 * a mistake that is invisible until much later.
 *
 * <p>Resolution happens here, against the local keyring listing, rather than by handing
 * {@code --key} to gpg and letting it decide. Two reasons, both of them about failing before
 * anything is written (SPEC §3.4): a name that matches nothing has to be an error before the
 * first signature file exists, and the full fingerprint has to be known up front to be printed
 * in the report and to be the argument of {@code --local-user} and {@code --send-keys}. A short
 * key id can collide; a full fingerprint cannot.
 */
final class KeySelection {
    private KeySelection() {
    }

    /**
     * The single key {@code id} names.
     *
     * <p>Matching is deliberately generous (case-insensitive substring over the fingerprint, the
     * key id and every user id) and the ambiguity check is what makes that safe.
     */
    static KeyInfo unique(List<KeyInfo> keys, String id) {
        return single(matches(keys, id), id);
    }

    /** Every key here whose private half we hold, which is everything a lost machine would take. */
    static List<KeyInfo> withSecret(List<KeyInfo> keys) {
        List<KeyInfo> selected = new ArrayList<>();
        for (KeyInfo key : keys) {
            if (key.secret()) {
                selected.add(key);
            }
        }
        return selected;
    }

    /** Every key here that could sign something right now — what {@code backup} protects by default. */
    static List<KeyInfo> signing(List<KeyInfo> keys) {
        List<KeyInfo> selected = new ArrayList<>();
        for (KeyInfo key : keys) {
            if (key.canSign()) {
                selected.add(key);
            }
        }
        return selected;
    }

    /** The key {@code sign} will sign with: it must be able to sign, and the private half must be here. */
    static KeyInfo forSigning(Keyring keyring, String requested) {
        return resolve(keyring.list(), requested, true);
    }

    /**
     * The key {@code publish} will send.
     *
     * <p>Differs from {@link #forSigning} in one way: an explicitly named key does not need a
     * private half. Publishing a public key you only hold the public half of is legitimate —
     * it is how you make someone else's key reachable — while publishing is never something a
     * command should do by accident, so the default remains the signing key.
     */
    static KeyInfo forPublish(Keyring keyring, String requested) {
        return resolve(keyring.list(), requested, false);
    }

    private static KeyInfo resolve(List<KeyInfo> keys, String requested, boolean requireSecret) {
        if (keys.isEmpty()) {
            throw new Failure("this keyring has no keys at all; generate one with `gpgkit gen` first");
        }
        if (requested == null) {
            return defaultSigningKey(keys);
        }
        KeyInfo key = single(matches(keys, requested), requested);
        if (requireSecret && !key.secret()) {
            throw new Failure("key " + key.fingerprint() + " matches, but its private half is not in this "
                    + "keyring, so it cannot sign anything; a key whose secret half lives on a smartcard "
                    + "or another machine has to be used there");
        }
        return key;
    }

    /**
     * The key a command uses when none was named.
     *
     * <p>Requires the private half, the signing capability and a live expiry, which is exactly
     * {@link KeyInfo#canSign} — the same test {@code status} prints its verdict from, so the two
     * commands cannot disagree about whether this machine can sign.
     */
    private static KeyInfo defaultSigningKey(List<KeyInfo> keys) {
        List<KeyInfo> usable = new ArrayList<>();
        for (KeyInfo key : keys) {
            if (key.canSign()) {
                usable.add(key);
            }
        }
        if (usable.isEmpty()) {
            throw new Failure("no key in this keyring can sign right now (a signing key with its private "
                    + "half here that has not expired); run `gpgkit status` for the details, `gpgkit gen` "
                    + "to make one, or `gpgkit restore` to bring one back");
        }
        if (usable.size() > 1) {
            throw new Failure(usable.size() + " keys here can sign (" + fingerprints(usable)
                    + "); choose one with --key <fingerprint> so the choice is yours and not this tool's");
        }
        return usable.get(0);
    }

    private static List<KeyInfo> matches(List<KeyInfo> keys, String id) {
        if (id.isBlank()) {
            // An empty needle matches every key by substring, which would report "3 keys match"
            // about an option the user did not really give a value for.
            throw new UsageError("--key needs a key id, part of a user id, or `all`");
        }
        String needle = id.trim().toLowerCase(Locale.ROOT);
        List<KeyInfo> found = new ArrayList<>();
        for (KeyInfo key : keys) {
            if (matches(key, needle)) {
                found.add(key);
            }
        }
        return found;
    }

    private static KeyInfo single(List<KeyInfo> matches, String id) {
        if (matches.isEmpty()) {
            throw new Failure("no key here matches `" + id + "`; `gpgkit status` lists the fingerprints");
        }
        if (matches.size() > 1) {
            throw new Failure("`" + id + "` matches " + matches.size() + " keys (" + fingerprints(matches)
                    + "); name one by its full fingerprint");
        }
        return matches.get(0);
    }

    private static boolean matches(KeyInfo key, String needle) {
        if (key.fingerprint().toLowerCase(Locale.ROOT).contains(needle)
                || key.keyId().toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        for (String userId : key.userIds()) {
            if (userId.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String fingerprints(List<KeyInfo> keys) {
        StringBuilder listed = new StringBuilder();
        for (KeyInfo key : keys) {
            if (listed.length() > 0) {
                listed.append(", ");
            }
            listed.append(key.fingerprint());
        }
        return listed.toString();
    }
}
