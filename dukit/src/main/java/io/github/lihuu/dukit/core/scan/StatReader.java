package io.github.lihuu.dukit.core.scan;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Reads the attributes a scan needs: entry kind, logical size, and — on platforms that
 * expose it — the link count and inode identity used to count hardlinked content once.
 *
 * <p>A cursor owns whatever native scratch memory its implementation needs. It must be
 * opened, used and closed on one thread; {@link DirectoryScanner} does that around the
 * directory listing so the memory lives no longer than that listing.
 */
interface StatReader {
    static @NotNull StatReader create() {
        NativeStatReader nativeReader = NativeStatReader.forCurrentPlatform();
        return nativeReader != null ? nativeReader : NioStatReader.INSTANCE;
    }

    @NotNull Cursor open();

    interface Cursor extends AutoCloseable {
        void read(@NotNull Path path, @NotNull FileStat stat) throws IOException;

        @Override
        void close();
    }
}
