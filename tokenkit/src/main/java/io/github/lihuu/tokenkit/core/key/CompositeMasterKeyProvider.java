package io.github.lihuu.tokenkit.core.key;

import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;

public final class CompositeMasterKeyProvider implements MasterKeyProvider {
    private final Map<String, String> environment;
    private final String osName;
    private final Path configDir;
    private volatile byte[] cachedKey;

    public CompositeMasterKeyProvider(@NotNull Map<String, String> environment, @NotNull String osName, @NotNull Path configDir) {
        this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment must not be null"));
        this.osName = Objects.requireNonNull(osName, "osName must not be null");
        this.configDir = Objects.requireNonNull(configDir, "configDir must not be null");
    }

    public static @NotNull CompositeMasterKeyProvider defaultProvider(@NotNull Path configDir) {
        return new CompositeMasterKeyProvider(
                System.getenv(),
                System.getProperty("os.name", ""),
                configDir
        );
    }

    @Override
    public @NotNull byte[] getOrCreateMasterKey() throws GeneralSecurityException {
        byte[] cached = cachedKey;
        if (cached != null) {
            return cached.clone();
        }
        synchronized (this) {
            if (cachedKey != null) {
                return cachedKey.clone();
            }
            byte[] resolved = resolveMasterKey();
            cachedKey = resolved.clone();
            return resolved;
        }
    }

    private byte[] resolveMasterKey() throws GeneralSecurityException {
        // Priority 1: Environment variable
        EnvMasterKeyProvider envProvider = new EnvMasterKeyProvider(environment);
        if (envProvider.isAvailable()) {
            return envProvider.getOrCreateMasterKey();
        }

        // Priority 2: OS Native with fallback
        String lowerOs = osName.toLowerCase(Locale.ROOT);
        Path defaultKeyFile = configDir.resolve(".master.key");

        if (lowerOs.contains("mac") || lowerOs.contains("darwin")) {
            try {
                return new MacKeychainKeyProvider().getOrCreateMasterKey();
            } catch (Exception e) {
                // Headless or permission denied in Keychain, fallback to secure local keyfile
                return new LinuxKeyfileProvider(defaultKeyFile).getOrCreateMasterKey();
            }
        }

        if (lowerOs.contains("win")) {
            return new WindowsDpapiProvider(defaultKeyFile).getOrCreateMasterKey();
        }

        // Linux and default
        return new LinuxKeyfileProvider(defaultKeyFile).getOrCreateMasterKey();
    }
}
