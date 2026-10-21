package io.github.lihuu.gpgkit;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Local cache for keyserver probe results.
 *
 * <p>Keyservers are append-only stores: once a public key is {@code VISIBLE}, it is not deleted.
 * Caching results avoids remote network round trips on every {@code gpgkit status} run.
 */
final class KeyserverCache {
    /** Public keys on keyservers are never deleted; a week is a conservative freshness window. */
    private static final Duration VISIBLE_TTL = Duration.ofDays(7);

    /** A key not yet published might be published shortly after. */
    private static final Duration NOT_PUBLISHED_TTL = Duration.ofHours(1);

    /** Network unreachability is transient; retry sooner. */
    private static final Duration UNREACHABLE_TTL = Duration.ofMinutes(15);

    private final Path cacheFile;

    KeyserverCache() {
        this(Defaults.keyserverCacheFile());
    }

    KeyserverCache(@NotNull Path cacheFile) {
        this.cacheFile = cacheFile;
    }

    @NotNull
    Optional<Keyserver.Probe> get(@NotNull String fingerprint) {
        Map<String, CachedEntry> entries = readEntries();
        CachedEntry entry = entries.get(fingerprint);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.isExpired()) {
            return Optional.empty();
        }
        return Optional.of(new Keyserver.Probe(entry.visibility, entry.detail));
    }

    void put(@NotNull String fingerprint, @NotNull Keyserver.Probe probe) {
        putAll(Map.of(fingerprint, probe));
    }

    void putAll(@NotNull Map<String, Keyserver.Probe> probes) {
        if (probes.isEmpty()) {
            return;
        }
        Map<String, CachedEntry> entries = readEntries();
        long now = Instant.now().toEpochMilli();
        for (Map.Entry<String, Keyserver.Probe> item : probes.entrySet()) {
            entries.put(item.getKey(), new CachedEntry(item.getValue().visibility(), item.getValue().detail(), now));
        }
        writeEntries(entries);
    }

    private Map<String, CachedEntry> readEntries() {
        if (!Files.isRegularFile(cacheFile)) {
            return new LinkedHashMap<>();
        }
        Map<String, CachedEntry> result = new LinkedHashMap<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(cacheFile, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return result;
        }

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] fields = trimmed.split("\t", -1);
            if (fields.length < 4) {
                continue;
            }
            String fingerprint = fields[0];
            Keyserver.Visibility visibility;
            try {
                visibility = Keyserver.Visibility.valueOf(fields[1]);
            } catch (IllegalArgumentException notEnum) {
                continue;
            }
            long timestamp;
            try {
                timestamp = Long.parseLong(fields[2]);
            } catch (NumberFormatException notNumber) {
                continue;
            }
            String detail = fields[3];
            result.put(fingerprint, new CachedEntry(visibility, detail, timestamp));
        }
        return result;
    }

    private void writeEntries(Map<String, CachedEntry> entries) {
        Path parent = cacheFile.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
                restrictPermissions(parent, "rwx------");
            } catch (IOException ignored) {
                // Best-effort directory creation
            }
        }

        StringBuilder buffer = new StringBuilder();
        for (Map.Entry<String, CachedEntry> item : entries.entrySet()) {
            buffer.append(item.getKey()).append('\t')
                    .append(item.getValue().visibility.name()).append('\t')
                    .append(item.getValue().timestamp).append('\t')
                    .append(item.getValue().detail).append('\n');
        }

        Path tempFile;
        try {
            tempFile = Files.createTempFile(parent != null ? parent : Path.of("."), "ksc-", ".tmp");
            Files.writeString(tempFile, buffer.toString(), StandardCharsets.UTF_8);
            restrictPermissions(tempFile, "rw-------");
            try {
                Files.move(tempFile, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(tempFile, cacheFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // Best-effort cache write; failure to cache does not fail the calling command
        }
    }

    private static void restrictPermissions(Path path, String posix) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(posix));
        } catch (IOException | UnsupportedOperationException ignored) {
            // Non-posix filesystems silently ignore
        }
    }

    private record CachedEntry(Keyserver.Visibility visibility, String detail, long timestamp) {
        boolean isExpired() {
            Duration ttl = switch (visibility) {
                case VISIBLE -> VISIBLE_TTL;
                case NOT_PUBLISHED -> NOT_PUBLISHED_TTL;
                case UNREACHABLE -> UNREACHABLE_TTL;
            };
            return Instant.ofEpochMilli(timestamp).plus(ttl).isBefore(Instant.now());
        }
    }
}
