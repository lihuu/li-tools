package io.github.lihuu.gpgkit;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * {@code gpgkit publish} — SPEC §2.
 *
 * <p>Sends <b>one public key, named by its full fingerprint</b>. The argument after
 * {@code --send-keys} is always a full fingerprint resolved from the public listing, never a
 * user id, never a short key id, and never anything derived from the private half: a keyserver
 * request that names a key imprecisely is how the wrong key gets published, and a keyserver is
 * not somewhere to find out.
 *
 * <p>The command is public by nature, so it fails loudly and usefully. A keyserver that refuses
 * is a different problem depending on why — a different server, a proxy, a blocked port — and
 * "gpg exited 2" sends the user nowhere. Every failure here therefore ends with the two or three
 * things worth trying, in the order worth trying them.
 */
final class PublishCommand {
    private static final Set<String> VALUE_OPTIONS = Set.of("--key", "--keyserver");
    private static final Set<String> BOOLEAN_OPTIONS = Set.of();

    /**
     * A single public key is a few kilobytes. Anything slower than this is not a slow transfer,
     * it is a network that is not going to answer, and the user is better served by the advice
     * below than by waiting.
     */
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(30);

    /** Short: this is a confirmation, not the operation (same reasoning as `status`). */
    private static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(8);

    /** Known-good alternatives, offered when the configured server will not take the key. */
    private static final String ALTERNATIVES = "hkps://keys.openpgp.org, hkps://keyserver.ubuntu.com, hkps://pgp.mit.edu";

    private final GlobalOptions global;

    PublishCommand(GlobalOptions global) {
        this.global = global;
    }

    int run(List<String> args) {
        Arguments.Result parsed = Arguments.parse(args, VALUE_OPTIONS, BOOLEAN_OPTIONS);
        if (!parsed.ok()) {
            System.err.println("gpgkit: publish: " + parsed.error());
            return Defaults.EXIT_USAGE;
        }
        Arguments options = parsed.arguments();
        if (!options.positional().isEmpty()) {
            System.err.println("gpgkit: publish takes no arguments, but got: " + options.positional().get(0));
            return Defaults.EXIT_USAGE;
        }

        String keyserver = options.value("--keyserver") == null
                ? Defaults.KEYSERVER
                : options.value("--keyserver");
        if (!keyserver.contains("://")) {
            System.err.println("gpgkit: publish: --keyserver needs a scheme, for example " + Defaults.KEYSERVER
                    + " (got: " + keyserver + ")");
            return Defaults.EXIT_USAGE;
        }

        Gpg gpg = new Gpg();
        if (!gpg.available()) {
            throw new Failure("gpg is not on PATH; install GnuPG first");
        }
        KeyInfo key = KeySelection.forPublish(new Keyring(gpg), options.value("--key"));
        publish(global, key, keyserver);
        return Defaults.EXIT_OK;
    }

    /**
     * Sends one key's public half and reports whether the server can see it afterwards.
     *
     * <p>Shared with {@code gen --publish}, which is why it takes a resolved key rather than
     * parsing anything: the two commands must send the same thing in the same way, and the only
     * way to be sure of that is to have one implementation of it.
     */
    static void publish(GlobalOptions global, KeyInfo key, String keyserver) {
        Gpg gpg = new Gpg();
        if (!gpg.available()) {
            throw new Failure("gpg is not on PATH; install GnuPG first");
        }

        // The full fingerprint, and nothing else. `--send-keys` uploads the public half of whatever
        // it names; naming it exactly is what keeps this from becoming an upload of everything in
        // the keyring (SPEC §3.1 — a private key must never leave this machine, and the way to
        // guarantee that is to never hand gpg a selector that could mean more than one key).
        Gpg.Result result = gpg.run(
                List.of("--keyserver", keyserver, "--send-keys", key.fingerprint()), null, SEND_TIMEOUT);
        if (!result.ok()) {
            throw new Failure("could not publish " + key.fingerprint() + " to " + keyserver + ": "
                    + result.reason() + "\n"
                    + "  try another keyserver:  --keyserver " + ALTERNATIVES + "\n"
                    + "  behind a proxy?         export https_proxy=http://host:port  (gpg's dirmngr reads it)\n"
                    + "  is the server up?       gpgkit doctor, or gpg --keyserver " + keyserver
                    + " --recv-keys " + key.fingerprint() + "\n"
                    + "  corporate networks      often block the HKPS port outright; that needs a network "
                    + "change, not another flag");
        }

        // What the server said about the key a moment after accepting it. Deliberately reported and
        // never fatal: propagation is not instant, and a command that failed here would be reporting
        // its own impatience as a problem with the key.
        Keyserver.Probe probe = Keyserver.probe(keyserver, key.fingerprint(), CONFIRM_TIMEOUT);
        if (global.json()) {
            printJson(key, keyserver, probe);
        } else {
            printHuman(key, keyserver, probe);
        }
    }

    private static void printHuman(KeyInfo key, String keyserver, Keyserver.Probe probe) {
        System.out.println("published    " + key.fingerprint());
        System.out.println("  keyserver  " + keyserver);
        for (String userId : key.userIds()) {
            System.out.println("  uid        " + userId);
        }
        System.out.println("  visible    " + visibleText(probe));
        if (probe.visibility() != Keyserver.Visibility.VISIBLE) {
            System.out.println("             the server accepted the key, so this is a propagation delay rather "
                    + "than a failure; `gpgkit status` will confirm it in a minute");
        }
    }

    private static void printJson(KeyInfo key, String keyserver, Keyserver.Probe probe) {
        Json json = new Json();
        json.beginObject();
        json.name("fingerprint").value(key.fingerprint());
        json.name("keyserver").value(keyserver);
        json.name("userIds").beginArray();
        for (String userId : key.userIds()) {
            json.value(userId);
        }
        json.endArray();
        json.name("visible").value(probe.visibility() == Keyserver.Visibility.VISIBLE);
        json.name("detail").value(probe.detail());
        json.endObject();
        System.out.println(json);
    }

    private static String visibleText(Keyserver.Probe probe) {
        return switch (probe.visibility()) {
            case VISIBLE -> "yes";
            case NOT_PUBLISHED -> "not yet";
            case UNREACHABLE -> "could not tell";
        };
    }
}
