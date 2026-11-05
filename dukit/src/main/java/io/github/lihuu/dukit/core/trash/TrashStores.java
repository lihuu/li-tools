package io.github.lihuu.dukit.core.trash;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

/**
 * Picks the Trash implementation for the running platform.
 *
 * <p>Platform selection lives here, not in the delete core: everything above this class
 * works against the {@link TrashStore} interface, which is also what tests replace.
 */
public final class TrashStores {
    private TrashStores() {}

    public static @NotNull TrashStore forCurrentPlatform(@NotNull Path home,
            @NotNull Map<String, String> environment, @NotNull Clock clock) {
        String os = System.getProperty("os.name", "");
        if (os.startsWith("Mac")) {
            return new MacOsTrashStore(home);
        }
        if (os.startsWith("Linux")) {
            return new LinuxTrashStore(home, environment, clock);
        }
        if (os.startsWith("Windows")) {
            return new WindowsTrashStore();
        }
        return new UnsupportedTrashStore();
    }
}
