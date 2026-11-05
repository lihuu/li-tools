package io.github.lihuu.dukit.core.scan;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/**
 * Receives periodic progress updates while a scan is running. Implementations throttle
 * rendering themselves; the scanner only calls this every so often.
 */
@FunctionalInterface
public interface ProgressListener {
    void onUpdate(long fileCount, long bytesSeen, @NotNull Path currentDirectory);
}
