package io.github.lihuu.dukit.core.trash;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * macOS Trash: {@code ~/.Trash} for the home volume and {@code /Volumes/<vol>/.Trashes/<uid>}
 * for external volumes.
 *
 * <p>No metadata file is written: Finder records the original location itself, and writing a
 * foreign format would only confuse it. A target on a volume without a usable
 * {@code .Trashes} directory is refused rather than copied.
 */
public final class MacOsTrashStore implements TrashStore {
    private final Path home;

    public MacOsTrashStore(@NotNull Path home) {
        this.home = home;
    }

    @Override
    public @NotNull TrashTarget prepare(@NotNull Path target) throws TrashException {
        Path trashRoot = trashRootFor(target);
        if (!Files.isDirectory(trashRoot)) {
            throw new TrashException(TrashException.Reason.NO_TRASH, TrashException.Outcome.NOT_STARTED,
                    "Trash directory does not exist: " + trashRoot);
        }
        String name = uniqueName(trashRoot, fileName(target));
        Path destination = trashRoot.resolve(name);
        return new TrashTarget(target, destination, trashRoot, trashRoot.toString());
    }

    @Override
    public @NotNull TrashReceipt move(@NotNull TrashTarget target) throws TrashException {
        Path destination = target.destination();
        if (destination == null) {
            throw new TrashException(TrashException.Reason.IO_ERROR, TrashException.Outcome.NOT_STARTED,
                    "no prepared destination for " + target.source());
        }
        try {
            Files.move(target.source(), destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException crossDevice) {
            throw new TrashException(TrashException.Reason.CROSS_DEVICE, TrashException.Outcome.NOT_STARTED,
                    "cannot move across filesystems without copying: " + target.source(), crossDevice);
        } catch (IOException failure) {
            throw new TrashException(reasonOf(failure), TrashException.Outcome.NOT_STARTED,
                    "cannot move to Trash: " + target.source(), failure);
        }
        return new TrashReceipt(target.source(), destination);
    }

    private @NotNull Path trashRootFor(@NotNull Path target) throws TrashException {
        Path homeTrash = home.resolve(".Trash");
        try {
            if (Files.getFileStore(target).equals(Files.getFileStore(home))) {
                return homeTrash;
            }
        } catch (IOException failure) {
            throw new TrashException(TrashException.Reason.IO_ERROR, TrashException.Outcome.NOT_STARTED,
                    "cannot compare filesystems for " + target, failure);
        }
        Path volumeRoot = volumeRootOf(target);
        Path perVolume = volumeRoot.resolve(".Trashes").resolve(String.valueOf(uid()));
        if (Files.isDirectory(perVolume)) {
            return perVolume;
        }
        throw new TrashException(TrashException.Reason.NO_TRASH, TrashException.Outcome.NOT_STARTED,
                "volume has no usable .Trashes directory: " + volumeRoot);
    }

    /**
     * Walks up to the mount point of the volume holding {@code target}: a directory whose
     * parent is {@code /Volumes} or the filesystem root.
     */
    private static @NotNull Path volumeRootOf(@NotNull Path target) {
        Path volumes = Path.of("/Volumes");
        Path current = target.toAbsolutePath().normalize();
        while (current.getParent() != null && !current.getParent().equals(volumes)
                && !current.getParent().equals(current.getRoot())) {
            current = current.getParent();
        }
        return current;
    }

    private long uid() throws TrashException {
        try {
            Object value = Files.getAttribute(home, "unix:uid");
            return value instanceof Number number ? number.longValue() : 501L;
        } catch (IOException | UnsupportedOperationException failure) {
            throw new TrashException(TrashException.Reason.IO_ERROR, TrashException.Outcome.NOT_STARTED,
                    "cannot determine the current uid from " + home, failure);
        }
    }

    private static @NotNull String fileName(@NotNull Path target) {
        Path name = target.getFileName();
        return name == null ? "item" : name.toString();
    }

    /**
     * Finder's convention for a taken name is {@code name 2}, {@code name 3}, and so on.
     */
    private static @NotNull String uniqueName(@NotNull Path trashRoot, @NotNull String base) throws TrashException {
        String candidate = base;
        for (int suffix = 2; suffix < 10_000; suffix++) {
            if (!Files.exists(trashRoot.resolve(candidate))) {
                return candidate;
            }
            candidate = base + " " + suffix;
        }
        throw new TrashException(TrashException.Reason.IO_ERROR, TrashException.Outcome.NOT_STARTED,
                "cannot find a free name in the Trash for " + base);
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

    static @Nullable Path existingDestination(@NotNull TrashTarget target) {
        return target.destination() != null && Files.exists(target.destination()) ? target.destination() : null;
    }
}
