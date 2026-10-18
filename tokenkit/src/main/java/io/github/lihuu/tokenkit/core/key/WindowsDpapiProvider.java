package io.github.lihuu.tokenkit.core.key;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.util.Objects;
import io.github.lihuu.tokenkit.core.crypto.CryptoEngine;
import org.jetbrains.annotations.NotNull;

public final class WindowsDpapiProvider implements MasterKeyProvider {
    private final Path keyFilePath;

    public WindowsDpapiProvider(@NotNull Path keyFilePath) {
        this.keyFilePath = Objects.requireNonNull(keyFilePath, "keyFilePath must not be null");
    }

    @Override
    public byte @NotNull [] getOrCreateMasterKey() throws GeneralSecurityException {
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
            return newKey;
        } catch (IOException e) {
            throw new GeneralSecurityException("Failed to access or persist keyfile on Windows at " + keyFilePath, e);
        }
    }
}
