package io.github.lihuu.dukit.core.scan;

import io.github.lihuu.dukit.core.report.HumanBytes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.PrintWriter;
import java.nio.file.Path;

/**
 * Single-line scan progress on the given writer. Disabled printers accept updates and do
 * nothing, so callers never need to branch. The line is rewritten in place with a carriage
 * return and space-padded to erase the previous render; {@link #finish()} clears it.
 */
public final class ProgressPrinter implements ProgressListener {
    private static final int MAX_PATH_CHARS = 60;

    private final PrintWriter writer;
    private final boolean enabled;
    private int lastWidth;

    public ProgressPrinter(@NotNull PrintWriter writer, boolean enabled) {
        this.writer = writer;
        this.enabled = enabled;
    }

    @Override
    public synchronized void onUpdate(long fileCount, long bytesSeen, @NotNull Path currentDirectory) {
        if (!enabled) {
            return;
        }
        String line = fileCount + " files, " + HumanBytes.format(bytesSeen) + " · " + shorten(currentDirectory.toString());
        String padding = lastWidth > line.length() ? " ".repeat(lastWidth - line.length()) : "";
        writer.print("\r" + line + padding);
        writer.flush();
        lastWidth = line.length();
    }

    /**
     * Erases the progress line, if one was printed.
     */
    public synchronized void finish() {
        if (enabled && lastWidth > 0) {
            writer.print("\r" + " ".repeat(lastWidth) + "\r");
            writer.flush();
            lastWidth = 0;
        }
    }

    private static @NotNull String shorten(@Nullable String path) {
        if (path == null || path.length() <= MAX_PATH_CHARS) {
            return path == null ? "" : path;
        }
        return "…" + path.substring(path.length() - MAX_PATH_CHARS + 1);
    }
}
