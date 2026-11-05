package io.github.lihuu.dukit.core.scan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins the {@code struct stat} layouts {@link NativeStatReader} relies on: the JDK sees the
 * same sizes, and two hardlinks to one inode report the same device and inode. Windows has
 * no pinned layout and skips.
 */
class NativeStatReaderTest {
    @TempDir Path temporary;

    @Test
    void readsTheSameAttributesAsTheJdkAndRecognisesSharedInodes() throws IOException {
        NativeStatReader reader = NativeStatReader.forCurrentPlatform();
        assumeTrue(reader != null, "no pinned struct stat layout for this platform");

        Path original = Files.writeString(temporary.resolve("original.bin"), "x".repeat(123), StandardCharsets.US_ASCII);
        Path link = Files.createLink(temporary.resolve("link.bin"), original);
        Path other = Files.writeString(temporary.resolve("other.bin"), "y".repeat(456), StandardCharsets.US_ASCII);
        Path directory = Files.createDirectory(temporary.resolve("directory"));
        Path symbolic = Files.createSymbolicLink(temporary.resolve("symbolic"), original);

        FileStat originalStat = new FileStat();
        FileStat linkStat = new FileStat();
        FileStat otherStat = new FileStat();
        FileStat directoryStat = new FileStat();
        FileStat symbolicStat = new FileStat();
        try (StatReader.Cursor cursor = reader.open()) {
            cursor.read(original, originalStat);
            cursor.read(link, linkStat);
            cursor.read(other, otherStat);
            cursor.read(directory, directoryStat);
            cursor.read(symbolic, symbolicStat);
        }

        BasicFileAttributes jdk = Files.readAttributes(original, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        assertEquals(FileStat.Kind.REGULAR_FILE, originalStat.kind);
        assertEquals(jdk.size(), originalStat.size);
        assertEquals(2, originalStat.linkCount, "the file and its hardlink");
        assertEquals(originalStat.device, linkStat.device);
        assertEquals(originalStat.inode, linkStat.inode);
        assertNotEquals(originalStat.inode, otherStat.inode);
        assertEquals(456, otherStat.size);
        assertEquals(FileStat.Kind.DIRECTORY, directoryStat.kind);
        assertEquals(FileStat.Kind.SYMBOLIC_LINK, symbolicStat.kind);
    }
}
