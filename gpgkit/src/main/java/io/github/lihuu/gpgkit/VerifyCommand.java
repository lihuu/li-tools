package io.github.lihuu.gpgkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@code gpgkit verify} — SPEC §2.
 *
 * <p>Two questions, one command: does this signature belong to this file, and does this file match
 * the manifest it is supposed to match. The second is why {@code --checksums} exists — a signed
 * jar is only worth something if it is the jar that was signed.
 *
 * <p>Everything that is not "good" is named. "1 of 5 files did not match" is not something anyone
 * can act on; {@code b.jar: expected 4f2a…, got 91cc…} is. The same goes for a signature that
 * cannot be checked at all, which is a different answer from a bad one and says so.
 *
 * <p>Nothing is imported to make a check pass. A signature whose public key is not here is
 * reported as exactly that, because a verification tool that quietly fetches keys from the
 * network answers a different question than the one it was asked.
 */
final class VerifyCommand {
    private static final Set<String> VALUE_OPTIONS = Set.of("--sig", "--checksums");
    private static final Set<String> BOOLEAN_OPTIONS = Set.of();

    private static final String STATUS_PREFIX = "[GNUPG:] ";

    private final GlobalOptions global;

    VerifyCommand(GlobalOptions global) {
        this.global = global;
    }

    /**
     * What a signature check concluded.
     *
     * @param ok      whether the signature is a valid signature over the file. True for a good
     *                signature whose key has since expired: that signature was made while the key
     *                was live, and it stays verifiable for ever (see docs/key-expiry.md)
     * @param token   the machine-readable half, for --json
     * @param sentence the one-line human answer
     * @param reason  gpg's own last word, when the answer is not good
     */
    private record SignatureCheck(boolean ok, String token, String sentence, String reason,
                                  String fingerprint, String keyId, String userId,
                                  Path file, Path signature) {
    }

    /** One manifest line, and what checking it found. */
    private record EntryResult(String name, String status, String detail) {
    }

    private record ManifestCheck(Path manifest, Path signature, String signatureSentence,
                                 boolean signatureChecked, boolean ok, List<EntryResult> entries) {
    }

    int run(List<String> args) {
        Arguments.Result parsed = Arguments.parse(args, VALUE_OPTIONS, BOOLEAN_OPTIONS);
        if (!parsed.ok()) {
            System.err.println("gpgkit: verify: " + parsed.error());
            return Defaults.EXIT_USAGE;
        }
        Arguments options = parsed.arguments();
        if (options.positional().size() != 1) {
            System.err.println("gpgkit: verify takes exactly one file, but got "
                    + options.positional().size());
            return Defaults.EXIT_USAGE;
        }

        Path file = Path.of(options.positional().get(0));
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new Failure("cannot read " + file);
        }

        Path signature = options.value("--sig") != null
                ? Path.of(options.value("--sig"))
                : file.resolveSibling(file.getFileName() + Checksums.MANIFEST_SIGNATURE_SUFFIX);
        if (!Files.isRegularFile(signature) || !Files.isReadable(signature)) {
            throw new Failure("no signature to check: " + signature
                    + " (sign the file with `gpgkit sign`, or name the signature with --sig)");
        }

        Path manifest = null;
        if (options.value("--checksums") != null) {
            manifest = Path.of(options.value("--checksums"));
            if (!Files.isRegularFile(manifest) || !Files.isReadable(manifest)) {
                throw new Failure("cannot read the checksum manifest: " + manifest);
            }
        }

        Gpg gpg = new Gpg();
        if (!gpg.available()) {
            throw new Failure("gpg is not on PATH; install GnuPG first");
        }

        SignatureCheck check = check(gpg, signature, file);
        ManifestCheck manifestCheck = manifest == null ? null : compare(gpg, manifest);

        if (global.json()) {
            printJson(check, manifestCheck);
        } else {
            printHuman(check, manifestCheck);
        }

