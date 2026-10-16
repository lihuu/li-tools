package io.github.lihuu.gpgkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * {@code gpgkit extend} — SPEC §2.
 *
 * <p>Renewal changes the self-signature and nothing else, which is the whole reason it is the right
 * answer to an expiry date and generating a new key pair is not: the fingerprint stays, the key
 * material does not move, and signatures published before today keep verifying exactly as they did
 * (docs/key-expiry.md). The dates are read before and read again after rather than computed, so the
 * line the user reads is what gpg actually holds and not what this command intended.
 *
 * <p>Subkeys are renewed along with the primary. Renewing only the primary is the tempting
 * one-command version and it leaves the encryption subkey to expire on its own schedule — a key
 * that can sign releases and cannot encrypt anything.
 */
final class ExtendCommand {
    private static final String USAGE = """
            usage: gpgkit extend [--key <id>] [--expire 2y] [--publish]""";

    /** SPEC §2 default. */
    private static final String DEFAULT_EXPIRE = "2y";

    /**
     * What gpg accepts for an expiry and what a person actually types. Checked here rather than
     * left to gpg so the answer is a usage error naming the forms that work, instead of a failure
     * quoting gpg's own grammar.
     */
    private static final Pattern EXPIRY = Pattern.compile("^[0-9]+[ymwd]$|^never$|^[0-9]{4}-[0-9]{2}-[0-9]{2}$");

    private final GlobalOptions global;

    ExtendCommand(GlobalOptions global) {
        this.global = global;
    }

    int run(List<String> args) {
        try {
            return execute(args);
        } catch (UsageError usage) {
            System.err.println("gpgkit: extend: " + usage.getMessage());
            System.err.println(USAGE);
            return Defaults.EXIT_USAGE;
        }
    }

    private int execute(List<String> args) {
        String keyOption = null;
        String expire = DEFAULT_EXPIRE;
        boolean publish = false;
        for (int index = 0; index < args.size(); index++) {
            String argument = args.get(index);
            if (argument.equals("--key")) {
                keyOption = value(args, index, "--key");
                index++;
            } else if (argument.equals("--expire")) {
                expire = value(args, index, "--expire").toLowerCase(Locale.ROOT);
                index++;
            } else if (argument.equals("--publish")) {
                publish = true;
            } else {
                throw new UsageError("unknown option: " + argument);
            }
        }
        if (!EXPIRY.matcher(expire).matches()) {
            throw new UsageError("--expire " + expire + " is not something gpg understands;"
                    + " write it as 2y, 18m, 30d, never, or a date like 2030-01-01");
        }

        Gpg gpg = new Gpg();
        if (!gpg.available()) {
            throw new Failure("gpg is not on PATH; install GnuPG first");
        }
        KeyInfo key = select(new Keyring(gpg).list(), keyOption);
        if (!key.secret()) {
            throw new Failure("the private half of " + key.fingerprint()
                    + " is not in this keyring, so its expiry cannot be changed here");
        }

        List<Expiry> before = expiries(gpg, key.fingerprint());
        renew(gpg, key.fingerprint(), expire, hasSubkeys(before));
        List<Expiry> after = expiries(gpg, key.fingerprint());

        if (global.json()) {
            printJson(key, expire, before, after);
        } else if (global.quiet()) {
            System.out.println(primaryExpiry(before) + " → " + primaryExpiry(after));
        } else {
            report("key", key.fingerprint() + (key.userIds().isEmpty() ? "" : "  \"" + key.userIds().get(0) + "\""));
            for (int index = 0; index < before.size() && index < after.size(); index++) {
                report("expiry", expiryLine(before.get(index), after.get(index)));
            }
        }

        if (publish) {
            int published = new PublishCommand(global).run(List.of("--key", key.fingerprint()));
            if (published != Defaults.EXIT_OK) {
                report("publish", "failed: the renewed key is on this machine only. Until the keyserver has it,"
                        + " everyone else still sees the old expiry date — retry `gpgkit publish` when the"
                        + " network allows it");
                return published;
            }
            return Defaults.EXIT_OK;
        }

        report("next", "the self-signature changed, so the keyserver still has the old public key:"
                + " run `gpgkit publish`, then `gpgkit backup` so the backup matches");
        return Defaults.EXIT_OK;
    }

    /**
     * The key to renew: the one named, or the only usable signing key here.
     *
     * <p>Two signing keys and no {@code --key} is a refusal, not a coin toss. Renewing the wrong
     * key looks like success until a release cannot be signed, and by then the expiry date that
     * was about to lapse is the one nobody watched.
     */
    private static KeyInfo select(List<KeyInfo> keys, String option) {
        if (option != null) {
            return KeySelection.unique(keys, option);
        }
        List<KeyInfo> signing = KeySelection.signing(keys);
        if (signing.isEmpty()) {
            throw new Failure("no usable signing key in this keyring, so there is no expiry to move;"
                    + " `gpgkit status` lists what is here");
        }
        if (signing.size() > 1) {
            StringBuilder fingerprints = new StringBuilder();
            for (KeyInfo key : signing) {
                fingerprints.append(fingerprints.isEmpty() ? "" : ", ").append(key.fingerprint());
            }
            throw new Failure("this keyring has " + signing.size() + " usable signing keys (" + fingerprints
                    + "); name the one to renew with --key");
        }
        return signing.get(0);
    }

