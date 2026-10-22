package io.github.lihuu.skillkit.core;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class RepositoryManager {
    private final RepositoryStore store;
    private final SourceResolver sources;

    public RepositoryManager(@NotNull RepositoryStore store, @NotNull SourceResolver sources) {
        this.store = store;
        this.sources = sources;
    }

    public record Entry(@NotNull String name, @NotNull String kind, @NotNull String source) {}

    public @NotNull RepositoryStore store() { return store; }

    public void initialize() throws IOException {
        try (OperationLock ignored = OperationLock.acquire(store.root())) {
            Files.createDirectories(store.root().resolve("skills"));
            store.save(store.load());
            Path ignore = store.root().resolve(".gitignore");
            String contents = Files.exists(ignore) ? Files.readString(ignore) : "";
            if (!contents.lines().anyMatch(line -> line.equals(".skillkit.lock"))) {
                Files.writeString(ignore, contents + (contents.isEmpty() || contents.endsWith("\n") ? "" : "\n") + ".skillkit.lock\n");
            }
        }
    }

    public void create(@NotNull String name) throws IOException {
        SkillCatalog.validateName(name);
        try (OperationLock ignored = OperationLock.acquire(store.root())) {
            store.ensureAvailable(name);
            Path target = store.root().resolve("skills").resolve(name);
            if (Files.exists(target)) throw new IllegalArgumentException("Target already exists: " + target);
            Files.createDirectories(target);
            Files.writeString(target.resolve("SKILL.md"), "---\nname: " + name + "\ndescription: Describe when to use this skill.\n---\n\n# " + name + "\n\nWrite your instructions here.\n");
            store.save(store.load());
        }
    }

    public void collect(@NotNull String name, @NotNull String source, @NotNull String ref,
                        boolean track, boolean dryRun) throws IOException {
        SkillCatalog.validateName(name);
        store.ensureAvailable(name);
        if (dryRun) return;
        SourceResolver.Snapshot snapshot = sources.resolve(source, ref);
        SkillCatalog.Skill skill = SkillCatalog.select(SkillCatalog.discover(snapshot.root()), name);
        try (OperationLock ignored = OperationLock.acquire(store.root())) {
            store.ensureAvailable(name);
            Map<String, RepositoryStore.Upstream> upstreams = store.load();
            if (track) {
                String relative = snapshot.root().relativize(skill.directory()).toString().replace('\\', '/');
                String revision = snapshot.revision().equals("directory") ? SkillFiles.digest(skill.directory()) : snapshot.revision();
                upstreams.put(name, new RepositoryStore.Upstream(name, source, relative.isEmpty() ? "." : relative, ref, revision));
                store.save(upstreams);
            } else {
                Path target = store.root().resolve("skills").resolve(name);
                if (Files.exists(target)) throw new IllegalArgumentException("Target already exists: " + target);
                Files.createDirectories(target.getParent());
                Path staging = Files.createTempDirectory(target.getParent(), ".skillkit-import-");
                try {
                    SkillFiles.copy(skill.directory(), staging);
                    Files.move(staging, target);
                    try {
                        store.save(upstreams);
                    } catch (IOException failure) {
                        SkillFiles.delete(target);
                        throw failure;
                    }
                } finally {
                    SkillFiles.delete(staging);
                }
            }
        }
    }

    public void forget(@NotNull String name) throws IOException {
        SkillCatalog.validateName(name);
        try (OperationLock ignored = OperationLock.acquire(store.root())) {
            Map<String, RepositoryStore.Upstream> upstreams = store.load();
            if (upstreams.remove(name) == null) throw new IllegalArgumentException("No upstream subscription: " + name);
            store.save(upstreams);
        }
    }

    public @NotNull List<Entry> catalog() throws IOException {
        List<Entry> entries = new ArrayList<>();
        for (SkillCatalog.Skill skill : SkillCatalog.discover(store.root().resolve("skills"))) {
            entries.add(new Entry(skill.name(), "local", store.root().relativize(skill.directory()).toString()));
        }
        for (RepositoryStore.Upstream upstream : store.load().values()) {
            if (entries.stream().anyMatch(entry -> entry.name().equals(upstream.name()))) {
                throw new IllegalArgumentException("Local content conflicts with upstream subscription: " + upstream.name());
            }
            entries.add(new Entry(upstream.name(), "upstream", upstream.source()));
        }
        entries.sort(Comparator.comparing(Entry::name));
        return List.copyOf(entries);
    }

    public @NotNull SkillCatalog.Skill resolve(@NotNull String name) throws IOException {
        SkillCatalog.validateName(name);
        List<SkillCatalog.Skill> locals = SkillCatalog.discover(store.root().resolve("skills"));
        RepositoryStore.Upstream upstream = store.load().get(name);
        boolean hasLocal = locals.stream().anyMatch(skill -> skill.name().equals(name));
        if (hasLocal && upstream != null) throw new IllegalArgumentException("Local/upstream name conflict: " + name);
        if (upstream == null) return SkillCatalog.select(locals, name);
        SourceResolver.Snapshot snapshot = sources.resolve(upstream.source(), upstream.ref());
        Path path = SkillFiles.inside(snapshot.root(), upstream.path());
        if (!Files.isRegularFile(path.resolve("SKILL.md"))) throw new IOException("Upstream skill is missing: " + name);
        return new SkillCatalog.Skill(name, path);
    }

    public @NotNull List<Entry> available(@NotNull String source, @NotNull String ref) throws IOException {
        SourceResolver.Snapshot snapshot = sources.resolve(source, ref);
        List<Entry> entries = new ArrayList<>();
        for (SkillCatalog.Skill skill : SkillCatalog.discover(snapshot.root())) {
            entries.add(new Entry(skill.name(), "available", snapshot.root().relativize(skill.directory()).toString()));
        }
        return entries;
    }
}
