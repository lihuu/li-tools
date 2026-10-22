package io.github.lihuu.skillkit.core;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SourceResolver {
    private final Path cache;
    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public SourceResolver(@NotNull Path cache) { this.cache = cache; }

    public record Snapshot(@NotNull Path root, @NotNull String revision) {}

    public @NotNull Snapshot resolve(@NotNull String source, @NotNull String ref) throws IOException {
        if (source.isBlank() || ref.isBlank() || ref.startsWith("-")) throw new IllegalArgumentException("Invalid source or ref");
        if (!source.contains(":") && Files.isDirectory(Path.of(source))) {
            return new Snapshot(Path.of(source).toRealPath(), "directory");
        }
        if (source.matches("^[A-Za-z]:[\\\\/].*") && Files.isDirectory(Path.of(source))) {
            return new Snapshot(Path.of(source).toRealPath(), "directory");
        }
        String url = source;
        if (source.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) url = "https://github.com/" + source + ".git";
        if (!(url.startsWith("https://") || url.startsWith("http://") || url.startsWith("ssh://")
                || url.startsWith("git@") || url.startsWith("file://"))) {
            throw new IllegalArgumentException("Source is not a directory or Git URL: " + source);
        }
        String identity = url + "\n" + ref;
        synchronized (locks.computeIfAbsent(url, ignored -> new Object())) {
            Snapshot cached = snapshots.get(identity);
            if (cached != null) return cached;
            Path storage = cache.resolve(SkillFiles.key(url));
            try (OperationLock ignored = OperationLock.acquire(storage)) {
                Path git = storage.resolve("repository.git");
                if (!Files.exists(git)) {
                    Path staging = storage.resolve("clone-" + java.util.UUID.randomUUID());
                    try {
                        GitClient.run(storage, List.of("clone", "--bare", "--no-hardlinks", "--", url, staging.toString()));
                        Files.move(staging, git);
                    } finally {
                        SkillFiles.delete(staging);
                    }
                }
                GitClient.run(storage, List.of("--git-dir=" + git, "fetch", "--no-tags", "origin", ref));
                String revision = GitClient.run(storage, List.of("--git-dir=" + git, "rev-parse", "FETCH_HEAD"));
                if (!revision.matches("[0-9a-f]{40,64}")) throw new IOException("Git returned an invalid revision");
                Path snapshot = storage.resolve(revision);
                if (!Files.isDirectory(snapshot)) {
                    Path staging = Files.createTempDirectory(storage, "snapshot-");
                    try {
                        GitClient.run(storage, List.of("--git-dir=" + git, "--work-tree=" + staging,
                                "checkout", "--force", revision, "--", "."));
                        Files.move(staging, snapshot);
                    } finally {
                        SkillFiles.delete(staging);
                    }
                }
                Snapshot result = new Snapshot(snapshot, revision);
                snapshots.put(identity, result);
                return result;
            }
        }
    }
}
