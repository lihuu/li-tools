package io.github.lihuu.dukit.core.browse;

import io.github.lihuu.clicommon.style.OutputStyle;
import io.github.lihuu.dukit.core.model.DirEntry;
import io.github.lihuu.dukit.core.model.ScanResult;
import io.github.lihuu.dukit.core.report.HumanBytes;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Line-based interactive browser over a finished scan, in the spirit of ncdu but without a
 * full-screen UI: every command re-renders the current directory as a plain listing, so it
 * works in any terminal including Windows consoles. Navigation keeps a trail of entries;
 * the underlying tree is immutable.
 *
 * <p>A bare number descends into that entry (no {@code cd} prefix needed), {@code ..}
 * returns to the parent, and {@code s} cycles the sort key — the three gestures that make
 * the tool feel responsive rather than command-driven.
 */
public final class Browser {
    private final ScanResult result;
    private final BufferedReader in;
    private final PrintWriter out;
    private final OutputStyle style;
    private final Deque<DirEntry> trail = new ArrayDeque<>();
    private SortKey sortKey = SortKey.SIZE;

    private enum SortKey {
        SIZE, NAME, COUNT
    }

    public Browser(@NotNull ScanResult result, @NotNull Reader input, @NotNull PrintWriter out, @NotNull OutputStyle style) {
        this.result = result;
        this.in = input instanceof BufferedReader buffered ? buffered : new BufferedReader(input);
        this.out = out;
        this.style = style;
        this.trail.push(result.root());
    }

    public int run() throws IOException {
        render();
        String line;
        while ((line = in.readLine()) != null) {
            String command = line.trim();
            if (command.isEmpty() || "ls".equals(command)) {
                render();
            } else if ("q".equals(command) || "quit".equals(command) || "exit".equals(command)) {
                return 0;
            } else if ("up".equals(command) || "..".equals(command)) {
                up();
            } else if ("top".equals(command)) {
                while (trail.size() > 1) {
                    trail.pop();
                }
                render();
            } else if ("?".equals(command) || "help".equals(command)) {
                printHelp();
            } else if ("s".equals(command) || "sort".equals(command)) {
                cycleSort();
            } else if (command.startsWith("sort ")) {
                setSort(command);
            } else if (command.matches("\\d+")) {
                descend(Integer.parseInt(command));
            } else if (command.startsWith("cd ")) {
                descendCommand(command);
            } else {
                out.println(style.error("unknown command: " + command + " (try ?)"));
            }
        }
        return 0;
    }

    private void up() {
        if (trail.size() > 1) {
            trail.pop();
            render();
        } else {
            out.println(style.muted("already at the top"));
        }
    }

    private void cycleSort() {
        SortKey[] keys = SortKey.values();
        sortKey = keys[(sortKey.ordinal() + 1) % keys.length];
        render();
    }

    private void setSort(@NotNull String command) {
        String[] parts = command.split("\\s+");
        if (parts.length != 2) {
            out.println(style.error("usage: sort size|name|count"));
            return;
        }
        try {
            sortKey = SortKey.valueOf(parts[1].toUpperCase(Locale.US));
        } catch (IllegalArgumentException unknownKey) {
            out.println(style.error("usage: sort size|name|count"));
            return;
        }
        render();
    }

    private void descendCommand(@NotNull String command) {
        String[] parts = command.split("\\s+");
        if (parts.length != 2) {
            out.println(style.error("usage: cd <n>"));
            return;
        }
        try {
            descend(Integer.parseInt(parts[1]));
        } catch (NumberFormatException notANumber) {
            out.println(style.error("usage: cd <n>"));
        }
    }

    private void descend(int oneBased) {
        DirEntry current = trail.peek();
        if (current == null) {
            return;
        }
        List<DirEntry> children = sorted(current.children());
        int index = oneBased - 1;
        if (index < 0 || index >= children.size()) {
            out.println(style.error("no entry " + oneBased + " (1.." + children.size() + ")"));
            return;
        }
        trail.push(children.get(index));
        render();
    }

    private void render() {
        DirEntry current = trail.peek();
        if (current == null) {
            return;
        }
        out.println();
        out.println(style.bold(style.highlight(current.path().toString()) + "  " + HumanBytes.format(current.size())));
        out.println(style.muted(String.format(Locale.US, "%,d directories, %,d files · %s in %,d files directly · sort: %s",
                current.dirCount() + 1, current.fileCount(),
                HumanBytes.format(current.ownFileSize()), current.ownFileCount(),
                sortKey.name().toLowerCase(Locale.US))));
        List<DirEntry> children = sorted(current.children());
        for (int i = 0; i < children.size(); i++) {
            DirEntry child = children.get(i);
            String ownFiles = child.ownFileCount() > 0
                    ? style.muted(String.format(Locale.US, "  (%s in %,d files)",
                            HumanBytes.format(child.ownFileSize()), child.ownFileCount()))
                    : "";
            String marker = child.errorCount() > 0 ? style.warning(" !") : "";
            out.println(String.format(Locale.US, "  %3d  %9s  %5.1f%%  %s/%s%s",
                    i + 1, HumanBytes.format(child.size()), percent(child, current), child.name(), ownFiles, marker));
        }
        if (children.isEmpty()) {
            out.println(style.muted("  (no subdirectories)"));
        }
        out.println(style.muted("<n> enter · .. up · top · s/sort sort · q quit · ? help"));
    }

    private void printHelp() {
        out.println(style.muted("<n>            descend into entry n (no prefix needed)"));
        out.println(style.muted("cd <n>         same as <n>"));
        out.println(style.muted(".. or up       go to the parent directory"));
        out.println(style.muted("top            go back to the scan root"));
        out.println(style.muted("s              cycle sort: size -> name -> count"));
        out.println(style.muted("sort <key>     order by size, name or count"));
        out.println(style.muted("[enter]        re-render the current directory"));
        out.println(style.muted("q              quit (end of input also quits)"));
    }

    private List<DirEntry> sorted(@NotNull List<DirEntry> children) {
        List<DirEntry> ordered = new ArrayList<>(children);
        switch (sortKey) {
            case NAME -> ordered.sort(Comparator.comparing(DirEntry::name));
            case COUNT -> ordered.sort(Comparator.comparingLong(DirEntry::fileCount).reversed()
                    .thenComparing(DirEntry::name));
            case SIZE -> ordered.sort(Comparator.comparingLong(DirEntry::size).reversed()
                    .thenComparing(DirEntry::name));
        }
        return ordered;
    }

    private static double percent(@NotNull DirEntry child, @NotNull DirEntry parent) {
        return parent.size() > 0 ? 100.0 * child.size() / parent.size() : 0.0;
    }
}
