package io.github.lihuu.skillkit.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.github.lihuu.skillkit.core.InstallationStore.Installation;

public final class SkillManager {
    private final RepositoryManager repository;
    private final Path stagingRoot;

    public SkillManager(@NotNull RepositoryManager repository, @NotNull Path stagingRoot) {
        this.repository = repository;
        this.stagingRoot = stagingRoot;
    }

    public record Item(@NotNull String name, @NotNull String target, @NotNull String status, @NotNull String message) {}
    private record Prepared(String name, Path directory, Path source, String digest, String revision, RepositoryStore.Upstream subscription) {}

    public @NotNull List<Item> install(@NotNull List<String> requested, @NotNull List<Path> roots,
            boolean symlink, int jobs, boolean dryRun, boolean update, @Nullable String source, @NotNull String ref) throws IOException {
        if (jobs < 1) throw new IllegalArgumentException("--jobs must be positive");
        List<String> names = new ArrayList<>(new LinkedHashSet<>(requested));
        for (String name : names) SkillCatalog.validateName(name);
        if (update) {
            LinkedHashSet<String> installed = installedNames(roots);
            if (names.isEmpty()) names.addAll(installed);
            else if (!installed.containsAll(names)) throw new IllegalArgumentException("Update only accepts installed skills");
        }
        if (dryRun) {
            List<Item> planned = new ArrayList<>();
            for (Path root : roots) {
                LinkedHashSet<String> installed = update ? installedNames(List.of(root)) : new LinkedHashSet<>();
                for (String name : names) if (!update || installed.contains(name)) {
                    planned.add(new Item(name, root.resolve(name).toString(), "planned", update ? "update" : "install"));
                }
            }
            return planned;
        }
        if (names.isEmpty()) return List.of();
        try (OperationLock ignored = OperationLock.acquire(repository.store().root())) {
            Files.createDirectories(stagingRoot);
            Path operation = Files.createTempDirectory(stagingRoot, "operation-");
            try {
                List<ParallelTasks.Task<Prepared>> tasks = new ArrayList<>();
                for (String name : names) tasks.add(() -> prepare(name, operation, source, ref));
                List<ParallelTasks.Outcome<Prepared>> prepared = ParallelTasks.run(tasks, jobs);
                List<RepositoryStore.Upstream> subscriptions = new ArrayList<>();
                for (ParallelTasks.Outcome<Prepared> outcome : prepared) {
                    if (outcome.value() != null && outcome.value().subscription() != null) subscriptions.add(outcome.value().subscription());
                }
                repository.registerSubscriptions(subscriptions);
                List<Item> items = new ArrayList<>();
                LinkedHashSet<Path> canonicalRoots = new LinkedHashSet<>();
                for (Path root : roots) {
                    if (update && installedNames(List.of(root)).isEmpty()) continue;
                    Files.createDirectories(root);
                    canonicalRoots.add(root.toRealPath());
                }
                for (Path root : canonicalRoots.stream().sorted().toList()) {
                    try (OperationLock targetLock = OperationLock.acquire(root)) {
                        InstallationStore store = new InstallationStore(root);
                        Map<String, Installation> state = store.load();
                        for (int index = 0; index < names.size(); index++) {
                            String name = names.get(index);
                            Path target = root.resolve(name);
                            ParallelTasks.Outcome<Prepared> outcome = prepared.get(index);
                            if (outcome.value() == null) {
                                items.add(new Item(name, target.toString(), "failed", outcome.error() == null ? "Preparation failed" : outcome.error()));
                                continue;
                            }
                            Installation previous = state.get(name);
                            if (update && (previous == null || !previous.repository().equals(owner()))) continue;
                            String mode = update ? previous.mode() : symlink ? "symlink" : "copy";
                            try {
                                publish(target, outcome.value(), mode, previous, state, store);
                                items.add(new Item(name, target.toString(), "installed", mode));
                            } catch (IOException | IllegalArgumentException failure) {
                                items.add(new Item(name, target.toString(), "failed", failure.getMessage()));
                            }
                        }
                    }
                }
                Map<String, String> revisions = new LinkedHashMap<>();
                for (ParallelTasks.Outcome<Prepared> outcome : prepared) {
                    Prepared content = outcome.value();
                    if (content != null && items.stream().anyMatch(item -> item.name().equals(content.name()) && item.status().equals("installed"))) {
                        revisions.put(content.name(), content.revision());
                    }
                }
                repository.recordVersions(revisions);
                return List.copyOf(items);
            } finally {
                SkillFiles.delete(operation);
            }
        }
    }

    private Prepared prepare(String name, Path operation, String requestedSource, String ref) throws IOException {
        RepositoryManager.ResolvedSkill source = requestedSource == null ? repository.resolve(name)
                : repository.resolveFromSource(name, requestedSource, ref);
        Path staging = Files.createTempDirectory(operation, "skill-");
        try {
            SkillFiles.copy(source.directory(), staging);
            String digest = SkillFiles.digest(staging);
            if (!digest.equals(SkillFiles.digest(source.directory()))) throw new IOException("Source changed while preparing " + name);
            return new Prepared(name, staging, source.directory().toRealPath(), digest, source.revision(), source.subscription());
        } catch (IOException | RuntimeException failure) {
            SkillFiles.delete(staging);
            throw failure;
        }
    }

