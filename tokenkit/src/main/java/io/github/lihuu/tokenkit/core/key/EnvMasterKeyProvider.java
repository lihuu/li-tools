package io.github.lihuu.tokenkit.core.key;

import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;

public final class EnvMasterKeyProvider implements MasterKeyProvider {
    public static final String ENV_VAR_NAME = "TOKENKIT_MASTER_KEY";
    private final Map<String, String> environment;

    public EnvMasterKeyProvider(@NotNull Map<String, String> environment) {
        this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment must not be null"));
    }

    public boolean isAvailable() {
        String key = environment.get(ENV_VAR_NAME);
        return key != null && !key.trim().isEmpty();
    }

    @Override
    public @NotNull byte[] getOrCreateMasterKey() throws GeneralSecurityException {
        String rawKey = environment.get(ENV_VAR_NAME);
        if (rawKey == null || rawKey.trim().isEmpty()) {
            throw new GeneralSecurityException("Environment variable " + ENV_VAR_NAME + " is not set");
        }
        String cleanKey = rawKey.trim();
        try {
            byte[] decoded = HexFormat.of().parseHex(cleanKey);
            if (decoded.length != 32) {
                throw new GeneralSecurityException(ENV_VAR_NAME + " must be a 32-byte (64-character hex) key, got " + decoded.length + " bytes");
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("Invalid hex format in " + ENV_VAR_NAME, e);
        }
    }
}
