package io.github.lihuu.dukit.core.model;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;

/**
 * Outcome of one completed scan: the scanned root path, the aggregated tree below it, and
 * every read failure.
 *
 * @param path   absolute path that was scanned
 * @param root   aggregate for the root directory
 * @param errors entries that could not be read
 */
public record ScanResult(@NotNull Path path, @NotNull DirEntry root, @NotNull List<ScanError> errors) {
    public ScanResult {
        errors = List.copyOf(errors);
    }
}
