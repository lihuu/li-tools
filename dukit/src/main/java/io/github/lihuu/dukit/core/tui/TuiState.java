package io.github.lihuu.dukit.core.tui;

import io.github.lihuu.dukit.core.browse.SortKey;
import io.github.lihuu.dukit.core.delete.DeletePlan;
import io.github.lihuu.dukit.core.model.DirEntry;
import io.github.lihuu.dukit.core.model.TreeEntry;
import io.github.lihuu.dukit.core.model.FileEntry;
import io.github.lihuu.dukit.core.model.ScanResult;
import io.github.lihuu.dukit.core.scan.DirectoryLister;
import io.github.lihuu.dukit.core.scan.Exclusions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * Navigation and selection state for the full-screen browser, kept free of terminal I/O so
 * every transition is unit-testable.
 *
 * <p>The state is a trail of directories plus a cursor. Entries are re-sorted on every
 * access, so changing the sort key never leaves the cursor pointing at a stale index beyond
 * the list bounds.
 *
 * <p>Pressing {@code t} appends the current directory's direct files to the listing, and a
 * directory that holds no subdirectories shows them without being asked — there is nothing
 * else to look at. Those rows are selectable but not enterable; the navigation trail only
 * ever contains directories.
 *
 * <p>The file rows come from {@link DirectoryLister}, which re-reads the one directory on
 * screen rather than spending memory on a per-file entry for every directory in the tree.
 * The list is therefore refreshed on every move — never per frame — and falls back to the
 * scan's bounded preview when the directory can no longer be read.
 */
public final class TuiState {
    /** How many of a directory's largest direct files the scan keeps as a fallback preview. */
    public static final int TOP_FILES = 10;

    private final Path rootPath;
    private final Exclusions exclusions;
    private final Deque<DirEntry> trail = new ArrayDeque<>();
    private SortKey sortKey = SortKey.SIZE;
    private boolean showFiles;
    private int selected;
    private int scroll;
    private List<FileEntry> files = List.of();
    private long filesTotal;
    private @Nullable DeletePlan pendingDelete;
    private @Nullable String notice;

    public TuiState(@NotNull ScanResult result) {
        this(result, Exclusions.none());
    }

    public TuiState(@NotNull ScanResult result, @NotNull Exclusions exclusions) {
        this.rootPath = result.path();
        this.exclusions = exclusions;
        trail.push(result.root());
        refreshFiles();
    }

    public @NotNull DirEntry current() {
        DirEntry current = trail.peek();
        if (current == null) {
            throw new IllegalStateException("empty trail");
        }
        return current;
    }

    public @NotNull List<TreeEntry> entries() {
        List<DirEntry> directories = new ArrayList<>(current().children());
        directories.sort(sortKey.comparator());
        List<TreeEntry> entries = new ArrayList<>(directories.size() + files.size());
        entries.addAll(directories);
        entries.addAll(files);
        return entries;
    }

    public boolean showFiles() {
        return showFiles;
    }

    /**
     * Whether the listing currently carries the directory's files: either the user asked for
     * them, or there are no subdirectories to show instead.
     */
    public boolean filesVisible() {
        return showFiles || current().children().isEmpty();
    }

    /** Direct files in the listing, largest first. */
    public @NotNull List<FileEntry> files() {
        return files;
    }

    /** Files the directory holds, including any the listing left out. */
    public long filesTotal() {
        return filesTotal;
    }

    public boolean filesTruncated() {
        return files.size() < filesTotal;
    }

    public @NotNull SortKey sortKey() {
        return sortKey;
    }

    public int selected() {
        return selected;
    }

    public int scroll() {
        return scroll;
    }

    public int depth() {
        return trail.size() - 1;
    }

    /**
     * The entry the cursor is on, or {@code null} when the directory is empty.
     */
    public @Nullable TreeEntry selectedEntry() {
        List<TreeEntry> entries = entries();
        if (selected < 0 || selected >= entries.size()) {
            return null;
        }
        return entries.get(selected);
    }

    public @Nullable DeletePlan pendingDelete() {
        return pendingDelete;
    }

    public void requestDelete(@NotNull DeletePlan plan) {
        this.pendingDelete = plan;
        this.notice = null;
    }

    public void cancelDelete() {
        this.pendingDelete = null;
    }

    public void clearDelete() {
        this.pendingDelete = null;
    }

    public @Nullable String notice() {
        return notice;
    }

    public void setNotice(@Nullable String notice) {
        this.notice = notice;
    }

