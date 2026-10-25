package io.github.lihuu.skillkit.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class RepositorySynchronizer {
    private final Path root;
    private final Map<String, String> environment;

    public RepositorySynchronizer(@NotNull Path root, @NotNull Map<String, String> environment) {
        this.root = root;
        this.environment = environment;
    }

    public record Result(@NotNull String status, @NotNull String revision) {}

    public @NotNull Result pull(@Nullable String source, boolean dryRun) throws IOException {
        String remote = source == null ? null : source(source);
        if (dryRun) {
            validate(remote);
            return new Result("planned", "");
        }
        validate(remote);
        try (OperationLock ignored = OperationLock.acquire(root)) {
            validate(remote);
            if (!Files.exists(root.resolve(".git"))) return cloneRepository(remote);
            String branch = git(List.of("symbolic-ref", "--short", "HEAD"));
            String trackingRemote = git(List.of("config", "--get", "branch." + branch + ".remote"));
            String trackingBranch = git(List.of("config", "--get", "branch." + branch + ".merge"));
            if (!trackingRemote.equals("origin") || !trackingBranch.startsWith("refs/heads/")) {
                throw new IllegalArgumentException("Current branch must track a branch on origin");
            }
            git(List.of("fetch", "--no-tags", "origin", trackingBranch));
            try {
                git(List.of("merge", "--ff-only", "--no-edit", "--no-autostash", "--no-overwrite-ignore", "FETCH_HEAD"));
            } catch (IOException failure) {
                throw new IOException("Pull could not fast-forward safely; local content and commits are retained. Resolve conflicts or branch divergence using Git.", failure);
            }
            return new Result("pulled", git(List.of("rev-parse", "HEAD")));
        }
    }

    private void validate(String remote) throws IOException {
        if (!Files.exists(root.resolve(".git"))) {
            if (remote == null) throw new IllegalArgumentException("First pull requires --repo <repository>");
            if (Files.exists(root)) {
                if (!Files.isDirectory(root)) throw new IllegalArgumentException("Personal repository must be a directory");
                try (Stream<Path> entries = Files.list(root)) {
                    if (entries.anyMatch(path -> !path.getFileName().toString().equals(".skillkit.lock"))) {
                        throw new IllegalArgumentException("Cannot clone over existing personal content; use an empty repository directory");
                    }
                }
            }
            return;
        }
        Path top = Path.of(git(List.of("rev-parse", "--show-toplevel")));
        if (!top.toRealPath().equals(root.toRealPath())) throw new IllegalArgumentException("Personal repository must be a Git working tree root");
        if (remote != null && !remote.equals(git(List.of("remote", "get-url", "origin")))) {
            throw new IllegalArgumentException("Personal repository already uses a different origin; change it explicitly using Git");
        }
        if (!git(List.of("status", "--porcelain", "--untracked-files=all", "--", ".", ":(exclude).skillkit.lock")).isEmpty()) {
            throw new IOException("Personal repository has local changes; commit or resolve them before pull");
        }
    }

    private Result cloneRepository(String remote) throws IOException {
        Path staging = Files.createTempDirectory(root.getParent(), ".skillkit-clone-");
        List<Path> published = new ArrayList<>();
        try {
            GitClient.run(root.getParent(), List.of("clone", "--no-hardlinks", "--", remote, staging.getFileName().toString()), environment);
            if (Files.exists(staging.resolve(".skillkit.lock"))) throw new IOException("Remote repository contains reserved .skillkit.lock");
            String revision = GitClient.run(staging, List.of("rev-parse", "HEAD"), environment);
            validate(remote);
            try (Stream<Path> children = Files.list(staging)) {
                for (Path child : children.toList()) {
                    Path target = root.resolve(child.getFileName());
                    Files.move(child, target);
                    published.add(target);
                }
            }
            return new Result("cloned", revision);
        } catch (IOException | RuntimeException failure) {
            for (Path path : published.reversed()) {
                try {
                    SkillFiles.delete(path);
                } catch (IOException recovery) {
                    failure.addSuppressed(recovery);
                }
            }
            throw failure;
        } finally {
            SkillFiles.delete(staging);
        }
    }

    private String git(List<String> arguments) throws IOException { return GitClient.run(root, arguments, environment); }

    private static String source(String value) {
        if (value.isBlank()) throw new IllegalArgumentException("Repository must not be blank");
        if (value.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) return "https://github.com/" + value + ".git";
        if (value.startsWith("https://") || value.startsWith("http://") || value.startsWith("ssh://")
                || value.startsWith("git@") || value.startsWith("file://") || Files.isDirectory(Path.of(value))) return value;
        throw new IllegalArgumentException("Personal repository source must be a Git URL, owner/repo or local Git repository");
    }
}
