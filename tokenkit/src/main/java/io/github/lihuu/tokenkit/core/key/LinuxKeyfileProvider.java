package io.github.lihuu.tokenkit.core.key;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.util.Objects;
import java.util.Set;
import io.github.lihuu.tokenkit.core.crypto.CryptoEngine;
import org.jetbrains.annotations.NotNull;

public final class LinuxKeyfileProvider implements MasterKeyProvider {
    private static final Set<PosixFilePermission> OWNER_ONLY_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");
    private final Path keyFilePath;

    public LinuxKeyfileProvider(@NotNull Path keyFilePath) {
        this.keyFilePath = Objects.requireNonNull(keyFilePath, "keyFilePath must not be null");
    }

    @Override
    public @NotNull byte[] getOrCreateMasterKey() throws GeneralSecurityException {
        try {
            if (Files.exists(keyFilePath)) {
                byte[] key = Files.readAllBytes(keyFilePath);
                if (key.length != 32) {
                    throw new GeneralSecurityException("Keyfile at " + keyFilePath + " is invalid (expected 32 bytes, got " + key.length + ")");
                }
                return key;
            }

            Path parentDir = keyFilePath.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }

            byte[] newKey = CryptoEngine.generateRandomBytes(32);
            Files.write(keyFilePath, newKey, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

            applyPosixPermissions(keyFilePath);
            return newKey;
        } catch (IOException e) {
            throw new GeneralSecurityException("Failed to access or persist keyfile at " + keyFilePath, e);
        }
    }

    private static void applyPosixPermissions(@NotNull Path path) {
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            try {
                Files.setPosixFilePermissions(path, OWNER_ONLY_PERMISSIONS);
            } catch (IOException ignored) {
                // Ignore if filesystem doesn't support setting posix permissions
            }
        }
    }
}
