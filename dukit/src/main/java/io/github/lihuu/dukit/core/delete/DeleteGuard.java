package io.github.lihuu.dukit.core.delete;

import io.github.lihuu.dukit.core.trash.TrashStore;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Refuses a delete target before anything is touched.
 *
 * <p>The checks are ordered cheapest-first and every one of them is a reason to stop: this
 * class has no "best effort" mode. The target is re-validated at execute time as well, so a
 * successful plan is never treated as a permission slip.
 */
public final class DeleteGuard {
    private DeleteGuard() {}

    /**
     * @param scanRoot the tree the user is looking at
     * @param target   absolute path of the directory they selected
     */
    public static void validate(@NotNull Path scanRoot, @NotNull Path target) throws DeleteException {
        Path absolute = target.toAbsolutePath().normalize();
        if (absolute.equals(scanRoot)) {
            throw new DeleteException(DeleteException.Reason.ROOT, "the scan root cannot be deleted");
        }
        if (!absolute.startsWith(scanRoot)) {
            throw new DeleteException(DeleteException.Reason.NOT_IN_TREE,
                    "target is outside the scanned tree: " + absolute);
        }
        BasicFileAttributes attributes = attributesOf(absolute);
        if (attributes.isSymbolicLink()) {
            throw new DeleteException(DeleteException.Reason.SYMBOLIC_LINK,
                    "refusing to delete a symbolic link: " + absolute);
        }
        if (attributes.isOther()) {
            throw new DeleteException(DeleteException.Reason.REPARSE_POINT,
                    "refusing to delete a reparse point: " + absolute);
        }
        if (!attributes.isDirectory()) {
            throw new DeleteException(DeleteException.Reason.NOT_A_DIRECTORY,
                    "only directories can be deleted: " + absolute);
        }
        requireNotMountPoint(absolute);
        requireNoLinkAncestor(scanRoot, absolute);
    }

    /**
     * The destination must not be the target or live inside it: moving a directory into a
     * Trash that sits under it would consume the directory itself.
     */
    public static void validateTrashRelation(@NotNull Path target, @NotNull TrashStore.TrashTarget trash)
            throws DeleteException {
        Path absoluteTarget = target.toAbsolutePath().normalize();
        Path trashRoot = trash.trashRoot().toAbsolutePath().normalize();
        if (absoluteTarget.equals(trashRoot) || absoluteTarget.startsWith(trashRoot)) {
            throw new DeleteException(DeleteException.Reason.IS_TRASH,
                    "target is inside the Trash: " + absoluteTarget);
        }
        if (trashRoot.startsWith(absoluteTarget)) {
            throw new DeleteException(DeleteException.Reason.CONTAINS_TRASH,
                    "the Trash lives inside the target: " + trashRoot);
        }
    }

    /**
     * A directory on a different filesystem from its parent is a mount point. Moving it
     * would move the mount, not the data.
     */
    private static void requireNotMountPoint(@NotNull Path target) throws DeleteException {
        Path parent = target.getParent();
        if (parent == null) {
            throw new DeleteException(DeleteException.Reason.ROOT, "target has no parent: " + target);
        }
        try {
            if (!Files.getFileStore(target).equals(Files.getFileStore(parent))) {
                throw new DeleteException(DeleteException.Reason.MOUNT_POINT,
                        "refusing to delete a mount point: " + target);
            }
        } catch (IOException failure) {
            throw new DeleteException(DeleteException.Reason.UNREADABLE,
                    "cannot compare filesystems for " + target, failure);
        }
    }

    /**
     * Every level from the scan root down to the parent must be a real directory, not a
     * symlink that could have been swapped in after the scan.
     */
    private static void requireNoLinkAncestor(@NotNull Path scanRoot, @NotNull Path target) throws DeleteException {
        Path current = target.getParent();
        while (current != null && !current.equals(scanRoot)) {
            BasicFileAttributes attributes = attributesOf(current);
            if (attributes.isSymbolicLink()) {
                throw new DeleteException(DeleteException.Reason.SYMBOLIC_LINK,
                        "an ancestor is a symbolic link: " + current);
            }
            if (attributes.isOther()) {
                throw new DeleteException(DeleteException.Reason.REPARSE_POINT,
                        "an ancestor is a reparse point: " + current);
            }
            current = current.getParent();
        }
        if (current == null) {
            throw new DeleteException(DeleteException.Reason.NOT_IN_TREE,
                    "target is not below the scan root: " + target);
        }
    }

    private static @NotNull BasicFileAttributes attributesOf(@NotNull Path path) throws DeleteException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            throw new DeleteException(DeleteException.Reason.NOT_FOUND,
                    "target no longer exists: " + path, missing);
        } catch (AccessDeniedException denied) {
            throw new DeleteException(DeleteException.Reason.UNREADABLE,
                    "cannot inspect target: " + path, denied);
        } catch (IOException failure) {
            throw new DeleteException(DeleteException.Reason.UNREADABLE,
                    "cannot inspect target: " + path, failure);
        }
    }

    /**
     * @return a human-readable reason, or {@code null} when the path is a valid target
     */
    public static @Nullable String explain(@NotNull Path scanRoot, @NotNull Path target) {
        try {
            validate(scanRoot, target);
            return null;
        } catch (DeleteException refusal) {
            return refusal.getMessage();
        }
    }
}
