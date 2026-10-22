package io.github.lihuu.skillkit.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class RepositoryPublisher {
    private final Path root;
    private final Map<String, String> environment;

    public RepositoryPublisher(@NotNull Path root, @NotNull Map<String, String> environment) {
        this.root = root;
        this.environment = environment;
    }

    public record Result(@NotNull String status, @NotNull String branch, @NotNull String revision, boolean committed) {}

    public @NotNull Result push(@Nullable String destination, @NotNull String message, boolean dryRun) throws IOException {
        if (message.isBlank()) throw new IllegalArgumentException("Commit message must not be blank");
        String remote = destination == null ? null : destination(destination);
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("Personal repository does not exist");
        if (dryRun) return new Result("planned", plan(remote).branch(), "", false);
        try (OperationLock ignored = OperationLock.acquire(root)) {
            Plan plan = plan(remote);
            if (!plan.initialized()) git(List.of("init", "--initial-branch=main"));
            if (remote != null) {
                boolean origin = git(List.of("remote")).lines().anyMatch("origin"::equals);
                git(List.of("remote", origin ? "set-url" : "add", "origin", remote));
                git(List.of("config", "--replace-all", "remote.origin.pushurl", remote));
            }
            List<String> add = new ArrayList<>(List.of("add", "--"));
            add.addAll(plan.paths());
            git(add);
            List<String> changes = new ArrayList<>(List.of("diff", "--cached", "--shortstat", "--"));
            changes.addAll(plan.paths());
            boolean committed = !git(changes).isEmpty();
            if (committed) {
                List<String> commit = new ArrayList<>(List.of("commit", "--only", "--message", message, "--"));
                commit.addAll(plan.paths());
                git(commit);
            }
            String revision = git(List.of("rev-parse", "HEAD"));
            try {
                git(List.of("push", "--porcelain", "--set-upstream", "origin", "HEAD:refs/heads/" + plan.branch()));
            } catch (IOException failure) {
                throw new IOException("Push failed; local commits are retained for retry. " + failure.getMessage(), failure);
            }
            return new Result("pushed", plan.branch(), revision, committed);
        }
    }

    private record Plan(List<String> paths, String branch, boolean initialized) {}

    private Plan plan(String remote) throws IOException {
        boolean initialized = Files.exists(root.resolve(".git"));
        List<String> paths = new ArrayList<>();
        for (String path : List.of("skills", "skillkit.json")) {
            if (Files.exists(root.resolve(path)) || initialized && !git(List.of("ls-files", "--", path)).isEmpty()) paths.add(path);
        }
        if (paths.isEmpty()) throw new IllegalArgumentException("No skills or skillkit.json to publish");
        if (!initialized && remote == null) throw new IllegalArgumentException("First push requires --repo <repository>");
        String branch = initialized ? git(List.of("symbolic-ref", "--short", "HEAD")) : "main";
        if (initialized) {
            Path top = Path.of(git(List.of("rev-parse", "--show-toplevel")));
            if (!top.toRealPath().equals(root.toRealPath())) throw new IllegalArgumentException("Personal repository must be a Git working tree root");
            if (remote == null && !git(List.of("remote")).lines().anyMatch("origin"::equals)) {
                throw new IllegalArgumentException("No origin remote; use push --repo <repository>");
            }
        }
        return new Plan(List.copyOf(paths), branch, initialized);
    }

    private String git(List<String> arguments) throws IOException {
        return GitClient.run(root, arguments, environment);
    }

    private static String destination(String value) {
        if (value.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) return "https://github.com/" + value + ".git";
        if (value.startsWith("https://") || value.startsWith("http://") || value.startsWith("ssh://")
                || value.startsWith("git@") || value.startsWith("file://") || Files.isDirectory(Path.of(value))) return value;
        throw new IllegalArgumentException("Publishing repository must be a Git URL, owner/repo or local bare repository");
    }
}