    private String owner() throws IOException { return repository.store().root().toRealPath().toString(); }

    private void verify(Path target, Installation previous) throws IOException {
        boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (previous == null) {
            if (exists) throw new IOException("Unmanaged target already exists: " + target);
            return;
        }
        if (!previous.repository().equals(owner())) throw new IOException("Target belongs to another repository: " + target);
        if (!exists) return;
        if (previous.mode().equals("symlink")) {
            if (!Files.isSymbolicLink(target) || !Files.readSymbolicLink(target).toString().equals(previous.link())) {
                throw new IOException("Installed link was modified: " + target);
            }
        } else if (Files.isSymbolicLink(target) || !Files.isDirectory(target)
                || !SkillFiles.digest(target).equals(previous.digest())) {
            throw new IOException("Installed content was modified: " + target);
        }
    }

    private void publish(Path target, Prepared content, String mode, Installation previous,
                         Map<String, Installation> state, InstallationStore store) throws IOException {
        verify(target, previous);
        Path staging = target.resolveSibling(".skillkit-new-" + UUID.randomUUID());
        Path backup = target.resolveSibling(".skillkit-old-" + UUID.randomUUID());
        boolean backedUp = false;
        boolean published = false;
        try {
            if (mode.equals("symlink")) Files.createSymbolicLink(staging, content.source());
            else SkillFiles.copy(content.directory(), staging);
            verify(target, previous);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                Files.move(target, backup);
                backedUp = true;
            }
            Files.move(staging, target);
            published = true;
            state.put(content.name(), new Installation(content.name(), owner(), mode, content.digest(),
                    mode.equals("symlink") ? content.source().toString() : "", content.revision()));
            store.save(state);
        } catch (IOException | RuntimeException failure) {
            if (previous == null) state.remove(content.name()); else state.put(content.name(), previous);
            try {
                if (published) SkillFiles.delete(target);
                if (backedUp) Files.move(backup, target);
            } catch (IOException recovery) { failure.addSuppressed(recovery); }
            throw failure;
        } finally {
            SkillFiles.delete(staging);
        }
        if (backedUp) SkillFiles.delete(backup);
    }

    private LinkedHashSet<String> installedNames(List<Path> roots) throws IOException {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        String owner = Files.exists(repository.store().root()) ? owner() : repository.store().root().toString();
        for (Path root : roots) for (Installation entry : new InstallationStore(root).load().values()) {
            if (entry.repository().equals(owner)) names.add(entry.name());
        }
        return names;
    }

    public @NotNull List<Item> inspect(@NotNull List<Path> roots) throws IOException {
        List<Item> items = new ArrayList<>();
        String identity = Files.exists(repository.store().root()) ? owner() : repository.store().root().toString();
        for (Path root : roots) for (Installation entry : new InstallationStore(root).load().values()) {
            if (!entry.repository().equals(identity)) continue;
            Path target = root.resolve(entry.name());
            try {
                verify(target, entry);
                String status = Files.isRegularFile(target.resolve("SKILL.md")) ? "installed" : "missing";
                items.add(new Item(entry.name(), target.toString(), status, entry.mode()));
            } catch (IOException | IllegalArgumentException failure) {
                items.add(new Item(entry.name(), target.toString(), "failed", failure.getMessage()));
            }
        }
        return items;
    }

    public @NotNull List<Item> remove(@NotNull List<String> names, @NotNull List<Path> roots, boolean dryRun) throws IOException {
        for (String name : names) SkillCatalog.validateName(name);
        List<Item> items = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            if (dryRun) {
                Map<String, Installation> state = new InstallationStore(root).load();
                for (String name : names) if (state.containsKey(name)) items.add(new Item(name, root.resolve(name).toString(), "planned", "remove"));
                continue;
            }
            try (OperationLock ignored = OperationLock.acquire(root)) {
                InstallationStore store = new InstallationStore(root);
                Map<String, Installation> state = store.load();
                for (String name : new LinkedHashSet<>(names)) {
                    Installation previous = state.get(name);
                    if (previous == null) continue;
                    Path target = root.resolve(name);
                    Path backup = root.resolve(".skillkit-remove-" + UUID.randomUUID());
                    boolean moved = false;
                    try {
                        verify(target, previous);
                        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                            Files.move(target, backup);
                            moved = true;
                        }
                        state.remove(name);
                        try {
                            store.save(state);
                        } catch (IOException failure) {
                            state.put(name, previous);
                            if (moved) Files.move(backup, target);
                            throw failure;
                        }
                        SkillFiles.delete(backup);
                        items.add(new Item(name, target.toString(), "removed", ""));
                    } catch (IOException | IllegalArgumentException failure) {
                        items.add(new Item(name, target.toString(), "failed", failure.getMessage()));
                    }
                }
            }
        }
        return items;
    }
}
