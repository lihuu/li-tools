package io.github.lihuu.clicommon.format;

import org.jetbrains.annotations.NotNull;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Tabular output formatter with ANSI-safe column alignment.
 */
public final class TableFormatter {
    private static final Pattern ANSI_PATTERN = Pattern.compile("\u001b\\[[0-9;]*[a-zA-Z]");

    private TableFormatter() {}

    public record Column(@NotNull String header, int minWidth) {
        public Column {
            Objects.requireNonNull(header, "header must not be null");
        }
    }

    public static @NotNull String stripAnsi(@NotNull String text) {
        return ANSI_PATTERN.matcher(text).replaceAll("");
    }

    public static int visibleWidth(@NotNull String text) {
        return stripAnsi(text).length();
    }

    public static @NotNull String format(@NotNull List<Column> columns, @NotNull List<List<String>> rows) {
        Objects.requireNonNull(columns, "columns must not be null");
        Objects.requireNonNull(rows, "rows must not be null");

        int[] widths = new int[columns.size()];
        for (int c = 0; c < columns.size(); c++) {
            widths[c] = Math.max(columns.get(c).minWidth(), visibleWidth(columns.get(c).header()));
        }

        for (List<String> row : rows) {
            for (int c = 0; c < Math.min(row.size(), columns.size()); c++) {
                String cell = row.get(c) != null ? row.get(c) : "";
                widths[c] = Math.max(widths[c], visibleWidth(cell));
            }
        }

        StringBuilder sb = new StringBuilder();
        int totalWidth = 0;
        for (int c = 0; c < columns.size(); c++) {
            String header = columns.get(c).header();
            sb.append(header);
            int pad = widths[c] - visibleWidth(header);
            if (pad > 0) sb.append(" ".repeat(pad));
            if (c < columns.size() - 1) sb.append("  ");
        }
        sb.append("\n");

        for (int c = 0; c < columns.size(); c++) {
            totalWidth += widths[c];
            if (c < columns.size() - 1) totalWidth += 2;
        }
        sb.append("─".repeat(totalWidth)).append("\n");

        for (int r = 0; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            for (int c = 0; c < columns.size(); c++) {
                String cell = (c < row.size() && row.get(c) != null) ? row.get(c) : "";
                sb.append(cell);
                int pad = widths[c] - visibleWidth(cell);
                if (pad > 0) sb.append(" ".repeat(pad));
                if (c < columns.size() - 1) sb.append("  ");
            }
            if (r < rows.size() - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }
}
