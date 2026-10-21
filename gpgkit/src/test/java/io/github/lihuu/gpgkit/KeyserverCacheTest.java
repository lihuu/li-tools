package io.github.lihuu.gpgkit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyserverCacheTest {

    @Test
    void emptyCacheReturnsEmpty(@TempDir Path tempDir) {
        Path cacheFile = tempDir.resolve("keyserver-cache");
        KeyserverCache cache = new KeyserverCache(cacheFile);

        Optional<Keyserver.Probe> probe = cache.get("5DFB74250A80C001F7305EF8F37E23C14F7495EE");

        assertTrue(probe.isEmpty());
    }

    @Test
    void putAndGetVisibleProbe(@TempDir Path tempDir) {
        Path cacheFile = tempDir.resolve("keyserver-cache");
        KeyserverCache cache = new KeyserverCache(cacheFile);
        String fingerprint = "5DFB74250A80C001F7305EF8F37E23C14F7495EE";
        Keyserver.Probe probe = new Keyserver.Probe(Keyserver.Visibility.VISIBLE, "the server returned the key");

        cache.put(fingerprint, probe);

        KeyserverCache reloaded = new KeyserverCache(cacheFile);
        Optional<Keyserver.Probe> cached = reloaded.get(fingerprint);

        assertTrue(cached.isPresent());
        assertEquals(Keyserver.Visibility.VISIBLE, cached.get().visibility());
        assertEquals("the server returned the key", cached.get().detail());
    }

    @Test
    void expiredProbeReturnsEmpty(@TempDir Path tempDir) throws IOException {
        Path cacheFile = tempDir.resolve("keyserver-cache");
        String fingerprint = "5DFB74250A80C001F7305EF8F37E23C14F7495EE";
        long oldTimestamp = Instant.now().minus(Duration.ofHours(2)).toEpochMilli();
        String line = fingerprint + "\tNOT_PUBLISHED\t" + oldTimestamp + "\tnot found\n";
        Files.writeString(cacheFile, line, StandardCharsets.UTF_8);

        KeyserverCache cache = new KeyserverCache(cacheFile);
        Optional<Keyserver.Probe> cached = cache.get(fingerprint);

        assertTrue(cached.isEmpty());
    }

    @Test
    void visibleProbeHasLongTtl(@TempDir Path tempDir) throws IOException {
        Path cacheFile = tempDir.resolve("keyserver-cache");
        String fingerprint = "5DFB74250A80C001F7305EF8F37E23C14F7495EE";
        long oldTimestamp = Instant.now().minus(Duration.ofDays(3)).toEpochMilli();
        String line = fingerprint + "\tVISIBLE\t" + oldTimestamp + "\tthe server returned the key\n";
        Files.writeString(cacheFile, line, StandardCharsets.UTF_8);

        KeyserverCache cache = new KeyserverCache(cacheFile);
        Optional<Keyserver.Probe> cached = cache.get(fingerprint);

        assertTrue(cached.isPresent());
        assertEquals(Keyserver.Visibility.VISIBLE, cached.get().visibility());
    }

    @Test
    void corruptedLinesAreSkippedGracefully(@TempDir Path tempDir) throws IOException {
        Path cacheFile = tempDir.resolve("keyserver-cache");
        String content = "invalid-line\n"
                + "5DFB\tUNKNOWN_ENUM\t12345\tdetail\n"
                + "5DFB74250A80C001F7305EF8F37E23C14F7495EE\tVISIBLE\t" + Instant.now().toEpochMilli() + "\tok\n";
        Files.writeString(cacheFile, content, StandardCharsets.UTF_8);

        KeyserverCache cache = new KeyserverCache(cacheFile);
        Optional<Keyserver.Probe> cached = cache.get("5DFB74250A80C001F7305EF8F37E23C14F7495EE");

        assertTrue(cached.isPresent());
        assertEquals(Keyserver.Visibility.VISIBLE, cached.get().visibility());
    }
}