    /**
     * Swaps in a tree that had a node removed, keeping the user where they were when the
     * directory they were standing in still exists.
     */
    public void replaceResult(@NotNull ScanResult updated) {
        List<String> names = new ArrayList<>();
        Iterator<DirEntry> ancestors = trail.descendingIterator();
        ancestors.next();
        while (ancestors.hasNext()) {
            names.add(ancestors.next().name());
        }

        Deque<DirEntry> rebuilt = new ArrayDeque<>();
        DirEntry current = updated.root();
        rebuilt.addLast(current);
        for (String name : names) {
            DirEntry next = null;
            for (DirEntry child : current.children()) {
                if (child.name().equals(name)) {
                    next = child;
                    break;
                }
            }
            if (next == null) {
                break;
            }
            rebuilt.addLast(next);
            current = next;
        }

        trail.clear();
        for (DirEntry entry : rebuilt) {
            // rebuilt runs root -> current; the trail's head must be the current directory.
            trail.addFirst(entry);
        }
        pendingDelete = null;
        refreshFiles();
        selected = Math.max(0, Math.min(selected, Math.max(0, entries().size() - 1)));
        scroll = Math.max(0, Math.min(scroll, selected));
    }

    /**
     * Absolute path of the directory currently shown, rebuilt from the navigation trail.
     */
    public @NotNull String currentPath() {
        return currentDirectory().toString();
    }

    private @NotNull Path currentDirectory() {
        Path path = rootPath;
        Iterator<DirEntry> ancestors = trail.descendingIterator();
        ancestors.next();
        while (ancestors.hasNext()) {
            path = path.resolve(ancestors.next().name());
        }
        return path;
    }

    /**
     * Re-reads the file rows for the directory in view. Called on every move, never per
     * frame: a repaint must not touch the filesystem.
     */
    private void refreshFiles() {
        DirEntry current = current();
        if (!filesVisible()) {
            files = List.of();
            filesTotal = current.ownFileCount();
            return;
        }
        DirectoryLister.Listing listing = DirectoryLister.list(currentDirectory(), rootPath,
                exclusions, DirectoryLister.LIMIT);
        if (listing.total() == 0) {
            // Gone, unreadable, or emptied since the scan: the retained preview is the only
            // evidence left, and it is better than claiming the directory holds nothing.
            files = current.topFiles();
            filesTotal = current.ownFileCount();
            return;
        }
        files = listing.files();
        filesTotal = listing.total();
    }

    /**
     * Applies one key.
     *
     * @param visibleRows how many rows the list may occupy, used to keep the cursor on screen
     * @return false when the caller should exit
     */
    public boolean handle(@NotNull Key key, int visibleRows) {
        int rows = Math.max(1, visibleRows);
        switch (key) {
            case UP -> move(selected - 1, rows);
            case DOWN -> move(selected + 1, rows);
            case HOME -> move(0, rows);
            case END -> move(entries().size() - 1, rows);
            case PAGE_UP -> move(selected - rows, rows);
            case PAGE_DOWN -> move(selected + rows, rows);
            case ENTER -> descend();
            case BACK -> ascend();
            case SORT -> {
                sortKey = sortKey.next();
                clamp(rows);
            }
            case TOGGLE_FILES -> {
                showFiles = !showFiles;
                refreshFiles();
                clamp(rows);
            }
            case QUIT -> {
                return false;
            }
            case NONE -> {
                // Nothing to do; the caller redraws or waits again.
            }
        }
        return true;
    }

    private void move(int target, int rows) {
        int size = entries().size();
        if (size == 0) {
            selected = 0;
            scroll = 0;
            return;
        }
        selected = Math.max(0, Math.min(size - 1, target));
        if (selected < scroll) {
            scroll = selected;
        } else if (selected >= scroll + rows) {
            scroll = selected - rows + 1;
        }
        maxScroll(size, rows);
    }

    private void descend() {
        List<TreeEntry> entries = entries();
        if (selected < 0 || selected >= entries.size() || !(entries.get(selected) instanceof DirEntry directory)) {
            return;
        }
        trail.push(directory);
        selected = 0;
        scroll = 0;
        refreshFiles();
    }

    private void ascend() {
        if (trail.size() <= 1) {
            return;
        }
        trail.pop();
        selected = 0;
        scroll = 0;
        refreshFiles();
        clamp(1);
    }

    private void clamp(int rows) {
        int size = entries().size();
        if (size == 0) {
            selected = 0;
            scroll = 0;
            return;
        }
        selected = Math.min(selected, size - 1);
        maxScroll(size, Math.max(1, rows));
    }

    private void maxScroll(int size, int rows) {
        int maxScroll = Math.max(0, size - rows);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        if (selected < scroll) {
            scroll = selected;
        }
        if (selected >= scroll + rows) {
            scroll = Math.max(0, selected - rows + 1);
        }
    }
}
