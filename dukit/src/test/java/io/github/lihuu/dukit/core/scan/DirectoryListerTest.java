package io.github.lihuu.dukit.core.scan;

import io.github.lihuu.dukit.core.model.FileEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DirectoryListerTest {
    @TempDir Path temporary;

    private Path base;

    @BeforeEach
    void buildDirectory() throws IOException {
        base = temporary.resolve("dir");
        Files.createDirectories(base.resolve("nested"));
        Files.writeString(base.resolve("small.bin"), "x".repeat(10), StandardCharsets.US_ASCII);
        Files.writeString(base.resolve("large.bin"), "x".repeat(300), StandardCharsets.US_ASCII);
        Files.writeString(base.resolve("medium.bin"), "x".repeat(100), StandardCharsets.US_ASCII);
        Files.writeString(base.resolve(".hidden.bin"), "x".repeat(50), StandardCharsets.US_ASCII);
    }

    @Test
    void listsFilesLargestFirstAndSkipsDirectories() {
        DirectoryLister.Listing listing = DirectoryLister.list(base, base);

        assertEquals(List.of("large.bin", "medium.bin", ".hidden.bin", "small.bin"),
                listing.files().stream().map(FileEntry::name).toList());
        assertFalse(listing.truncated());
        assertEquals(4, listing.total());
    }

    @Test
    void sizesMatchTheFilesystem() throws IOException {
        DirectoryLister.Listing listing = DirectoryLister.list(base, base);

        FileEntry large = listing.files().get(0);
        assertEquals(Files.size(base.resolve("large.bin")), large.size());
        assertTrue(large.diskSize() > 0, "an occupied file reports allocated blocks");
    }

    @Test
    void filesAreNotNodesOfTheScanTreeButAreListedHere() {
        DirectoryLister.Listing listing = DirectoryLister.list(base, base);

        assertTrue(listing.files().stream().noneMatch(file -> file.name().equals("nested")));
    }

    @Test
    void keepsTheLargestWhenTheLimitIsSmallerThanTheDirectory() {
        DirectoryLister.Listing listing = DirectoryLister.list(base, base, Exclusions.none(), 2);

        assertEquals(List.of("large.bin", "medium.bin"),
                listing.files().stream().map(FileEntry::name).toList());
        assertTrue(listing.truncated(), "the caller must be able to say what it left out");
        assertEquals(4, listing.total());
    }

    @Test
    void honoursTheScansExclusions() {
        Exclusions exclusions = Exclusions.compile(List.of("large.bin", "*.hidden.bin"));
        DirectoryLister.Listing listing = DirectoryLister.list(base, base, exclusions, DirectoryLister.LIMIT);

        assertEquals(List.of("medium.bin", "small.bin"),
                listing.files().stream().map(FileEntry::name).toList());
        assertEquals(2, listing.total(), "excluded files do not count toward the total either");
    }

    @Test
    void relativeExcludePatternsAreAnchoredAtTheScanRoot() throws IOException {
        Path deeper = base.resolve("nested");
        Files.writeString(deeper.resolve("kept.bin"), "x".repeat(20), StandardCharsets.US_ASCII);
        Exclusions exclusions = Exclusions.compile(List.of("nested/*.bin"));

        DirectoryLister.Listing listing = DirectoryLister.list(deeper, base, exclusions, DirectoryLister.LIMIT);

        assertTrue(listing.files().isEmpty(), "the relative pattern matches below the root");
        assertEquals(0, listing.total());
    }

    @Test
    void zeroByteFilesAreStillRealEntries() throws IOException {
        Files.createFile(base.resolve("empty.bin"));

        DirectoryLister.Listing listing = DirectoryLister.list(base, base);

        assertEquals("empty.bin", listing.files().get(listing.files().size() - 1).name());
    }

    @Test
    void aDirectoryThatNoLongerExistsListsNothing() throws IOException {
        Path vanished = base.resolve("gone");
        Files.createDirectories(vanished);
        Files.delete(vanished);

        DirectoryLister.Listing listing = DirectoryLister.list(vanished, base);

        assertTrue(listing.files().isEmpty());
        assertEquals(0, listing.total());
    }
}