        if (!check.ok()) {
            System.err.println("gpgkit: verify: " + check.file() + " (" + check.signature() + "): "
                    + check.sentence() + (check.reason() == null ? "" : " — " + check.reason()));
            return Defaults.EXIT_FAILURE;
        }
        if (manifestCheck != null && !manifestCheck.ok()) {
            for (EntryResult entry : manifestCheck.entries()) {
                if (!entry.status().equals("OK")) {
                    System.err.println("gpgkit: verify: " + entry.name() + " (" + manifestCheck.manifest()
                            + "): " + entry.status() + (entry.detail() == null ? "" : " — " + entry.detail()));
                }
            }
            if (!manifestCheck.signatureChecked() || !manifestCheck.signatureSentence().startsWith("good")) {
                System.err.println("gpgkit: verify: " + manifestCheck.manifest() + ": "
                        + manifestCheck.signatureSentence());
            }
            return Defaults.EXIT_FAILURE;
        }
        return Defaults.EXIT_OK;
    }

    /**
     * Runs one detached-signature check and reads the answer out of gpg's status lines.
     *
     * <p>{@code --status-fd 1} rather than the human report on stderr: the status lines are a
     * documented, stable interface, while the prose around them has been reworded between gpg
     * releases and translated into every locale. Deciding "good" from prose is a bug that appears
     * only on someone else's laptop.
     */
    private static SignatureCheck check(Gpg gpg, Path signature, Path file) {
        Gpg.Result result = gpg.run(List.of("--status-fd", "1", "--verify",
                signature.toAbsolutePath().toString(), file.toAbsolutePath().toString()));

        String fingerprint = null;
        String keyId = null;
        String userId = null;
        String token = null;

        for (String line : result.out().split("\n")) {
            if (!line.startsWith(STATUS_PREFIX)) {
                continue;
            }
            String status = line.substring(STATUS_PREFIX.length());
            String[] fields = status.split(" ", 3);
            switch (fields[0]) {
                case "VALIDSIG" -> fingerprint = at(fields, 1);
                case "GOODSIG" -> {
                    token = "good";
                    keyId = at(fields, 1);
                    userId = at(fields, 2);
                }
                case "BADSIG" -> {
                    token = "bad";
                    keyId = at(fields, 1);
                    userId = at(fields, 2);
                }
                case "EXPKEYSIG" -> {
                    token = "expired-key";
                    keyId = at(fields, 1);
                    userId = at(fields, 2);
                }
                case "REVKEYSIG" -> {
                    token = "revoked-key";
                    keyId = at(fields, 1);
                    userId = at(fields, 2);
                }
                case "EXPSIG" -> {
                    token = "expired-signature";
                    keyId = at(fields, 1);
                    userId = at(fields, 2);
                }
                case "ERRSIG" -> {
                    // gpg could not even get as far as comparing digests: the public key is not here,
                    // or the algorithm is unsupported. Not the same answer as "bad", and reporting it
                    // as "bad" would send someone looking for a corrupted download that never happened.
                    token = token == null ? "unknown-key" : token;
                    keyId = at(fields, 1);
                }
                default -> {
                }
            }
        }

        if (token == null) {
            // No status line we recognise: not a signature file, an empty file, gpg refusing. The exit
            // code and gpg's last line are all there is to go on.
            return new SignatureCheck(false, "unchecked", "could not check this signature",
                    result.reason(), null, null, null, file, signature);
        }
        boolean ok = token.equals("good") || token.equals("expired-key");
        String sentence = switch (token) {
            case "good" -> "good signature";
            case "bad" -> "BAD SIGNATURE — this file is not the file that was signed";
            case "unknown-key" -> "the public key that made this signature is not in this keyring"
                    + (keyId == null ? "" : " (key id " + keyId + ")");
            case "expired-key" -> "good signature, made by a key that has since expired";
            case "revoked-key" -> "the key that made this signature has been revoked";
            case "expired-signature" -> "the signature itself has expired";
            default -> "could not check this signature";
        };
        return new SignatureCheck(ok, token, sentence,
                ok ? null : result.reason(), fingerprint, keyId, userId, file, signature);
    }

    /**
     * Checks every line of a manifest against the files it names.
     *
     * <p>Names resolve against the manifest's own directory, which is what {@code sha256sum -c}
     * does and what {@code sign} wrote them for.
     *
     * <p>The manifest's own detached signature is checked when it is there. Without it the
     * checksums still detect a corrupted download, but say nothing about who wrote the list —
     * and a list anyone can edit is not a reason to trust a file. A missing signature is reported
     * as a warning rather than a failure because SPEC §2 does not require one; a signature that is
     * present and does not verify is a failure, because that is not a weaker guarantee, it is a
     * contradiction.
     */
    private static ManifestCheck compare(Gpg gpg, Path manifest) {
        Path directory = manifest.toAbsolutePath().getParent();
        Path manifestSignature = manifest.resolveSibling(
                manifest.getFileName() + Checksums.MANIFEST_SIGNATURE_SUFFIX);

        String signatureSentence = "no detached signature beside the manifest (" + manifestSignature + ")";
        boolean signatureChecked = false;
        if (Files.isRegularFile(manifestSignature)) {
            SignatureCheck manifestCheck = check(gpg, manifestSignature, manifest);
            signatureChecked = true;
            signatureSentence = manifestCheck.sentence()
                    + (manifestCheck.fingerprint() == null ? "" : " from " + manifestCheck.fingerprint());
            if (!manifestCheck.ok()) {
                return new ManifestCheck(manifest, manifestSignature, signatureSentence, true, false, List.of());
            }
        }

        String text;
        try {
            text = Files.readString(manifest, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new Failure("could not read the manifest " + manifest + ": " + failure.getMessage());
        }
        List<Checksums.Entry> entries = Checksums.parse(text);
        List<EntryResult> results = new ArrayList<>(entries.size());
        boolean ok = true;
        for (Checksums.Entry entry : entries) {
            if (!Checksums.wellFormed(entry.hash())) {
                results.add(new EntryResult(entry.name(), "MALFORMED",
                        "this line is not a SHA-256 digest and a file name"));
                ok = false;
                continue;
            }
            Path target = directory.resolve(entry.name()).normalize();
            if (!Files.isRegularFile(target)) {
                results.add(new EntryResult(entry.name(), "MISSING", "no such file at " + target));
                ok = false;
                continue;
            }
            String actual = Checksums.of(target);
            if (!actual.equals(entry.hash())) {
                results.add(new EntryResult(entry.name(), "MISMATCH",
                        "expected " + entry.hash() + ", got " + actual));
                ok = false;
                continue;
            }
            results.add(new EntryResult(entry.name(), "OK", null));
        }

        if (entries.isEmpty()) {
            // An empty manifest checks nothing while looking like it checked everything. `sign` never
            // writes one, so seeing one means the file is not the manifest it claims to be.
            results.add(new EntryResult(manifest.getFileName().toString(), "MALFORMED",
                    "the manifest lists no files, so it checks nothing"));
            ok = false;
        }
        return new ManifestCheck(manifest, manifestSignature, signatureSentence, signatureChecked, ok, results);
    }

    private void printHuman(SignatureCheck check, ManifestCheck manifestCheck) {
        System.out.println("file          " + check.file());
        System.out.println("signature     " + check.signature());
        if (check.fingerprint() != null) {
            System.out.println("signed by     " + check.fingerprint());
        }
        if (check.userId() != null) {
            System.out.println("uid           " + check.userId());
        }
        System.out.println("status        " + check.sentence());
        if (check.reason() != null) {
            System.out.println("gpg says      " + check.reason());
        }
        if (check.token().equals("unknown-key")) {
            System.out.println("              fetch the public key that made this signature, for example:");
            System.out.println("              gpg --keyserver " + Defaults.KEYSERVER + " --recv-keys "
                    + (check.keyId() == null ? "<key id>" : check.keyId()));
        }
        if (manifestCheck == null) {
            return;
        }

        System.out.println("checksums     " + manifestCheck.manifest());
        System.out.println("manifest sig  " + manifestCheck.signatureSentence());
        for (EntryResult entry : manifestCheck.entries()) {
            System.out.println("  " + pad(entry.status(), 10) + entry.name()
                    + (entry.detail() == null ? "" : "  (" + entry.detail() + ")"));
        }
    }

    private void printJson(SignatureCheck check, ManifestCheck manifestCheck) {
        Json json = new Json();
        json.beginObject();
        json.name("file").value(check.file().toString());
        json.name("signature").value(check.signature().toString());
        json.name("fingerprint").value(check.fingerprint());
        json.name("keyId").value(check.keyId());
        json.name("userIds").beginArray();
        if (check.userId() != null) {
            json.value(check.userId());
        }
        json.endArray();
        json.name("status").value(check.token());
        json.name("message").value(check.sentence());
        json.name("ok").value(check.ok());
        if (manifestCheck != null) {
            json.name("checksums").beginObject();
            json.name("file").value(manifestCheck.manifest().toString());
            json.name("signature").value(manifestCheck.signature().toString());
            json.name("signatureStatus").value(manifestCheck.signatureSentence());
            json.name("ok").value(manifestCheck.ok());
            json.name("entries").beginArray();
            for (EntryResult entry : manifestCheck.entries()) {
                json.beginObject();
                json.name("name").value(entry.name());
                json.name("status").value(entry.status());
                json.name("detail").value(entry.detail());
                json.endObject();
            }
            json.endArray();
            json.endObject();
        }
        json.endObject();
        System.out.println(json);
    }

    private static String pad(String text, int width) {
        if (text.length() >= width) {
            return text + " ";
        }
        return text + " ".repeat(width - text.length());
    }

    private static String at(String[] fields, int index) {
        return index < fields.length ? fields[index] : null;
    }
}
