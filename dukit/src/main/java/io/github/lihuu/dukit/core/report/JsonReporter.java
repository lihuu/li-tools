package io.github.lihuu.dukit.core.report;

import io.github.lihuu.clicommon.json.JsonWriter;
import io.github.lihuu.dukit.app.ApplicationInfo;
import io.github.lihuu.dukit.core.model.DirEntry;
import io.github.lihuu.dukit.core.model.ScanResult;
import org.jetbrains.annotations.NotNull;

import java.io.PrintWriter;
import java.time.Instant;

/**
 * Writes a scan result as machine-readable JSON using the zero-reflection
 * {@link JsonWriter}, so the output works unchanged in Native Image.
 *
 * <p>The tree contains directories only. Each node carries its whole-subtree totals and,
 * separately, the bytes and count of the regular files directly inside it, so a consumer
 * can reason about "this directory's own files" without the tree carrying a node per file.
 * The interactive browser's bounded file preview ({@link DirEntry#topFiles()}) is
 * deliberately not serialized: it exists for the screen, not for the report contract.
 */
public final class JsonReporter {
    private final PrintWriter out;
    private final ApplicationInfo info;
    private final int depth;

    /**
     * @param depth levels of children to emit below the root; negative means every level.
     *              Sizes always describe the whole subtree regardless of the cut.
     */
    public JsonReporter(@NotNull PrintWriter out, @NotNull ApplicationInfo info, int depth) {
        this.out = out;
        this.info = info;
        this.depth = depth;
    }

    public void write(@NotNull ScanResult result) {
        DirEntry root = result.root();
        JsonWriter json = new JsonWriter(out);
        json.beginObject();
        json.name("meta").beginObject();
        json.name("tool").value(info.name());
        json.name("version").value(info.version());
        json.name("path").value(result.path().toString());
        json.name("size").value(root.size());
        json.name("files").value(root.fileCount());
        json.name("directories").value(root.dirCount());
        json.name("errors").value(result.errors().size());
        json.name("generatedAt").value(Instant.now().toString());
        json.endObject();
        json.name("tree");
        writeNode(json, root, 0);
        json.endObject();
        json.flush();
        out.println();
    }

    private void writeNode(@NotNull JsonWriter json, @NotNull DirEntry entry, int level) {
        json.beginObject();
        json.name("name").value(entry.name());
        json.name("size").value(entry.size());
        json.name("files").value(entry.fileCount());
        json.name("directories").value(entry.dirCount());
        json.name("errors").value(entry.errorCount());
        json.name("ownFileSize").value(entry.ownFileSize());
        json.name("ownFileCount").value(entry.ownFileCount());
        json.name("children").beginArray();
        if (depth < 0 || level < depth) {
            for (DirEntry child : entry.children()) {
                writeNode(json, child, level + 1);
            }
        }
        json.endArray();
        json.endObject();
    }
}
