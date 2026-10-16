package io.github.lihuu.gpgkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where a backup lands: the two forms SPEC §2 names, {@code file:} and {@code onedrive:}.
 *
 * <p>Parsing and checking are separate on purpose. Parsing answers "did you write a destination
 * I understand" and is a usage error (exit 2); checking answers "can I actually write there" and
 * is a failure (exit 1). Both run before a single byte of key material is exported, because the
 * one outcome that must never happen is a destination that is discovered to be unwritable after
 * the backup has already been announced as taken (SPEC §3.4).
 */
final class Destinations {
    private static final String FILE_PREFIX = "file:";
    private static final String ONEDRIVE_PREFIX = "onedrive:";

    /**
     * OneDrive's local mount. SPEC §2 spells the macOS path out, and the Windows client — which
     * mounts a drive letter instead — is a case this tool does not pretend to handle: the
     * {@code onedrive:} form is documented as the macOS location or nothing.
     */
    private static final String ONEDRIVE_SUBDIR = "Library/CloudStorage/OneDrive-Personal";

    /** A destination as it was written, next to where it resolves to. */
    record Destination(String spec, Path directory) {
    }

    private Destinations() {
    }

    static Path oneDriveRoot() {
        return Path.of(System.getProperty("user.home"), "Library", "CloudStorage", "OneDrive-Personal");
    }

    /**
     * Turns destination specs into directories, rejecting the ones that cannot mean anything.
     *
     * <p>Duplicate destinations collapse: {@code --out} is always a destination and the same path
     * may also be written as {@code --dest file:}, and writing the ciphertext twice into one
     * directory would report two copies where there is one.
     */
    static List<Destination> parse(List<String> specs) {
        Map<Path, Destination> resolved = new LinkedHashMap<>();
        for (String spec : specs) {
            Destination destination = parseOne(spec);
            resolved.putIfAbsent(destination.directory(), destination);
        }
        return List.copyOf(resolved.values());
    }

    /**
     * Confirms every destination can be written, without writing to any of them yet.
     *
     * <p>A directory that is not there yet is not a failure — {@code backup} creates it — so the
     * check walks up to the nearest directory that does exist and asks about that one. That is
     * the difference between "you pointed this at a path I cannot create" being reported before
     * the export and after it.
     */
    static void check(List<Destination> destinations) {
        Path oneDriveRoot = oneDriveRoot();
        for (Destination destination : destinations) {
            if (destination.spec().startsWith(ONEDRIVE_PREFIX) && !Files.isDirectory(oneDriveRoot)) {
                throw new Failure("OneDrive is not where it should be: " + oneDriveRoot + " does not exist,"
                        + " so `" + destination.spec() + "` cannot be written. Sign in to the OneDrive client,"
                        + " or use --dest file:/absolute/path instead");
            }
            checkWritable(destination);
        }
    }

    private static Destination parseOne(String spec) {
        if (spec.startsWith(FILE_PREFIX)) {
            String value = spec.substring(FILE_PREFIX.length());
            if (value.isBlank()) {
                throw new UsageError("`" + spec + "` names no path; write it as file:/absolute/path");
            }
            Path path = Path.of(value);
            if (!path.isAbsolute()) {
                // Relative would mean "relative to whatever directory this command happened to run
                // in", which for a backup written once and restored a year later is a different
                // place. The manifest records an absolute path, so the input has to be one.
                throw new UsageError("`" + spec + "` is not an absolute path; write it as file:/absolute/path");
            }
            return new Destination(spec, path);
        }
        if (spec.startsWith(ONEDRIVE_PREFIX)) {
            String value = spec.substring(ONEDRIVE_PREFIX.length());
            if (value.isBlank()) {
                throw new UsageError("`" + spec + "` names no folder; write it as onedrive:<relative path>");
            }
            Path relative = Path.of(value);
            if (relative.isAbsolute()) {
                throw new UsageError("`" + spec + "` is absolute; the onedrive: form takes a path relative to "
                        + oneDriveRoot() + ", so write it as onedrive:" + relative.getFileName());
            }
            return new Destination(spec, oneDriveRoot().resolve(relative).normalize());
        }
        throw new UsageError("`" + spec + "` is not a destination: write file:/absolute/path or onedrive:<relative path>");
    }

    private static void checkWritable(Destination destination) {
        Path existing = destination.directory();
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new Failure("nowhere to write " + destination.spec() + ": no part of that path exists");
        }
        if (!Files.isDirectory(existing)) {
            throw new Failure(destination.spec() + " cannot be written: " + existing + " is not a directory");
        }
        if (!Files.isWritable(existing)) {
            throw new Failure(destination.spec() + " cannot be written: " + existing + " is not writable by you");
        }
    }

    /** Creates the destination directories, which is the first thing this tool writes. */
    static void prepare(List<Destination> destinations) {
        for (Destination destination : destinations) {
            try {
                Files.createDirectories(destination.directory());
            } catch (IOException failure) {
                throw new Failure("could not create " + destination.directory() + ": " + failure.getMessage());
            }
        }
    }

    /** The specs as the user wrote them, for the report lines. */
    static List<String> specs(List<Destination> destinations) {
        List<String> specs = new ArrayList<>(destinations.size());
        for (Destination destination : destinations) {
            specs.add(destination.spec());
        }
        return specs;
    }
}
