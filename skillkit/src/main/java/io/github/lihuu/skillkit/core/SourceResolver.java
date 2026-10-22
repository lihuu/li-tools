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
    private final Map<String, IOException> failures = new ConcurrentHashMap<>();
    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public SourceResolver(@NotNull Path cache) { this.cache = cache.toAbsolutePath().normalize(); }

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
            try (OperationLock ignored = OperationLock.acquire(storage)) {
                // Windows limits process cwd length and Git independently bounds the GIT_DIR string.
                // Keep cwd short and pass relative paths; init/fetch avoids clone exporting an absolute GIT_DIR.
                Path workingDirectory = storage;
                while (workingDirectory.toString().length() > 240 && workingDirectory.getParent() != null) {
                    workingDirectory = workingDirectory.getParent();
                }
                Path git = storage.resolve("repository.git");
                String gitDirectory = "--git-dir=" + workingDirectory.relativize(git);
                if (!Files.exists(git)) {
                    Path staging = storage.resolve("clone-" + java.util.UUID.randomUUID());
                    try {
                        String stagingDirectory = "--git-dir=" + workingDirectory.relativize(staging);
                        GitClient.run(workingDirectory, List.of("init", "--bare", "--", workingDirectory.relativize(staging).toString()));
                        GitClient.run(workingDirectory, List.of(stagingDirectory, "remote", "add", "origin", url));
                        Files.move(staging, git);
                    } finally {
                        SkillFiles.delete(staging);
                    }
                }
                GitClient.run(workingDirectory, List.of(gitDirectory, "fetch", "--no-tags", "origin", ref));
                String revision = GitClient.run(workingDirectory, List.of(gitDirectory, "rev-parse", "FETCH_HEAD"));
                if (!revision.matches("[0-9a-f]{40,64}")) throw new IOException("Git returned an invalid revision");
                Path snapshot = storage.resolve(revision);
                if (!Files.isDirectory(snapshot)) {
                    Path staging = Files.createTempDirectory(storage, "snapshot-");
                    try {
                        GitClient.run(workingDirectory, List.of(gitDirectory, "--work-tree=" + workingDirectory.relativize(staging),
                                "checkout", "--force", revision, "--", "."));
                        Files.move(staging, snapshot);
                    } finally {
                        SkillFiles.delete(staging);
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
