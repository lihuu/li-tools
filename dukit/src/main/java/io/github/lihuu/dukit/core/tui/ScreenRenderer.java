package io.github.lihuu.dukit.core.tui;

import io.github.lihuu.dukit.core.delete.DeletePlan;
import io.github.lihuu.dukit.core.model.DirEntry;
import io.github.lihuu.dukit.core.model.FileEntry;
import io.github.lihuu.dukit.core.model.TreeEntry;
import io.github.lihuu.dukit.core.report.HumanBytes;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns {@link TuiState} into the exact lines a full-screen frame shows.
 *
 * <p>Pure: no terminal, no clock, no I/O. Given the same state and size it returns the same
 * lines, which is what makes the layout testable without a TTY.
 *
 * <p>Width is always measured on plain text and styling is applied last. ANSI escapes are
 * bytes, not columns; measuring a styled string would truncate lines that fit perfectly well
 * on screen.
 */
public final class ScreenRenderer {
    private static final String RESET = "\033[0m";
    private static final String REVERSE = "\033[7m";
    private static final String BOLD = "\033[1m";
    private static final String DIM = "\033[90m";

    private final boolean color;

    public ScreenRenderer(boolean color) {
        this.color = color;
    }

    /**
     * @param width  terminal columns
     * @param height terminal rows
     * @return one string per row, top to bottom, each fitting the width once styling is
     *         stripped
     */
    public @NotNull List<String> render(@NotNull TuiState state, int width, int height) {
        int columns = Math.max(20, width);
        int rows = Math.max(3, height);
        DeletePlan pending = state.pendingDelete();
        if (pending != null) {
            return renderConfirmation(pending, columns, rows);
        }
        List<String> lines = new ArrayList<>(rows);

        DirEntry current = state.current();
        lines.add(style(BOLD, fit(state.currentPath() + "  " + HumanBytes.format(current.diskSize()), columns)));
        String summary = String.format(Locale.US, "%s in own files · %,d directories · %,d files",
                HumanBytes.format(current.ownFileSize()), current.dirCount(), current.fileCount());
        if (state.showFiles() && !current.topFiles().isEmpty()) {
            summary += String.format(Locale.US, " · %,d files shown", current.topFiles().size());
        }
        lines.add(style(DIM, fit(summary, columns)));
        lines.add(style(DIM, "─".repeat(columns)));

        List<TreeEntry> entries = state.entries();
        int listRows = Math.max(1, rows - 4);
        if (entries.isEmpty()) {
            String empty = state.showFiles() ? "  (no entries)" : "  (no subdirectories)";
            lines.add(style(DIM, fit(empty, columns)));
            for (int i = 1; i < listRows; i++) {
                lines.add("");
            }
        } else {
            int first = Math.min(state.scroll(), Math.max(0, entries.size() - 1));
            for (int row = 0; row < listRows; row++) {
                int index = first + row;
                if (index >= entries.size()) {
                    lines.add("");
                    continue;
                }
                lines.add(entryLine(entries, index, state.selected() == index, current.diskSize(), columns));
            }
        }

        if (state.notice() != null) {
            lines.add(style(DIM, fit("  " + state.notice(), columns)));
        }
        lines.add(style(DIM, footer(state, entries.size(), columns)));
        return lines;
    }

    /**
     * The confirmation page is the dry run: it shows exactly what will move and where, and
     * nothing happens until {@code y}.
     */
    private @NotNull List<String> renderConfirmation(@NotNull DeletePlan plan, int columns, int rows) {
        List<String> lines = new ArrayList<>(rows);
        lines.add(style(BOLD, fit("Move to Trash?", columns)));
        lines.add("");
        lines.add(fit("  " + plan.path(), columns));
        lines.add(style(DIM, fit(String.format(Locale.US, "  %s · %,d files · %,d directories",
                HumanBytes.format(plan.size()), plan.files(), plan.directories()), columns)));
        lines.add(style(DIM, fit("  → " + plan.trash().display(), columns)));
        lines.add("");
        lines.add(style(DIM, fit("  Space is reclaimed only after the Trash is emptied.", columns)));
        while (lines.size() < rows - 1) {
            lines.add("");
        }
        lines.add(style(BOLD, fit("  [y] move to Trash    [n] or Esc cancel", columns)));
        return lines;
    }

    private @NotNull String entryLine(@NotNull List<TreeEntry> entries, int index,
            boolean selected, long parentSize, int columns) {
        TreeEntry entry = entries.get(index);
        double percent = parentSize > 0 ? 100.0 * entry.diskSize() / parentSize : 0.0;
        String prefix = String.format(Locale.US, "%s %3d  %9s  %5.1f%%  ",
                selected ? "▶" : " ", index + 1, HumanBytes.format(entry.diskSize()), percent);
        String label = entry instanceof DirEntry ? entry.name() + "/" : entry.name();
        String line = fit(prefix + label, columns);
        if (selected && color) {
            return REVERSE + padRight(line, columns) + RESET;
        }
        if (entry instanceof FileEntry && color) {
            return DIM + line + RESET;
        }
        return line;
    }

    private @NotNull String footer(@NotNull TuiState state, int count, int columns) {
        String position = count == 0 ? "0/0" : (state.selected() + 1) + "/" + count;
        String help = "↑↓ move  ⏎ enter  ⌫ back  s sort(" + state.sortKey().label()
                + ")  d delete  t files(" + (state.showFiles() ? "on" : "off") + ")  q quit";
        int helpWidth = Math.max(0, columns - position.length() - 1);
        return fit(padRight(help, helpWidth) + " " + position, columns);
    }

    private @NotNull String style(@NotNull String code, @NotNull String text) {
        return color ? code + text + RESET : text;
    }

    /**
     * Truncates plain text to a column width, marking the cut so a shortened name is not
     * mistaken for a real one.
     */
    private static @NotNull String truncate(@NotNull String text, int width) {
        if (text.length() <= width) {
            return text;
        }
        if (width <= 1) {
            return text.substring(0, width);
        }
        return text.substring(0, width - 1) + "…";
    }

    private static @NotNull String padRight(@NotNull String text, int width) {
        if (text.length() >= width) {
            return text;
        }
        return text + " ".repeat(width - text.length());
    }

    private static @NotNull String fit(@NotNull String text, int width) {
        return text.length() > width ? truncate(text, width) : text;
    }
}
