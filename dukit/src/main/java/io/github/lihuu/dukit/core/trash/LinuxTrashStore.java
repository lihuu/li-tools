package io.github.lihuu.dukit.core.trash;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * The freedesktop.org Trash: {@code $XDG_DATA_HOME/Trash} (or {@code ~/.local/share/Trash})
 * with {@code files/} and {@code info/} subdirectories and one {@code .trashinfo} per entry.
 *
 * <p>Volume-level Trash ({@code $topdir/.Trash/$uid}) is not implemented in v1: a target on
 * another filesystem is refused with {@code CROSS_DEVICE} rather than moved by copying,
 * because a copy is not a Trash operation and a partial copy is worse than a refusal.
 */
public final class LinuxTrashStore implements TrashStore {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.US);

    private final Path home;
    private final Map<String, String> environment;
    private final Clock clock;

    public LinuxTrashStore(@NotNull Path home, @NotNull Map<String, String> environment, @NotNull Clock clock) {
        this.home = home;
        this.environment = environment;
        this.clock = clock;
    }

    @Override
    public @NotNull TrashTarget prepare(@NotNull Path target) throws TrashException {
        Path trashRoot = trashRoot();
        Path filesDirectory = trashRoot.resolve("files");
        if (!Files.isDirectory(filesDirectory)) {
            throw new TrashException(TrashException.Reason.NO_TRASH, TrashException.Outcome.NOT_STARTED,
                    "Trash directory does not exist: " + filesDirectory);
        }
        requireSameFileSystem(target, trashRoot);

        String name = uniqueName(filesDirectory, fileName(target));
        Path destination = filesDirectory.resolve(name);
        return new TrashTarget(target, destination, trashRoot, trashRoot.toString());
    }

    @Override
    public @NotNull TrashReceipt move(@NotNull TrashTarget target) throws TrashException {
        Path trashRoot = trashRoot();
        Path infoDirectory = trashRoot.resolve("info");
        Path filesDirectory = trashRoot.resolve("files");
        Path destination = target.destination();
        if (destination == null) {
            throw new TrashException(TrashException.Reason.IO_ERROR, TrashException.Outcome.NOT_STARTED,
                    "no prepared destination for " + target.source());
        }
        requireSameFileSystem(target.source(), trashRoot);

        Path info = infoDirectory.resolve(destination.getFileName() + ".trashinfo");
        writeInfo(info, target.source());
        try {
            Files.move(target.source(), destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException crossDevice) {
            deleteQuietly(info);
            throw new TrashException(TrashException.Reason.CROSS_DEVICE, TrashException.Outcome.NOT_STARTED,
                    "cannot move across filesystems without copying: " + target.source(), crossDevice);
        } catch (IOException failure) {
            deleteQuietly(info);
            throw new TrashException(reasonOf(failure), TrashException.Outcome.NOT_STARTED,
                    "cannot move to Trash: " + target.source(), failure);
        }
        return new TrashReceipt(target.source(), destination);
    }

    private void writeInfo(@NotNull Path info, @NotNull Path source) throws TrashException {
        String body = "[Trash Info]\nPath=" + encode(source) + "\nDeletionDate="
                + DATE_FORMAT.format(ZonedDateTime.now(clock)) + "\n";
        try {
            Files.writeString(info, body, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException collision) {
            throw new TrashException(TrashException.Reason.IO_ERROR, TrashException.Outcome.NOT_STARTED,
                    "Trash info already exists: " + info, collision);
        } catch (IOException failure) {
            throw new TrashException(reasonOf(failure), TrashException.Outcome.NOT_STARTED,
                    "cannot write Trash info: " + info, failure);
        }
    }

    private @NotNull Path trashRoot() {
        String xdgDataHome = environment.get("XDG_DATA_HOME");
        if (xdgDataHome != null && !xdgDataHome.isBlank()) {
            return Path.of(xdgDataHome).resolve("Trash");
        }
        return home.resolve(".local").resolve("share").resolve("Trash");
    }

    private static void requireSameFileSystem(@NotNull Path target, @NotNull Path trashRoot) throws TrashException {
        try {
            Object targetStore = Files.getFileStore(target);
            Object trashStore = Files.getFileStore(trashRoot);
            if (!targetStore.equals(trashStore)) {
                throw new TrashException(TrashException.Reason.CROSS_DEVICE, TrashException.Outcome.NOT_STARTED,
                        "target is on a different filesystem than the Trash: " + target);
            }
        } catch (IOException failure) {
            throw new TrashException(TrashException.Reason.IO_ERROR, TrashException.Outcome.NOT_STARTED,
                    "cannot compare filesystems for " + target, failure);
        }
    }

    private static @NotNull String fileName(@NotNull Path target) {
        Path name = target.getFileName();
        return name == null ? "item" : name.toString();
    }

    /**
     * Picks a name that is free in both {@code files/} and {@code info/}, following the
     * spec's {@code name.2}, {@code name.3} convention.
     */
    private static @NotNull String uniqueName(@NotNull Path filesDirectory, @NotNull String base) throws TrashException {
        Path infoDirectory = filesDirectory.getParent().resolve("info");
        String candidate = base;
        for (int suffix = 2; suffix < 10_000; suffix++) {
            boolean fileFree = !Files.exists(filesDirectory.resolve(candidate));
            boolean infoFree = !Files.exists(infoDirectory.resolve(candidate + ".trashinfo"));
            if (fileFree && infoFree) {
                return candidate;
            }
            candidate = base + "." + suffix;
        }
        throw new TrashException(TrashException.Reason.IO_ERROR, TrashException.Outcome.NOT_STARTED,
                "cannot find a free name in the Trash for " + base);
    }

    /**
     * Percent-encodes a path the way the spec expects: everything outside unreserved
     * characters and {@code /} is escaped as UTF-8 bytes.
     */
    static @NotNull String encode(@NotNull Path path) {
        byte[] bytes = path.toString().getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length * 3);
        for (byte value : bytes) {
            int code = value & 0xFF;
            boolean unreserved = (code >= 'A' && code <= 'Z') || (code >= 'a' && code <= 'z')
                    || (code >= '0' && code <= '9') || code == '-' || code == '.' || code == '_'
                    || code == '~' || code == '/';
            if (unreserved) {
                encoded.append((char) code);
            } else {
                encoded.append('%').append(Character.toUpperCase(Character.forDigit(code >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(code & 0xF, 16)));
            }
        }
        return encoded.toString();
    }

    private static @NotNull TrashException.Reason reasonOf(@NotNull IOException failure) {
        if (failure instanceof java.nio.file.AccessDeniedException) {
            return TrashException.Reason.PERMISSION_DENIED;
        }
        if (failure instanceof java.nio.file.NoSuchFileException) {
            return TrashException.Reason.NOT_FOUND;
        }
        return TrashException.Reason.IO_ERROR;
    }

    private static void deleteQuietly(@Nullable Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The original failure is what matters; a leftover info file is inert.
        }
    }
}