    /**
     * Moves the expiry, primary and subkeys.
     *
     * <p>Two calls because gpg needs two: {@code --quick-set-expire fpr expire} renews the primary,
     * and the same command with {@code *} renews the subkeys — passing {@code *} alone leaves the
     * primary exactly as it was, which is how a renewal ends up half done.
     */
    private static void renew(Gpg gpg, String fingerprint, String expire, boolean subkeys) {
        Gpg.Result primary = gpg.run(List.of("--quick-set-expire", fingerprint, expire));
        if (!primary.ok()) {
            throw new Failure("could not renew " + fingerprint + ": " + GpgOutput.reason(primary) + unlockedHint(primary));
        }
        if (!subkeys) {
            return;
        }
        Gpg.Result renewed = gpg.run(List.of("--quick-set-expire", fingerprint, expire, "*"));
        if (!renewed.ok()) {
            throw new Failure("the primary key now expires on the new date, but its subkeys could not be renewed: "
                    + GpgOutput.reason(renewed) + ". Run `gpgkit extend` again once that is fixed");
        }
    }

    private static String unlockedHint(Gpg.Result result) {
        if (result.stderr().contains("batchmode") || result.stderr().contains("passphrase")) {
            return ". That key is protected by its own passphrase, and gpgkit never prompts for one"
                    + " (SPEC §3.5), so the change has to be made where the passphrase can be entered";
        }
        return "";
    }

    /**
     * Every expiry this key has: the primary and each subkey, with the fingerprint each date
     * belongs to.
     *
     * <p>Read from gpg's own listing rather than from {@link Keyring}, which merges a key's subkey
     * capabilities into the primary and reports one date for the lot — the right answer for
     * {@code status}, and not enough to show which of them changed.
     */
    private static List<Expiry> expiries(Gpg gpg, String fingerprint) {
        Gpg.Result result = gpg.run(List.of("--with-colons", "--list-keys", fingerprint));
        if (!result.ok()) {
            throw new Failure("gpg could not list " + fingerprint + ": " + GpgOutput.reason(result));
        }
        List<Expiry> entries = new ArrayList<>();
        String kind = null;
        // The date belongs to the pub/sub record and the fingerprint to the fpr record that
        // follows it, so both are carried across one line: reading the expiry off the fpr line
        // reports every key as never expiring, which is a wrong answer that looks like a fine one.
        Long expires = null;
        for (String line : result.out().split("\n")) {
            String[] fields = line.split(":", -1);
            if (fields[0].equals("pub")) {
                kind = "primary";
                expires = epoch(fields, 6);
            } else if (fields[0].equals("sub")) {
                kind = "subkey";
                expires = epoch(fields, 6);
            } else if (fields[0].equals("fpr") && kind != null) {
                entries.add(new Expiry(kind, fields[9], expires));
                kind = null;
            }
        }
        return entries;
    }

    private static boolean hasSubkeys(List<Expiry> expiries) {
        for (Expiry expiry : expiries) {
            if (expiry.kind().equals("subkey")) {
                return true;
            }
        }
        return false;
    }

    private static String expiryLine(Expiry before, Expiry after) {
        String label = before.kind().equals("primary") ? "primary" : "subkey " + shortId(before.fingerprint());
        return date(before.expiresEpoch()) + "  →  " + date(after.expiresEpoch()) + "   (" + label + ")";
    }

    private static String primaryExpiry(List<Expiry> expiries) {
        for (Expiry expiry : expiries) {
            if (expiry.kind().equals("primary")) {
                return date(expiry.expiresEpoch());
            }
        }
        return "unknown";
    }

    private static String date(Long epoch) {
        return epoch == null ? "no expiry" : Dates.localDate(epoch);
    }

    private static String shortId(String fingerprint) {
        return fingerprint.length() > 16 ? fingerprint.substring(fingerprint.length() - 16) : fingerprint;
    }

    private static Long epoch(String[] fields, int index) {
        String value = index < fields.length ? fields[index] : "";
        if (value.isEmpty()) {
            // An empty expiry column is gpg's way of saying "never expires" — a date that means
            // the opposite of the 1970 that a zero here would print.
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    private void printJson(KeyInfo key, String expire, List<Expiry> before, List<Expiry> after) {
        Json json = new Json();
        json.beginObject();
        json.name("fingerprint").value(key.fingerprint());
        json.name("keyId").value(key.keyId());
        json.name("userIds").beginArray();
        for (String userId : key.userIds()) {
            json.value(userId);
        }
        json.endArray();
        json.name("requested").value(expire);
        json.name("expiry").beginArray();
        for (int index = 0; index < before.size() && index < after.size(); index++) {
            json.beginObject();
            json.name("kind").value(before.get(index).kind());
            json.name("fingerprint").value(before.get(index).fingerprint());
            json.name("before").value(jsonDate(before.get(index)));
            json.name("after").value(jsonDate(after.get(index)));
            json.endObject();
        }
        json.endArray();
        json.endObject();
        System.out.println(json);
    }

    private static String jsonDate(Expiry expiry) {
        return expiry.expiresEpoch() == null ? null : Dates.localDate(expiry.expiresEpoch());
    }

    private void report(String label, String detail) {
        if (global.json() || global.quiet()) {
            return;
        }
        System.out.printf("%-12s  %s%n", label, detail);
    }

    private static String value(List<String> args, int index, String option) {
        if (index + 1 >= args.size()) {
            throw new UsageError(option + " needs a value");
        }
        return args.get(index + 1);
    }

    /** One date in a key's file: the primary's or a subkey's, and whose it is. */
    private record Expiry(String kind, String fingerprint, Long expiresEpoch) {
    }
}
