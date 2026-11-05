package io.github.lihuu.dukit.core.report;

import io.github.lihuu.clicommon.json.JsonWriter;
import io.github.lihuu.dukit.app.ApplicationInfo;
import io.github.lihuu.dukit.core.model.ScanError;
import io.github.lihuu.dukit.core.model.TopEntry;
import io.github.lihuu.dukit.core.model.TopResult;
import org.jetbrains.annotations.NotNull;

import java.io.PrintWriter;
import java.time.Instant;
import java.util.List;

/**
 * Writes a top-N result as JSON. Only the requested entries are emitted, so the payload is
 * bounded by {@code limit} rather than by the size of the scanned volume.
 */
public final class TopJsonReporter {
    private final PrintWriter out;
    private final ApplicationInfo info;

    public TopJsonReporter(@NotNull PrintWriter out, @NotNull ApplicationInfo info) {
        this.out = out;
        this.info = info;
    }

    public void write(@NotNull TopResult result, @NotNull String path, @NotNull String kind, long minSize) {
        JsonWriter json = new JsonWriter();
        json.beginObject();
        json.name("meta").beginObject();
        json.name("tool").value(info.name());
        json.name("version").value(info.version());
        json.name("path").value(path);
        json.name("kind").value(kind);
        json.name("size").value(result.totalSize());
        json.name("files").value(result.fileCount());
        json.name("errors").value(result.errors().size());
        json.name("generatedAt").value(Instant.now().toString());
        json.endObject();
        json.name("entries").beginArray();
        List<TopEntry> entries = ("files".equals(kind) ? result.largestFiles() : result.largestDirectories())
                .stream().filter(entry -> entry.size() >= minSize).toList();
        for (TopEntry entry : entries) {
            json.beginObject();
            json.name("path").value(entry.path().toString());
            json.name("size").value(entry.size());
            json.endObject();
        }
        json.endArray();
        json.endObject();
        out.println(json);
    }
}
