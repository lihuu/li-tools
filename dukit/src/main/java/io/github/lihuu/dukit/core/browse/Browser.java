package io.github.lihuu.dukit.core.browse;

import io.github.lihuu.clicommon.style.OutputStyle;
import io.github.lihuu.dukit.core.delete.DeleteException;
import io.github.lihuu.dukit.core.delete.DeletePlan;
import io.github.lihuu.dukit.core.delete.DeleteResult;
import io.github.lihuu.dukit.core.delete.DeleteService;
import io.github.lihuu.dukit.core.delete.TreeUpdater;
import io.github.lihuu.dukit.core.trash.TrashException;
import io.github.lihuu.dukit.core.model.DirEntry;
import io.github.lihuu.dukit.core.model.FileEntry;
import io.github.lihuu.dukit.core.model.ScanResult;
import io.github.lihuu.dukit.core.report.HumanBytes;
import io.github.lihuu.dukit.core.scan.DirectoryLister;
import io.github.lihuu.dukit.core.scan.Exclusions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
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
    private ScanResult result;
    private final DeleteService deleteService;
    private final BufferedReader in;
    private final PrintWriter out;
    private final OutputStyle style;
    private final Deque<DirEntry> trail = new ArrayDeque<>();
    private final Exclusions exclusions;
    private SortKey sortKey = SortKey.SIZE;
    private boolean showFiles;

    public Browser(@NotNull ScanResult result, @NotNull Reader input, @NotNull PrintWriter out,
            @NotNull OutputStyle style, @Nullable DeleteService deleteService) {
        this(result, input, out, style, deleteService, Exclusions.none());
    }

    public Browser(@NotNull ScanResult result, @NotNull Reader input, @NotNull PrintWriter out,
            @NotNull OutputStyle style, @Nullable DeleteService deleteService, @NotNull Exclusions exclusions) {
        this.result = result;
        this.deleteService = deleteService;
        this.in = input instanceof BufferedReader buffered ? buffered : new BufferedReader(input);
        this.out = out;
        this.style = style;
        this.exclusions = exclusions;
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
            } else if ("t".equals(command) || "files".equals(command)) {
                showFiles = !showFiles;
                render();
            } else if (command.startsWith("sort ")) {
                setSort(command);
            } else if (command.matches("\\d+")) {
                descend(Integer.parseInt(command));
            } else if (command.startsWith("delete ")) {
                delete(command);
            } else if (command.startsWith("cd ")) {
                descendCommand(command);
            } else {
                out.println(style.error("unknown command: " + command + " (try ?)"));
            }
        }
        return 0;
    }

    /**
     * Plans a delete, shows the same facts the full-screen confirmation page shows, and only
     * moves after the user types the whole word back.
     */
    private void delete(@NotNull String command) throws IOException {
        if (deleteService == null) {
            out.println(style.error("delete is not available in this session"));
            return;
        }
        String[] parts = command.split("\\s+");
        DirEntry current = trail.peek();
        if (parts.length != 2 || current == null) {
            out.println(style.error("usage: delete <n>"));
            return;
        }
        int index;
        try {
            index = Integer.parseInt(parts[1]) - 1;
        } catch (NumberFormatException notANumber) {
            out.println(style.error("usage: delete <n>"));
            return;
        }
        List<DirEntry> children = sorted(current.children());
        if (index < 0 || index >= children.size()) {
            out.println(style.error("no entry " + parts[1] + " (1.." + children.size() + ")"));
            return;
        }
        DirEntry target = children.get(index);
        DeletePlan plan;
        try {
            plan = deleteService.plan(result, currentPath(), target);
        } catch (DeleteException | TrashException refusal) {
            out.println(style.error(refusal.getMessage()));
            return;
        }

        out.println();
        out.println(style.bold("Move to Trash?"));
        out.println("  " + plan.path());
        out.println(style.muted(String.format(Locale.US, "  %s · %,d files · %,d directories",
                HumanBytes.format(plan.size()), plan.files(), plan.directories())));
        out.println(style.muted("  → " + plan.trash().display()));
        out.println(style.muted("  Space is reclaimed only after the Trash is emptied."));
        out.print("Type 'delete' to confirm: ");
        out.flush();
        String answer = in.readLine();
        if (!"delete".equals(answer == null ? null : answer.trim())) {
            out.println(style.muted("cancelled"));
            return;
        }

        DeleteResult outcome = deleteService.execute(result, plan);
        if (outcome.status() != DeleteResult.Status.TRASHED) {
            out.println(style.error(outcome.error() == null ? "delete failed" : outcome.error()));
            return;
        }
        try {
            result = TreeUpdater.remove(result, plan.path());
            trail.clear();
            trail.push(result.root());
            out.println(style.success("moved to Trash: " + plan.name()));
        } catch (DeleteException inconsistent) {
            out.println(style.warning("moved, but the view is stale: " + inconsistent.getMessage()));
        }
        render();
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
        out.println(style.bold(style.highlight(currentPath() + "  " + HumanBytes.format(current.diskSize()))));
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
                    i + 1, HumanBytes.format(child.diskSize()), percent(child, current), child.name(), ownFiles, marker));
        }
        if (children.isEmpty()) {
            out.println(style.muted(emptyMessage(current)));
        }
        List<FileEntry> files = visibleFiles(current);
        for (FileEntry file : files) {
            out.println(style.muted(String.format(Locale.US, "       %9s       %s",
                    HumanBytes.format(file.diskSize()), file.name())));
        }
        out.println(style.muted(String.format(Locale.US,
                "<n> enter · .. up · top · s/sort sort · t files(%s) · q quit · ? help",
                filesMode(current))));
    }

    private void printHelp() {
        out.println(style.muted("<n>            descend into entry n (no prefix needed)"));
        out.println(style.muted("cd <n>         same as <n>"));
        out.println(style.muted(".. or up       go to the parent directory"));
        out.println(style.muted("top            go back to the scan root"));
        out.println(style.muted("s              cycle sort: size -> name -> count"));
        out.println(style.muted("t or files     show / hide the current directory's files"));
        out.println(style.muted("sort <key>     order by size, name or count"));
        out.println(style.muted("[enter]        re-render the current directory"));
        out.println(style.muted("q              quit (end of input also quits)"));
    }

    /**
     * A directory with no subdirectories always lists its files: there is nothing else to
     * show, and an empty listing reads like a bug rather than an empty directory.
     */
    private boolean filesVisible(@NotNull DirEntry current) {
        return showFiles || current.children().isEmpty();
    }

    private @NotNull List<FileEntry> visibleFiles(@NotNull DirEntry current) {
        if (!filesVisible(current)) {
            return List.of();
        }
        DirectoryLister.Listing listing = DirectoryLister.list(currentDirectory(), result.path(),
                exclusions, DirectoryLister.LIMIT);
        if (listing.total() == 0) {
            return current.topFiles();
        }
        if (listing.truncated()) {
            out.println(style.muted(String.format(Locale.US, "  showing the largest %,d of %,d files",
                    listing.files().size(), listing.total())));
        }
        return listing.files();
    }

    private @NotNull String emptyMessage(@NotNull DirEntry current) {
        if (current.children().isEmpty() && current.ownFileCount() == 0) {
            return "  (empty)";
        }
        return filesVisible(current) ? "  (no files)" : "  (no subdirectories)";
    }

    private @NotNull String filesMode(@NotNull DirEntry current) {
        if (current.children().isEmpty()) {
            return "auto";
        }
        return showFiles ? "on" : "off";
    }

    private @NotNull Path currentDirectory() {
        Path path = result.path();
        Iterator<DirEntry> ancestors = trail.descendingIterator();
        ancestors.next();
        while (ancestors.hasNext()) {
            path = path.resolve(ancestors.next().name());
        }
        return path;
    }

    private @NotNull String currentPath() {
        Path path = result.path();
        Iterator<DirEntry> ancestors = trail.descendingIterator();
        ancestors.next();
        while (ancestors.hasNext()) {
            path = path.resolve(ancestors.next().name());
        }
        return path.toString();
    }

    private List<DirEntry> sorted(@NotNull List<DirEntry> children) {
        List<DirEntry> ordered = new ArrayList<>(children);
        ordered.sort(sortKey.comparator());
        return ordered;
    }

    private static double percent(@NotNull DirEntry child, @NotNull DirEntry parent) {
        return parent.diskSize() > 0 ? 100.0 * child.diskSize() / parent.diskSize() : 0.0;
    }
}
