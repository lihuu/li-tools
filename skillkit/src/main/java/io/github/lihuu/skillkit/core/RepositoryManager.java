package io.github.lihuu.skillkit.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RepositoryManager {
    private final RepositoryStore store;
    private final SourceResolver sources;

    public RepositoryManager(@NotNull RepositoryStore store, @NotNull SourceResolver sources) {
        this.store = store;
        this.sources = sources;
    }

    public record ResolvedSkill(@NotNull String name, @NotNull Path directory, @NotNull String revision, @Nullable RepositoryStore.Upstream subscription) {}

    private record Content(Path directory, String revision, String path) {}

    public @NotNull RepositoryStore store() { return store; }

    public void recordVersions(@NotNull Map<String, String> revisions) throws IOException {
        Map<String, RepositoryStore.Upstream> upstreams = store.load();
        boolean changed = false;
        for (Map.Entry<String, String> revision : revisions.entrySet()) {
            RepositoryStore.Upstream previous = upstreams.get(revision.getKey());
            if (previous != null && !previous.revision().equals(revision.getValue())) {
                upstreams.put(previous.name(), new RepositoryStore.Upstream(previous.name(), previous.source(),
                        previous.path(), previous.ref(), revision.getValue()));
                changed = true;
            }
        }
        if (changed) store.save(upstreams);
    }

    public @NotNull ResolvedSkill resolve(@NotNull String name) throws IOException {
        SkillCatalog.validateName(name);
        List<SkillCatalog.Skill> locals = SkillCatalog.discover(store.root().resolve("skills"));
        RepositoryStore.Upstream upstream = store.load().get(name);
        boolean hasLocal = locals.stream().anyMatch(skill -> skill.name().equals(name));
        if (hasLocal && upstream != null) throw new IllegalArgumentException("Local/upstream name conflict: " + name);
        if (upstream == null) {
            SkillCatalog.Skill skill = SkillCatalog.select(locals, name);
            return new ResolvedSkill(name, skill.directory(), SkillFiles.digest(skill.directory()), null);
        }
        Content content = upstreamContent(upstream, store.root(), new HashSet<>());
        return new ResolvedSkill(name, content.directory(), content.revision(), upstream);
    }

    public @NotNull ResolvedSkill resolveFromSource(@NotNull String name, @NotNull String source, @NotNull String ref) throws IOException {
        SkillCatalog.validateName(name);
        RepositoryStore.Upstream existing = store.load().get(name);
        if (existing == null) store.ensureAvailable(name);
        else if (!sourceLocation(existing.source()).equals(source) || !existing.ref().equals(ref)) {
            throw new IllegalArgumentException("Skill already uses a different source: " + name);
        }
        SourceResolver.Snapshot snapshot = sources.resolve(source, ref);
        Content content = sourceContent(snapshot, name, new HashSet<>());
        RepositoryStore.Upstream upstream = new RepositoryStore.Upstream(name, declarationSource(source),
                content.path(), ref, content.revision());
        return new ResolvedSkill(name, content.directory(), content.revision(), upstream);
    }

    private Content sourceContent(SourceResolver.Snapshot snapshot, String name, Set<String> active) throws IOException {
        String identity = snapshot.root().toRealPath() + "\n" + name;
        if (!active.add(identity)) throw new IOException("Cyclic skill source: " + name);
        try {
            List<SkillCatalog.Skill> locals = SkillCatalog.discover(snapshot.root());
            RepositoryStore.Upstream upstream = new RepositoryStore(snapshot.root()).load().get(name);
            boolean hasLocal = locals.stream().anyMatch(skill -> skill.name().equals(name));
            if (hasLocal && upstream != null) throw new IllegalArgumentException("Local/upstream name conflict: " + name);
            if (upstream == null) {
                SkillCatalog.Skill skill = SkillCatalog.select(locals, name);
                String relative = snapshot.root().relativize(skill.directory()).toString().replace('\\', '/');
                String revision = snapshot.revision().equals("directory") ? SkillFiles.digest(skill.directory()) : snapshot.revision();
                return new Content(skill.directory(), revision, relative.isEmpty() ? "." : relative);
            }
            Content content = upstreamContent(upstream, snapshot.root(), active);
            return new Content(content.directory(), content.revision(), "skillkit.json");
        } finally {
            active.remove(identity);
        }
    }

    private Content upstreamContent(RepositoryStore.Upstream upstream, Path declarationRoot, Set<String> active) throws IOException {
        SourceResolver.Snapshot snapshot = sources.resolve(sourceLocation(upstream.source(), declarationRoot), upstream.ref());
        if (upstream.path().equals("skillkit.json")) return sourceContent(snapshot, upstream.name(), active);
        Path path = SkillFiles.inside(snapshot.root(), upstream.path());
        if (!Files.isRegularFile(path.resolve("SKILL.md"))) return sourceContent(snapshot, upstream.name(), active);
        String revision = snapshot.revision().equals("directory") ? SkillFiles.digest(path) : snapshot.revision();
        return new Content(path, revision, upstream.path());
    }

    public void registerSubscriptions(@NotNull List<RepositoryStore.Upstream> subscriptions) throws IOException {
        Map<String, RepositoryStore.Upstream> entries = store.load();
        boolean changed = false;
        for (RepositoryStore.Upstream upstream : subscriptions) {
            if (!entries.containsKey(upstream.name())) {
                store.ensureAvailable(upstream.name());
                entries.put(upstream.name(), upstream);
                changed = true;
            }
        }
        if (changed) store.save(entries);
    }

    private String sourceLocation(String source) {
        return sourceLocation(source, store.root());
    }

    private static String sourceLocation(String source, Path declarationRoot) {
        return source.startsWith("./") || source.startsWith("../") || source.equals(".")
                ? declarationRoot.resolve(source).normalize().toString() : source;
    }

    private String declarationSource(String source) {
        if (source.contains("://") || source.startsWith("git@")) return source;
        Path path = Path.of(source);
        if (!path.isAbsolute() || !Files.isDirectory(path)) return source;
        if (!path.getRoot().equals(store.root().getRoot())) return source;
        String relative = store.root().relativize(path.normalize()).toString().replace('\\', '/');
        return relative.isEmpty() ? "." : relative.startsWith("../") ? relative : "./" + relative;
    }

}
