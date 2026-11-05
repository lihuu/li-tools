package io.github.lihuu.dukit.core.scan;

import org.junit.jupiter.api.Test;

import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins how {@link NioStatReader} classifies what {@code java.nio.file} reports. Windows
 * reports a directory junction (reparse tag {@code IO_REPARSE_TAG_MOUNT_POINT}) as a
 * directory and as "other", never as a symbolic link, so the classification itself has to
 * keep junctions out of the traversal. Fake attributes keep that decision testable without
 * a Windows host.
 */
class NioStatReaderTest {
    @Test
    void treatsAWindowsJunctionAsOtherInsteadOfADirectory() {
        FileStat stat = new FileStat();

        NioStatReader.fill(stat, new FakeAttributes(true, false, true, false));

        assertEquals(FileStat.Kind.OTHER, stat.kind,
                "a junction is a reparse point and must not be traversed as a directory");
    }

    @Test
    void stillTreatsAPlainDirectoryAsADirectory() {
        FileStat stat = new FileStat();

        NioStatReader.fill(stat, new FakeAttributes(true, false, false, false));

        assertEquals(FileStat.Kind.DIRECTORY, stat.kind);
    }

    private record FakeAttributes(boolean directory, boolean symbolicLink, boolean other,
            boolean regularFile) implements BasicFileAttributes {
        @Override
        public FileTime lastModifiedTime() {
            return FileTime.fromMillis(0);
        }

        @Override
        public FileTime lastAccessTime() {
            return FileTime.fromMillis(0);
        }

        @Override
        public FileTime creationTime() {
            return FileTime.fromMillis(0);
        }

        @Override
        public boolean isRegularFile() {
            return regularFile;
        }

        @Override
        public boolean isDirectory() {
            return directory;
        }

        @Override
        public boolean isSymbolicLink() {
            return symbolicLink;
        }

        @Override
        public boolean isOther() {
            return other;
        }

        @Override
        public long size() {
            return 0;
        }

        @Override
        public Object fileKey() {
            return null;
        }
    }
}
