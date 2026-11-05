package io.github.lihuu.dukit.core.report;

import io.github.lihuu.clicommon.style.OutputStyle;
import io.github.lihuu.dukit.core.model.TopEntry;
import io.github.lihuu.dukit.core.model.TopResult;
import org.jetbrains.annotations.NotNull;

import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;

/**
 * Renders a top-N result as a plain ranked list for humans. Paths come first because they
 * are what you act on; sizes are right-aligned so the ranking is scannable.
 */
public final class TopTextReporter {
    private final PrintWriter out;
    private final OutputStyle style;

    public TopTextReporter(@NotNull PrintWriter out, @NotNull OutputStyle style) {
        this.out = out;
        this.style = style;
    }

    public void write(@NotNull TopResult result, @NotNull String kind, long minSize) {
        List<TopEntry> entries = ("files".equals(kind) ? result.largestFiles() : result.largestDirectories())
                .stream().filter(entry -> entry.size() >= minSize).toList();
        if (entries.isEmpty()) {
            out.println(style.muted("nothing matched"));
            return;
        }
        for (int i = 0; i < entries.size(); i++) {
            TopEntry entry = entries.get(i);
            double percent = result.totalSize() > 0 ? 100.0 * entry.size() / result.totalSize() : 0.0;
            out.println(String.format(Locale.US, "%3d  %9s  %5.1f%%  %s",
                    i + 1, HumanBytes.format(entry.size()), percent, entry.path()));
        }
        out.println(style.muted(String.format(Locale.US, "%d %s · %s total · %,d files",
                entries.size(), kind, HumanBytes.format(result.totalSize()), result.fileCount())));
    }
}
