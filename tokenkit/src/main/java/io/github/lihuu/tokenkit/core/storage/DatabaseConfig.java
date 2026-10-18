package io.github.lihuu.tokenkit.core.storage;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;

public final class DatabaseConfig {
    public static final String ENV_DB_PATH = "TOKENKIT_DB_PATH";
    public static final String DEFAULT_DB_FILENAME = "tokens.db";

    private DatabaseConfig() {
    }

    public static @NotNull Path resolveDatabasePath(@NotNull Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment must not be null");
        String customPath = environment.get(ENV_DB_PATH);
        if (customPath != null && !customPath.trim().isEmpty()) {
            return Path.of(customPath.trim()).toAbsolutePath().normalize();
        }

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String userHome = System.getProperty("user.home", ".");

        if (os.contains("win")) {
            String appData = environment.get("APPDATA");
            if (appData != null && !appData.trim().isEmpty()) {
                return Path.of(appData, "tokenkit", DEFAULT_DB_FILENAME);
            }
            return Path.of(userHome, "AppData", "Roaming", "tokenkit", DEFAULT_DB_FILENAME);
        }

        return Path.of(userHome, ".config", "tokenkit", DEFAULT_DB_FILENAME);
    }
}
