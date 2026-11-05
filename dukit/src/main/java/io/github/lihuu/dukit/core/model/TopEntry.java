package io.github.lihuu.dukit.core.model;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/**
 * One entry in a top-N result, carrying the full path so an agent can act on it directly.
 */
public record TopEntry(@NotNull Path path, long size) {
}
