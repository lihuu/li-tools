package io.github.lihuu.dukit.core.model;

import org.jetbrains.annotations.NotNull;

/**
 * A regular file retained for the interactive listing: its name and apparent size.
 *
 * <p>File nodes are never added to the directory tree or the JSON report; the scanner keeps
 * only a bounded preview per directory when the interactive browser asks for one.
 *
 * @param name file name, not a path
 * @param size apparent size in bytes; zero-byte files and hardlink twins are not retained
 * @param diskSize space actually occupied by the file
 */
public record FileEntry(@NotNull String name, long size, long diskSize) implements TreeEntry {
}
