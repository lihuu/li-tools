package io.github.lihuu.skillkit.core;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import io.github.lihuu.clicommon.json.JsonWriter;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

final class InstallationStore {
    private final Path root;
    InstallationStore(Path root) { this.root = root; }

    record Installation(@NotNull String name, @NotNull String repository, @NotNull String mode,
                        @NotNull String digest, @NotNull String link, @NotNull String revision) {}

    Map<String, Installation> load() throws IOException {
        Path file = root.resolve(".skillkit-state.json");
        Map<String, Installation> entries = new LinkedHashMap<>();
        if (!Files.exists(file)) return entries;
        try (JsonParser parser = new JsonFactory().createParser(file.toFile())) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw new IOException("Invalid installation state");
            boolean version = false;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.currentName();
                if (field == null) throw new IOException("Truncated installation state");
                JsonToken token = parser.nextToken();
                if (field.equals("version")) {
                    if (token != JsonToken.VALUE_NUMBER_INT || parser.getIntValue() != 1) throw new IOException("Unsupported installation state version");
                    version = true;
                } else if (field.equals("installations")) {
                    if (token != JsonToken.START_ARRAY) throw new IOException("Invalid installations array");
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        if (parser.currentToken() != JsonToken.START_OBJECT) throw new IOException("Invalid installation record");
                        Map<String, String> fields = new LinkedHashMap<>();
                        while (parser.nextToken() != JsonToken.END_OBJECT) {
                            String key = parser.currentName();
                            if (key == null || parser.nextToken() != JsonToken.VALUE_STRING) throw new IOException("Invalid installation field");
                            if (fields.put(key, parser.getText()) != null) throw new IOException("Duplicate installation field: " + key);
                        }
                        for (String key : new String[]{"name", "repository", "mode", "digest", "link", "revision"}) {
                            if (!fields.containsKey(key)) throw new IOException("Missing installation field: " + key);
                        }
                        Installation entry = new Installation(fields.get("name"), fields.get("repository"), fields.get("mode"),
                                fields.get("digest"), fields.get("link"), fields.get("revision"));
                        SkillCatalog.validateName(entry.name());
                        if (!entry.mode().equals("copy") && !entry.mode().equals("symlink")) throw new IOException("Unknown installation mode");
                        if (entries.put(entry.name(), entry) != null) throw new IOException("Duplicate installation: " + entry.name());
                    }
                } else throw new IOException("Unknown installation state field: " + field);
            }
            if (!version || parser.nextToken() != null) throw new IOException("Invalid installation state document");
        }
        return entries;
    }

    void save(Map<String, Installation> entries) throws IOException {
        JsonWriter json = new JsonWriter().beginObject().name("version").value(1).name("installations").beginArray();
        for (Installation entry : entries.values()) {
            json.beginObject().name("name").value(entry.name()).name("repository").value(entry.repository())
                    .name("mode").value(entry.mode()).name("digest").value(entry.digest()).name("link").value(entry.link())
                    .name("revision").value(entry.revision()).endObject();
        }
        RepositoryStore.atomicWrite(root.resolve(".skillkit-state.json"), json.endArray().endObject().toString());
    }
}
