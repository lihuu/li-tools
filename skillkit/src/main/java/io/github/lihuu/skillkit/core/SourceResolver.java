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
    private final Path gitCache;
    private final Map<String, IOException> failures = new ConcurrentHashMap<>();
    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public SourceResolver(@NotNull Path cache, @NotNull Path gitCache) {
        this.cache = cache.toAbsolutePath().normalize();
        this.gitCache = gitCache.toAbsolutePath().normalize();
    }

    public record Snapshot(@NotNull Path root, @NotNull String revision) {}

    public @NotNull Snapshot resolve(@NotNull String source, @NotNull String ref) throws IOException {
        if (source.isBlank() || ref.isBlank() || ref.startsWith("-")) throw new IllegalArgumentException("Invalid source or ref");
        if (!source.contains(":") && Files.isDirectory(Path.of(source))) {
            if (!ref.equals("HEAD")) throw new IllegalArgumentException("--ref requires a Git URL; use a file:// URL for a local Git repository");
            return new Snapshot(Path.of(source).toRealPath(), "directory");
        }
        if (source.matches("^[A-Za-z]:[\\\\/].*") && Files.isDirectory(Path.of(source))) {
            if (!ref.equals("HEAD")) throw new IllegalArgumentException("--ref requires a Git URL; use a file:// URL for a local Git repository");
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
            IOException previousFailure = failures.get(identity);
            if (previousFailure != null) throw new IOException(previousFailure.getMessage(), previousFailure);
            Snapshot cached = snapshots.get(identity);
            if (cached != null) return cached;
            Path storage = cache.resolve(SkillFiles.key(url));
            // Git bounds GIT_DIR even with longpaths enabled; keep its objects outside nested snapshot keys.
            Path gitStorage = gitCache.resolve(SkillFiles.key(cache + "\n" + url));
            try (OperationLock ignored = OperationLock.acquire(gitStorage)) {
                Files.createDirectories(storage);
                Path git = gitStorage.resolve("repository.git");
                if (!Files.exists(git)) {
                    Path staging = gitStorage.resolve("clone-" + java.util.UUID.randomUUID());
                    try {
                        GitClient.run(gitStorage, List.of("clone", "--bare", "--no-hardlinks", "--", url, staging.getFileName().toString()));
                        Files.move(staging, git);
                    } finally {
                        SkillFiles.delete(staging);
                    }
                }
                String gitDirectory = "--git-dir=repository.git";
                GitClient.run(gitStorage, List.of(gitDirectory, "fetch", "--no-tags", "origin", ref));
                String revision = GitClient.run(gitStorage, List.of(gitDirectory, "rev-parse", "FETCH_HEAD"));
                if (!revision.matches("[0-9a-f]{40,64}")) throw new IOException("Git returned an invalid revision");
                Path snapshot = storage.resolve(revision);
                if (!Files.isDirectory(snapshot)) {
                    Path checkout = Files.createTempDirectory(gitStorage, "checkout-");
                    try {
                        GitClient.run(gitStorage, List.of(gitDirectory, "--work-tree=" + checkout.getFileName(),
                                "checkout", "--force", revision, "--", "."));
                        Files.move(checkout, snapshot);
                    } finally {
                        SkillFiles.delete(checkout);
                    }
                }
                Snapshot result = new Snapshot(snapshot, revision);
                snapshots.put(identity, result);
                return result;
            } catch (IOException failure) {
                failures.put(identity, failure);
                throw failure;
            }
        }
    }
}
