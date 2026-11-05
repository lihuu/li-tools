package io.github.lihuu.dukit.core.model;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Outcome of one completed scan: the root of the aggregated tree plus every read failure.
 */
public record ScanResult(@NotNull DirEntry root, @NotNull List<ScanError> errors) {
    public ScanResult {
        errors = List.copyOf(errors);
    }
}
