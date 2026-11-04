package io.github.lihuu.dukit.core.report;

import io.github.lihuu.clicommon.style.OutputStyle;
import io.github.lihuu.dukit.core.model.DirEntry;
import io.github.lihuu.dukit.core.model.ScanResult;
import org.jetbrains.annotations.NotNull;

import java.io.PrintWriter;
import java.util.Locale;

/**
 * Renders a scan result as an indented tree on stdout, sorted by size descending (the
 * scanner's order). Depth cuts the display only; sizes always cover whole subtrees.
 */
public final class TreeReporter {
    private final PrintWriter out;
    private final OutputStyle style;
    private final int depth;

    public TreeReporter(@NotNull PrintWriter out, @NotNull OutputStyle style, int depth) {
        this.out = out;
        this.style = style;
        this.depth = Math.max(0, depth);
    }

    public void write(@NotNull ScanResult result) {
        DirEntry root = result.root();
        out.println(style.bold(style.highlight(root.path().toString()) + "  " + HumanBytes.format(root.size())));
        out.println(style.muted(summary(result)));
        if (depth > 0) {
            renderChildren(root, "", root.size(), 1);
        }
    }

    private String summary(@NotNull ScanResult result) {
        DirEntry root = result.root();
        StringBuilder summary = new StringBuilder();
        summary.append(String.format(Locale.US, "%,d files, %,d directories", root.fileCount(), root.dirCount()));
        if (!result.errors().isEmpty()) {
            summary.append(String.format(Locale.US, ", %,d paths could not be read", result.errors().size()));
        }
        return summary.toString();
    }

    private void renderChildren(@NotNull DirEntry parent, @NotNull String indent, long parentSize, int level) {
        for (int i = 0; i < parent.children().size(); i++) {
            DirEntry child = parent.children().get(i);
            boolean last = i == parent.children().size() - 1;
            String branch = last ? "└── " : "├── ";
            String childIndent = last ? "    " : "│   ";
            out.println(indent + branch + sizeColumn(child, parentSize) + displayName(child));
            if (child.directory() && level < depth && !child.children().isEmpty()) {
                renderChildren(child, indent + childIndent, child.size(), level + 1);
            }
        }
    }

    private String sizeColumn(@NotNull DirEntry child, long parentSize) {
        String size = String.format("%9s  ", HumanBytes.format(child.size()));
        String percent = parentSize > 0
                ? String.format(Locale.US, "%5.1f%%  ", 100.0 * child.size() / parentSize)
                : String.format("%5s  ", "0.0%");
        return size + percent;
    }

    private String displayName(@NotNull DirEntry child) {
        String name = child.directory() ? child.name() + "/" : child.name();
        return child.errorCount() > 0 ? style.warning(name) : name;
    }
}
