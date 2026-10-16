package io.github.lihuu.gpgkit;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code gpgkit status} — SPEC §2.
 *
 * <p>Answers one question: what keys are on this machine, and when do they stop working. It is
 * read on machines that have no keys at all as often as on machines that have one, so the empty
 * case is an ordinary answer rather than an error.
 */
final class StatusCommand {
    /**
     * Short, because keyserver visibility is a nice-to-have: a status command that takes half a
     * minute whenever the network is down is a status command nobody runs.
     */
    private static final Duration KEYSERVER_TIMEOUT = Duration.ofSeconds(8);

    private final GlobalOptions global;

    StatusCommand(GlobalOptions global) {
        this.global = global;
    }

    int run(List<String> args) {
        if (!args.isEmpty()) {
            System.err.println("gpgkit: status takes no arguments");
            return Defaults.EXIT_USAGE;
        }
        Gpg gpg = new Gpg();
        if (!gpg.available()) {
            throw new Failure("gpg is not on PATH; install GnuPG first");
        }

        List<KeyInfo> keys = new Keyring(gpg).list();
        Verdict verdict = verdict(keys);
        // The probe costs a network round trip per key, and the quiet form of this command prints
        // one line that does not depend on it. JSON does include it, so JSON pays the cost.
        Map<String, Keyserver.Probe> visibility = global.json() || !global.quiet()
                ? probe(keys)
                : Map.of();

        if (global.json()) {
            printJson(keys, visibility, verdict);
        } else if (global.quiet()) {
            System.out.println(verdict.message());
        } else {
            printHuman(keys, visibility, verdict);
        }
        // Best-effort by contract: neither an unreachable keyserver nor a missing key changes
        // this. `status` reports, it does not judge the network.
        return Defaults.EXIT_OK;
    }

    /**
     * Asks about each key, and stops asking once the server has failed to answer.
     *
     * <p>The stopping matters: with the server down, one timeout per key would turn a two-second
     * command into a wait proportional to the size of the keyring, for an answer that is already
     * known.
     */
    private static Map<String, Keyserver.Probe> probe(List<KeyInfo> keys) {
        Map<String, Keyserver.Probe> visibility = new LinkedHashMap<>();
        boolean reachable = true;
        for (KeyInfo key : keys) {
            if (!reachable) {
                visibility.put(key.fingerprint(),
                        new Keyserver.Probe(Keyserver.Visibility.UNREACHABLE, "not asked"));
                continue;
            }
            Keyserver.Probe probe = Keyserver.probe(Defaults.KEYSERVER, key.fingerprint(), KEYSERVER_TIMEOUT);
            if (probe.visibility() == Keyserver.Visibility.UNREACHABLE) {
                reachable = false;
            }
            visibility.put(key.fingerprint(), probe);
        }
        return visibility;
    }

    private static Verdict verdict(List<KeyInfo> keys) {
        long soonest = Long.MAX_VALUE;
        boolean anyUsable = false;
        for (KeyInfo key : keys) {
            if (!key.canSign()) {
                continue;
            }
            anyUsable = true;
            Long days = key.daysRemaining();
            if (days != null && days < soonest) {
                soonest = days;
            }
        }
        if (!anyUsable) {
            return new Verdict("WARN", "WARN: 没有可用签名密钥");
        }
        if (soonest < Defaults.EXPIRY_WARN_DAYS) {
            return new Verdict("WARN", "WARN: " + Defaults.EXPIRY_WARN_DAYS + " 天内到期");
        }
        return new Verdict("OK", "OK");
    }

    private void printHuman(List<KeyInfo> keys, Map<String, Keyserver.Probe> visibility, Verdict verdict) {
        if (keys.isEmpty()) {
            System.out.println("no keys in this keyring");
        }
        for (KeyInfo key : keys) {
            System.out.println("key          " + key.fingerprint());
            for (String userId : key.userIds()) {
                System.out.println("  uid        " + userId);
            }
            System.out.println("  algorithm  " + key.algorithm());
            System.out.println("  usage      " + key.usage());
            System.out.println("  secret key " + (key.secret() ? "yes" : "no"));
            System.out.println("  created    " + Dates.localDate(key.createdEpoch()));
            System.out.println("  expires    " + expiryText(key));
            System.out.println("  keyserver  " + visibilityText(visibility.get(key.fingerprint())));
        }
        System.out.println("verdict      " + verdict.message());
    }

    private void printJson(List<KeyInfo> keys, Map<String, Keyserver.Probe> visibility, Verdict verdict) {
        Json json = new Json();
        json.beginObject();
        json.name("keys").beginArray();
        for (KeyInfo key : keys) {
            json.beginObject();
            json.name("fingerprint").value(key.fingerprint());
            json.name("userIds").beginArray();
            for (String userId : key.userIds()) {
                json.value(userId);
            }
            json.endArray();
            json.name("algorithm").value(key.algorithm());
            json.name("usage").value(key.usage());
            json.name("secretKey").value(key.secret());
            json.name("created").value(Dates.localDate(key.createdEpoch()));
            Long expires = key.expiresEpoch();
            if (expires == null) {
                json.name("expires").nullValue();
                json.name("daysRemaining").nullValue();
                json.name("expired").value(false);
            } else {
                json.name("expires").value(Dates.localDate(expires));
                json.name("daysRemaining").value(Dates.daysRemaining(expires));
                json.name("expired").value(Dates.expired(expires));
            }
            json.name("keyserver").value(visibilityText(visibility.get(key.fingerprint())));
            json.endObject();
        }
        json.endArray();
        json.name("verdict").value(verdict.level());
        json.name("message").value(verdict.message());
        json.endObject();
        System.out.println(json);
    }

    private static String expiryText(KeyInfo key) {
        Long expires = key.expiresEpoch();
        if (expires == null) {
            return "never";
        }
        String date = Dates.localDate(expires);
        if (Dates.expired(expires)) {
            return date + " (expired)";
        }
        return date + " (" + Dates.daysRemaining(expires) + " days left)";
    }

    private static String visibilityText(Keyserver.Probe probe) {
        if (probe == null) {
            return "unknown";
        }
        return switch (probe.visibility()) {
            case VISIBLE -> "visible";
            case NOT_PUBLISHED -> "not published";
            case UNREACHABLE -> "unknown";
        };
    }

    /**
     * @param level   the machine-readable half: OK or WARN, with no language in it
     * @param message the sentence SPEC §2 specifies, which is what a person reads
     */
    private record Verdict(String level, String message) {
    }
}
