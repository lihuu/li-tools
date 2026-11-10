package io.github.lihuu.mitmkit.core;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Resolves the local CA directory from the environment or the user home. */
public final class CaDefaults {
    private CaDefaults() {}

    public static @NotNull Path caDir(@NotNull Map<String, String> environment) {
        String env = environment.get("MITMKIT_CA_DIR");
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }
        return Path.of(System.getProperty("user.home"), ".mitmkit", "ca");
    }

    public static boolean hasCa(@NotNull Path caDir) {
        return Files.exists(caDir.resolve("ca.crt")) && Files.exists(caDir.resolve("ca.key"));
    }
}
