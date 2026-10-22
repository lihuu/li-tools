package io.github.lihuu.skillkit.core;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import io.github.lihuu.clicommon.json.JsonWriter;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RepositoryStore {
    private final Path root;

    public RepositoryStore(@NotNull Path root) { this.root = root.toAbsolutePath().normalize(); }

    public @NotNull Path root() { return root; }

    public record Upstream(@NotNull String name, @NotNull String source, @NotNull String path,
                           @NotNull String ref, @NotNull String revision) {}

    public @NotNull Map<String, Upstream> load() throws IOException {
        Path file = root.resolve("skillkit.json");
        Map<String, Upstream> upstreams = new LinkedHashMap<>();
        if (!Files.exists(file)) return upstreams;
        try (JsonParser parser = new JsonFactory().createParser(file.toFile())) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw new IOException("Invalid skillkit.json");
            boolean version = false;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if (field.equals("version")) {
                    if (value != JsonToken.VALUE_NUMBER_INT || parser.getIntValue() != 1) throw new IOException("Unsupported repository version");
                    version = true;
                } else if (field.equals("upstreams")) {
                    if (value != JsonToken.START_ARRAY) throw new IOException("Invalid upstreams array");
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        if (parser.currentToken() != JsonToken.START_OBJECT) throw new IOException("Invalid upstream record");
                        Map<String, String> fields = new LinkedHashMap<>();
                        while (parser.nextToken() != JsonToken.END_OBJECT) {
                            String name = parser.currentName();
                            if (parser.nextToken() != JsonToken.VALUE_STRING) throw new IOException("Invalid upstream field: " + name);
                            if (fields.put(name, parser.getText()) != null) throw new IOException("Duplicate upstream field: " + name);
                        }
                        Upstream entry = new Upstream(required(fields, "name"), required(fields, "source"),
                                required(fields, "path"), required(fields, "ref"), required(fields, "revision"));
                        SkillCatalog.validateName(entry.name());
                        if (upstreams.put(entry.name(), entry) != null) throw new IOException("Duplicate upstream: " + entry.name());
                    }
                } else throw new IOException("Unknown repository field: " + field);
            }
            if (!version || parser.nextToken() != null) throw new IOException("Invalid repository document");
        }
        return upstreams;
    }

    private static String required(Map<String, String> fields, String key) throws IOException {
        String value = fields.get(key);
        if (value == null || value.isBlank()) throw new IOException("Missing upstream field: " + key);
        return value;
    }

    public void save(@NotNull Map<String, Upstream> upstreams) throws IOException {
        JsonWriter json = new JsonWriter().beginObject().name("version").value(1).name("upstreams").beginArray();
        for (Upstream upstream : upstreams.values()) {
            json.beginObject().name("name").value(upstream.name()).name("source").value(upstream.source())
                    .name("path").value(upstream.path()).name("ref").value(upstream.ref())
                    .name("revision").value(upstream.revision()).endObject();
        }
        json.endArray().endObject();
        Files.createDirectories(root);
        atomicWrite(root.resolve("skillkit.json"), json.toString());
    }

    public void ensureAvailable(@NotNull String name) throws IOException {
        SkillCatalog.validateName(name);
        List<SkillCatalog.Skill> locals = SkillCatalog.discover(root.resolve("skills"));
        if (load().containsKey(name) || locals.stream().anyMatch(skill -> skill.name().equals(name))) {
            throw new IllegalArgumentException("Skill already exists in repository: " + name);
        }
    }

    static void atomicWrite(Path file, String text) throws IOException {
        Path temporary = Files.createTempFile(file.getParent(), ".skillkit-", ".json");
        try {
            Files.writeString(temporary, text + System.lineSeparator());
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
