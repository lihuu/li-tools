package io.github.lihuu.dukit.core.report;

import io.github.lihuu.clicommon.json.JsonWriter;
import io.github.lihuu.dukit.app.ApplicationInfo;
import io.github.lihuu.dukit.core.model.DirEntry;
import io.github.lihuu.dukit.core.model.ScanResult;
import org.jetbrains.annotations.NotNull;

import java.io.PrintWriter;
import java.time.Instant;
import java.util.Locale;

/**
 * Writes a scan result as machine-readable JSON using the zero-reflection
 * {@link JsonWriter}, so the output works unchanged in Native Image.
 */
public final class JsonReporter {
    private final PrintWriter out;
    private final ApplicationInfo info;

    public JsonReporter(@NotNull PrintWriter out, @NotNull ApplicationInfo info) {
        this.out = out;
        this.info = info;
    }

    public void write(@NotNull ScanResult result) {
        DirEntry root = result.root();
        JsonWriter json = new JsonWriter();
        json.beginObject();
        json.name("meta").beginObject();
        json.name("tool").value(info.name());
        json.name("version").value(info.version());
        json.name("path").value(root.path().toString());
        json.name("files").value(root.fileCount());
        json.name("directories").value(root.dirCount());
        json.name("errors").value(result.errors().size());
        json.name("generatedAt").value(Instant.now().toString());
        json.endObject();
        json.name("tree");
        writeNode(json, root);
        json.endObject();
        out.println(json);
    }

    private void writeNode(@NotNull JsonWriter json, @NotNull DirEntry entry) {
        json.beginObject();
        json.name("name").value(entry.name());
        json.name("size").value(entry.size());
        json.name("files").value(entry.fileCount());
        json.name("directories").value(entry.dirCount());
        json.name("errors").value(entry.errorCount());
        json.name("children").beginArray();
        for (DirEntry child : entry.children()) {
            writeNode(json, child);
        }
        json.endArray();
        json.endObject();
    }
}
