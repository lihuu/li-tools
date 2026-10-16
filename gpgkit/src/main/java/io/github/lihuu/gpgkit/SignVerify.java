package io.github.lihuu.gpgkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Sign a throwaway payload with a key, then verify the signature back (SPEC §2 restore step 2,
 * hard constraint §3.3).
 *
 * <p>"A key is present" and "a key can sign" are different facts, and only the second one is worth
 * anything after a restore on a new machine: a stub whose smartcard is absent, a key whose private
 * half half-arrived, a keyring the agent cannot write to, an import that brought in only the public
 * block — every one of those passes "the file imported" and fails the first real release. The
 * signature is made over a temporary payload in a scratch directory and is never printed: what
 * matters is the round trip, not the artifact.
 */
final class SignVerify {
    /** Proof that the key signed something that verified, and how much it signed. */
    record Verified(String keyId, long bytes) {
    }

    private SignVerify() {
    }

    static Verified roundTrip(Gpg gpg, KeyInfo key, Path scratch) {
        Path payload = scratch.resolve("sign-verify.txt");
        Path signature = scratch.resolve("sign-verify.txt.asc");
        try {
            Files.writeString(payload, "gpgkit sign/verify check " + Instant.now() + "\n", StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new Failure("could not write the payload for the sign/verify check: " + failure.getMessage());
        }

        Gpg.Result sign = gpg.run(List.of(
                "--local-user", key.fingerprint(),
                "--armor", "--detach-sign",
                "--output", signature.toString(),
                payload.toString()));
        if (!sign.ok()) {
            throw new Failure("the key in this keyring cannot sign: gpg refused to sign a test payload with "
                    + key.fingerprint() + " (" + sign.reason() + "). The import brought in a key, not a usable one");
        }

        Gpg.Result verify = gpg.run(List.of("--verify", signature.toString(), payload.toString()));
        if (!verify.ok()) {
            throw new Failure("the signature made with " + key.fingerprint() + " did not verify ("
                    + verify.reason() + "). Treat this keyring as not restored");
        }

        try {
            return new Verified(key.keyId(), Files.size(payload));
        } catch (IOException failure) {
            throw new Failure("the sign/verify check passed but its payload could not be measured: "
                    + failure.getMessage());
        }
    }
}
