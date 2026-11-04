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
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Line-based interactive browser over a finished scan, in the spirit of ncdu but without a
 * full-screen UI: every command re-renders the current directory as a plain listing, so it
 * works in any terminal including Windows consoles. Navigation keeps a trail of entries;
 * the underlying tree is immutable.
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
            } else if ("up".equals(command)) {
                up();
            } else if ("top".equals(command)) {
                while (trail.size() > 1) {
                    trail.pop();
                }
                render();
            } else if ("?".equals(command) || "help".equals(command)) {
                printHelp();
            } else if (command.startsWith("sort")) {
                sort(command);
            } else if (command.startsWith("cd")) {
                descend(command);
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

    private void sort(@NotNull String command) {
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

    private void descend(@NotNull String command) {
        String[] parts = command.split("\\s+");
        DirEntry current = trail.peek();
        if (parts.length != 2 || current == null) {
            out.println(style.error("usage: cd <n>"));
            return;
        }
        List<DirEntry> children = sorted(current.children());
        int index;
        try {
            index = Integer.parseInt(parts[1]) - 1;
        } catch (NumberFormatException notANumber) {
            out.println(style.error("usage: cd <n>"));
            return;
        }
        if (index < 0 || index >= children.size()) {
            out.println(style.error("no entry " + parts[1] + " (1.." + children.size() + ")"));
            return;
        }
        DirEntry target = children.get(index);
        if (!target.directory()) {
            out.println(style.error(target.name() + " is not a directory"));
            return;
        }
        trail.push(target);
        render();
    }

    private void render() {
        DirEntry current = trail.peek();
        if (current == null) {
            return;
        }
        out.println();
        out.println(style.bold(style.highlight(current.path().toString()) + "  " + HumanBytes.format(current.size())));
        out.println(style.muted(String.format(Locale.US, "%,d files, %,d directories · sort: %s",
                current.fileCount(), current.dirCount(), sortKey.name().toLowerCase(Locale.US))));
        List<DirEntry> children = sorted(current.children());
        for (int i = 0; i < children.size(); i++) {
            DirEntry child = children.get(i);
            String name = child.directory() ? child.name() + "/" : child.name();
            String marker = child.errorCount() > 0 ? style.warning(" !") : "";
            out.println(String.format(Locale.US, "  %3d  %9s  %5.1f%%  %s%s",
                    i + 1, HumanBytes.format(child.size()), percent(child, current), name, marker));
        }
        out.println(style.muted("[enter] refresh · cd <n> · up · top · sort size|name|count · q quit"));
    }

    private void printHelp() {
        out.println(style.muted("[enter] or ls  re-render the current directory"));
        out.println(style.muted("cd <n>         descend into entry n"));
        out.println(style.muted("up             go to the parent directory"));
        out.println(style.muted("top            go back to the scan root"));
        out.println(style.muted("sort <key>     order entries by size, name or count"));
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
