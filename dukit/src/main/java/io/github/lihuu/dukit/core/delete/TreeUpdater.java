package io.github.lihuu.dukit.core.delete;

import io.github.lihuu.dukit.core.model.DirEntry;
import io.github.lihuu.dukit.core.model.ScanError;
import io.github.lihuu.dukit.core.model.ScanResult;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Removes a deleted subtree from an in-memory scan without rescanning.
 *
 * <p>Pure: it takes a {@link ScanResult} and an absolute path and returns a new one. Every
 * ancestor is reduced by the <em>difference</em> between its old child and the rebuilt one,
 * which is what keeps totals consistent for a delete several levels down — subtracting the
 * whole child would over-count whenever the removed node is not a direct child of the root.
 */
public final class TreeUpdater {
    private TreeUpdater() {}

    public static @NotNull ScanResult remove(@NotNull ScanResult scan, @NotNull Path target) throws DeleteException {
        Path root = scan.path();
        Path absolute = target.toAbsolutePath().normalize();
        if (absolute.equals(root)) {
            throw new DeleteException(DeleteException.Reason.ROOT, "cannot remove the scan root: " + root);
        }
        if (!absolute.startsWith(root)) {
            throw new DeleteException(DeleteException.Reason.NOT_IN_TREE,
                    "not inside the scanned tree: " + absolute);
        }
        List<String> segments = new ArrayList<>();
        for (Path segment : root.relativize(absolute)) {
            segments.add(segment.toString());
        }

        DirEntry updated = removeFrom(scan.root(), segments, 0);
        return new ScanResult(root, updated, errorsOutside(scan.errors(), absolute));
    }

    private static @NotNull DirEntry removeFrom(@NotNull DirEntry entry, @NotNull List<String> segments, int index)
            throws DeleteException {
        String wanted = segments.get(index);
        List<DirEntry> children = new ArrayList<>(entry.children().size());
        boolean found = false;
        long removedSize = 0;
        long removedDiskSize = 0;
        long removedFiles = 0;
        long removedDirectories = 0;
        long removedErrors = 0;

        for (DirEntry child : entry.children()) {
            if (!found && child.name().equals(wanted)) {
                found = true;
                DirEntry rebuilt = index + 1 == segments.size() ? null : removeFrom(child, segments, index + 1);
                if (rebuilt == null) {
                    removedSize = child.size();
                    removedDiskSize = child.diskSize();
                    removedFiles = child.fileCount();
                    removedDirectories = 1 + child.dirCount();
                    removedErrors = child.errorCount();
                } else {
                    removedSize = child.size() - rebuilt.size();
                    removedDiskSize = child.diskSize() - rebuilt.diskSize();
                    removedFiles = child.fileCount() - rebuilt.fileCount();
                    removedDirectories = child.dirCount() - rebuilt.dirCount();
                    removedErrors = child.errorCount() - rebuilt.errorCount();
                    children.add(rebuilt);
                }
                continue;
            }
            children.add(child);
        }

        if (!found) {
            throw new DeleteException(DeleteException.Reason.NOT_FOUND,
                    "no such node in the scanned tree: " + String.join("/", segments));
        }
        return new DirEntry(
                entry.name(),
                entry.diskSize() - removedDiskSize,
                entry.size() - removedSize,
                entry.fileCount() - removedFiles,
                entry.dirCount() - removedDirectories,
                entry.errorCount() - removedErrors,
                entry.ownFileSize(),
                entry.ownFileCount(),
                entry.topFiles(),
                children);
    }

    private static @NotNull List<ScanError> errorsOutside(@NotNull List<ScanError> errors, @NotNull Path target) {
        List<ScanError> kept = new ArrayList<>(errors.size());
        for (ScanError error : errors) {
            if (!error.path().startsWith(target)) {
                kept.add(error);
            }
        }
        return List.copyOf(kept);
    }
}
