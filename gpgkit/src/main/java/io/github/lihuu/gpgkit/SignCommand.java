package io.github.lihuu.gpgkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code gpgkit sign} — SPEC §2.
 *
 * <p>Signs release artifacts: one armored detached signature per file, and optionally the
 * {@code SHA256SUMS} manifest a consumer actually wants, signed in turn. The manifest exists so
 * that verifying a release means trusting one signature instead of one per artifact.
 *
 * <p><b>Nothing final is written until every signature exists.</b> Each signature is produced in
 * a hidden temporary file beside its destination and the files are renamed into place only after
 * the last one succeeded (SPEC §3.4). The alternative — signing straight into {@code foo.asc}
 * and stopping halfway — leaves two signed artifacts beside three unsigned ones, and nothing on
 * disk says which is which. That is exactly the state a release should never be left in.
 */
final class SignCommand {
    private static final Set<String> VALUE_OPTIONS = Set.of("--checksums", "--out", "--key");
    private static final Set<String> BOOLEAN_OPTIONS = Set.of();

    /** SPEC §2 spells the one supported value; anything else is a usage error, not a silent fallback. */
    private static final String SUPPORTED_CHECKSUMS = "sha256";

    private final GlobalOptions global;

    SignCommand(GlobalOptions global) {
        this.global = global;
    }

    /** A signature waiting to be renamed into place. */
    private record Staged(String kind, Path temporary, Path destination) {
    }

    int run(List<String> args) {
        Arguments.Result parsed = Arguments.parse(args, VALUE_OPTIONS, BOOLEAN_OPTIONS);
        if (!parsed.ok()) {
            System.err.println("gpgkit: sign: " + parsed.error());
            return Defaults.EXIT_USAGE;
        }
        Arguments options = parsed.arguments();
        if (options.positional().isEmpty()) {
            System.err.println("gpgkit: sign needs at least one file to sign");
            return Defaults.EXIT_USAGE;
        }

        String checksums = options.value("--checksums");
        if (checksums != null && !checksums.equalsIgnoreCase(SUPPORTED_CHECKSUMS)) {
            System.err.println("gpgkit: sign: unsupported --checksums algorithm: " + checksums
                    + " (SPEC §2 offers " + SUPPORTED_CHECKSUMS + ")");
            return Defaults.EXIT_USAGE;
        }
        boolean withChecksums = checksums != null;

        Path out = null;
        if (options.value("--out") != null) {
            out = Path.of(options.value("--out"));
            if (!Files.isDirectory(out)) {
                // Not created on demand: a mistyped --out that silently appears would scatter
                // signatures into a directory nobody is watching, which reads exactly like success.
                throw new Failure("output directory does not exist: " + out + " (create it first)");
            }
        }

        List<Path> files = new ArrayList<>(options.positional().size());
        List<String> problems = new ArrayList<>();
        for (String argument : options.positional()) {
            Path file = Path.of(argument);
            if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
                problems.add(argument);
            } else {
                files.add(file);
            }
        }
        if (!problems.isEmpty()) {
            // All of them at once: fixing a list of five paths one error message at a time is five
            // runs of this command, and the fifth is the one where someone stops reading.
            throw new Failure("cannot read " + problems.size() + " of the files named: "
                    + String.join(", ", problems));
        }

        Path manifestDirectory = null;
        if (withChecksums) {
            manifestDirectory = out != null ? out : Path.of("").toAbsolutePath();
        }

        LinkedHashSet<Path> destinations = new LinkedHashSet<>();
        for (Path file : files) {
            Path destination = destinationFor(file, out);
            if (!destinations.add(destination)) {
                throw new Failure("two of the files named would produce the same signature, " + destination
                        + "; give them different names or sign them one directory at a time");
            }
        }
        if (withChecksums && !destinations.add(manifestDirectory.resolve(Checksums.MANIFEST_NAME))) {
            throw new Failure("a file named " + Checksums.MANIFEST_NAME + " is already among the files to "
                    + "sign, and it is also the name of the manifest; rename it or sign it separately");
        }

