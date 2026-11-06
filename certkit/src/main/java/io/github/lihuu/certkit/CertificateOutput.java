package io.github.lihuu.certkit;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

/** Stages a verified certificate and its key before publishing either to the destination. */
final class CertificateOutput implements AutoCloseable {
    private final Path directory;
    private final Path staging;
    private final List<String> names;
    private final boolean force;
    private boolean keepForRecovery;

    CertificateOutput(@NotNull Path directory, @NotNull List<String> names, boolean force) throws IOException {
        this.directory = directory;
        this.names = names;
        this.force = force;
        checkDestinations();
        Files.createDirectories(directory);
        staging = directory.resolve(".certkit-pending");
        try {
            if (Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null) {
                Files.createDirectory(staging, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } else {
                Files.createDirectory(staging);
            }
        } catch (FileAlreadyExistsException failure) {
            throw new IOException("Generation already active or awaiting recovery: " + staging, failure);
        }
    }

    @NotNull Path staged(@NotNull String name) {
        return staging.resolve(name);
    }

    private void checkDestinations() throws IOException {
        for (String name : names) {
            Path target = directory.resolve(name);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (!force) throw new IOException("Refusing to replace " + target + "; use --force to replace existing files");
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Refusing to replace a non-regular file: " + target);
                }
            }
        }
    }

    void publish() throws IOException {
        checkDestinations();
        List<String> backedUp = new ArrayList<>();
        List<Path> reserved = new ArrayList<>();
        try {
            for (String name : names) {
                Path target = directory.resolve(name);
                if (force && Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    Files.move(target, staged(".previous-" + name));
                    backedUp.add(name);
                }
                Files.createFile(target);
                reserved.add(target);
            }
            for (String name : names) {
                Files.move(staged(name), directory.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            try {
                for (Path target : reserved) Files.deleteIfExists(target);
                for (String name : backedUp) Files.move(staged(".previous-" + name), directory.resolve(name));
            } catch (IOException recoveryFailure) {
                keepForRecovery = true;
                failure.addSuppressed(recoveryFailure);
                throw new IOException("Could not publish or restore all certificate files; recovery files: " + staging, failure);
            }
            throw failure;
        }
    }

    @Override
    public void close() throws IOException {
        if (keepForRecovery) return;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(staging)) {
            for (Path file : files) Files.delete(file);
        }
        Files.delete(staging);
    }
}
