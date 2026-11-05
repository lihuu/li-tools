package io.github.lihuu.dukit.core.model;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/**
 * One filesystem entry that could not be read during a scan. The scan continues past it.
 */
public record ScanError(@NotNull Path path, @NotNull String message) {
}