        Gpg gpg = new Gpg();
        if (!gpg.available()) {
            throw new Failure("gpg is not on PATH; install GnuPG first");
        }
        // Last of the checks that can fail, and the one most likely to: resolved here so that an
        // unusable signing key is an error before any file has been touched.
        KeyInfo key = KeySelection.forSigning(new Keyring(gpg), options.value("--key"));

        List<Staged> staged = stage(gpg, key, files, out, withChecksums, manifestDirectory);
        List<Signature> produced = commit(staged);
        report(key, produced);
        return Defaults.EXIT_OK;
    }

    /** One produced artifact as the report lists it: the file, and how many bytes it is. */
    private record Signature(String kind, Path path, long bytes) {
    }

    /**
     * Produces every signature into a temporary file, touching nothing that already existed.
     *
     * @throws Failure after removing every temporary it made, so a failed run leaves the directory
     *                 exactly as it found it
     */
    private static List<Staged> stage(Gpg gpg, KeyInfo key, List<Path> files, Path out,
                                      boolean withChecksums, Path manifestDirectory) {
        List<Staged> staged = new ArrayList<>();
        try {
            for (Path file : files) {
                Path destination = destinationFor(file, out);
                Path temporary = temporaryFor(destination);
                sign(gpg, key, file.toAbsolutePath(), temporary);
                staged.add(new Staged("signature", temporary, destination));
            }
            if (withChecksums) {
                Path manifest = manifestDirectory.resolve(Checksums.MANIFEST_NAME);
                Path manifestTemporary = temporaryFor(manifest);
                Files.writeString(manifestTemporary, manifestText(files, manifestDirectory),
                        StandardCharsets.UTF_8);
                staged.add(new Staged("checksums", manifestTemporary, manifest));

                // The manifest is signed while it is still the temporary file. Its contents do not
                // mention its own name, so the signature is over exactly the bytes that will land in
                // SHA256SUMS — and it, too, waits for the rename.
                Path manifestSignature = manifestDirectory.resolve(
                        Checksums.MANIFEST_NAME + Checksums.MANIFEST_SIGNATURE_SUFFIX);
                Path signatureTemporary = temporaryFor(manifestSignature);
                sign(gpg, key, manifestTemporary, signatureTemporary);
                staged.add(new Staged("signature", signatureTemporary, manifestSignature));
            }
        } catch (IOException failure) {
            discard(staged);
            throw new Failure("could not write the signatures: " + failure.getMessage()
                    + "; nothing was changed");
        } catch (RuntimeException failure) {
            discard(staged);
            throw failure;
        }
        return staged;
    }

    /** Renames every staged file into place, and reports what landed where. */
    private static List<Signature> commit(List<Staged> staged) {
        List<Signature> produced = new ArrayList<>(staged.size());
        for (int index = 0; index < staged.size(); index++) {
            Staged file = staged.get(index);
            try {
                Files.move(file.temporary(), file.destination(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException failure) {
                // Only a real filesystem error gets here, and it can only be reached after some of
                // the renames succeeded. Saying exactly which ones is the difference between a
                // confusing half-state and a recoverable one.
                throw new Failure("could not put " + file.destination() + " in place: " + failure.getMessage()
                        + "; already written: " + written(produced));
            }
            try {
                produced.add(new Signature(file.kind(), file.destination(), Files.size(file.destination())));
            } catch (IOException failure) {
                throw new Failure("wrote " + file.destination() + " but could not read its size: "
                        + failure.getMessage() + "; already written: " + written(produced));
            }
        }
        return produced;
    }

    private static String written(List<Signature> produced) {
        if (produced.isEmpty()) {
            return "nothing";
        }
        StringBuilder listed = new StringBuilder();
        for (Signature signature : produced) {
            if (listed.length() > 0) {
                listed.append(", ");
            }
            listed.append(signature.path());
        }
        return listed.toString();
    }

    private static void sign(Gpg gpg, KeyInfo key, Path source, Path destination) {
        Gpg.Result result = gpg.run(List.of(
                "--armor",
                "--detach-sign",
                // The full fingerprint, not the user id: with an id, gpg picks whichever key matches,
                // and a release signed by a key its author did not mean to use is a release nobody
                // can trace back.
                "--local-user", key.fingerprint(),
                "--output", destination.toAbsolutePath().toString(),
                source.toAbsolutePath().toString()));
        if (!result.ok()) {
            throw new Failure("could not sign " + source + ": " + result.reason());
        }
    }

    /**
     * A hidden file beside the destination, into which gpg writes the signature.
     *
     * <p>{@code Files.createTempFile} is not used here because it forces mode 0600 on what it
     * creates, and gpg preserves the mode of a file it overwrites. A release signature would then
     * come out readable by its owner alone — a surprise for a file whose entire purpose is to be
     * handed to other people. Creating the file with {@link Files#createFile} gives it the
     * ordinary umask-derived mode, which is what writing straight to the destination would have
     * produced.
     */
    private static Path temporaryFor(Path destination) throws IOException {
        Path absolute = destination.toAbsolutePath();
        Path temporary = absolute.resolveSibling("." + absolute.getFileName() + ".tmp");
        // Left over from a run that was killed between the two steps. It is this command's own
        // naming convention, so removing it cannot take anyone else's file.
        Files.deleteIfExists(temporary);
        Files.createFile(temporary);
        return temporary;
    }

    private static void discard(List<Staged> staged) {
        for (Staged file : staged) {
            try {
                Files.deleteIfExists(file.temporary());
            } catch (IOException ignored) {
                // On the failure path already. A message about a temporary file would replace the
                // real error with a less interesting one.
            }
        }
    }

    private static Path destinationFor(Path source, Path out) {
        String name = source.getFileName() + ".asc";
        if (out == null) {
            return source.toAbsolutePath().resolveSibling(name);
        }
        return out.toAbsolutePath().resolve(name);
    }

    /**
     * The manifest, in coreutils' format.
     *
     * <p>Names are made relative to the directory the manifest sits in, which is the directory
     * {@code sha256sum -c} would resolve them against. Written that way, a manifest produced with
     * {@code --out dist} lists {@code foo.jar} rather than {@code dist/foo.jar} — otherwise
     * verifying it would look for {@code dist/dist/foo.jar}.
     */
    private static String manifestText(List<Path> files, Path manifestDirectory) {
        Path directory = manifestDirectory.toAbsolutePath().normalize();
        StringBuilder text = new StringBuilder();
        for (Path file : files) {
            Path absolute = file.toAbsolutePath().normalize();
            String name = absolute.toString();
            if (absolute.getParent() != null && absolute.startsWith(directory)) {
                name = directory.relativize(absolute).toString();
            }
            text.append(Checksums.line(Checksums.of(absolute), name));
        }
        return text.toString();
    }

    /**
     * A produced file named the way the person who ran the command would name it.
     *
     * <p>Signatures land beside their source file, which means the path this command knows is an
     * absolute one, while the manifest it wrote next to them is relative. Printing the two forms
     * in one list makes a four-line report look like it concerns four unrelated directories.
     */
    private static String display(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path working = Path.of("").toAbsolutePath().normalize();
        if (absolute.startsWith(working) && !absolute.equals(working)) {
            return working.relativize(absolute).toString();
        }
        return absolute.toString();
    }

    private void report(KeyInfo key, List<Signature> produced) {
        if (global.json()) {
            Json json = new Json();
            json.beginObject();
            json.name("fingerprint").value(key.fingerprint());
            json.name("outputs").beginArray();
            for (Signature signature : produced) {
                json.beginObject();
                json.name("kind").value(signature.kind());
                json.name("path").value(display(signature.path()));
                json.name("bytes").value(signature.bytes());
                json.endObject();
            }
            json.endArray();
            json.endObject();
            System.out.println(json);
            return;
        }

        if (global.quiet()) {
            for (Signature signature : produced) {
                System.out.println(display(signature.path()) + "\t" + signature.bytes());
            }
            return;
        }

        System.out.println("signed with  " + key.fingerprint());
        int widest = 0;
        for (Signature signature : produced) {
            widest = Math.max(widest, display(signature.path()).length());
        }
        for (Signature signature : produced) {
            System.out.printf("%-11s%-" + widest + "s  %d bytes%n",
                    signature.kind(), display(signature.path()), signature.bytes());
        }
    }
}
