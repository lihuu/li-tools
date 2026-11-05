package io.github.lihuu.dukit.core.scan;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Attribute access through {@code java.nio.file}. Works everywhere, but the JDK exposes
 * neither a link count nor a portable file-key type, so hardlinks are recognised by key
 * identity and every regular file's key has to be remembered. Used only where
 * {@link NativeStatReader} cannot run.
 */
final class NioStatReader implements StatReader {
    static final NioStatReader INSTANCE = new NioStatReader();

    private NioStatReader() {}

    @Override
    public @NotNull Cursor open() {
        return new Cursor() {
            @Override
            public void read(@NotNull Path path, @NotNull FileStat stat) throws IOException {
                BasicFileAttributes attributes = Files.readAttributes(
                        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                fill(stat, attributes);
            }

            @Override
            public void close() {
            }
        };
    }

    static void fill(@NotNull FileStat stat, @NotNull BasicFileAttributes attributes) {
        stat.kind = kindOf(attributes);
        stat.size = attributes.size();
        stat.blocks = -1;
        stat.linkCount = -1;
        stat.device = 0;
        stat.inode = 0;
        stat.fileKey = attributes.fileKey();
    }

    private static @NotNull FileStat.Kind kindOf(@NotNull BasicFileAttributes attributes) {
        // Windows reports a junction (IO_REPARSE_TAG_MOUNT_POINT) as a directory and as
        // "other", never as a symbolic link. Classifying it as OTHER keeps the walk from
        // following it out of the scan root; on Unix a directory is never "other".
        if (attributes.isSymbolicLink()) {
            return FileStat.Kind.SYMBOLIC_LINK;
        }
        if (attributes.isOther()) {
            return FileStat.Kind.OTHER;
        }
        if (attributes.isDirectory()) {
            return FileStat.Kind.DIRECTORY;
        }
        if (attributes.isRegularFile()) {
            return FileStat.Kind.REGULAR_FILE;
        }
        return FileStat.Kind.OTHER;
    }
}
