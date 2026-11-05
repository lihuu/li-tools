package io.github.lihuu.dukit.core.tui;

import io.github.lihuu.dukit.core.browse.SortKey;
import io.github.lihuu.dukit.core.delete.DeletePlan;
import io.github.lihuu.dukit.core.model.DirEntry;
import io.github.lihuu.dukit.core.model.ScanResult;
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
 */
public final class TuiState {
    private final Path rootPath;
    private final Deque<DirEntry> trail = new ArrayDeque<>();
    private SortKey sortKey = SortKey.SIZE;
    private int selected;
    private int scroll;
    private @Nullable DeletePlan pendingDelete;
    private @Nullable String notice;

    public TuiState(@NotNull ScanResult result) {
        this.rootPath = result.path();
        trail.push(result.root());
    }

    public @NotNull DirEntry current() {
        DirEntry current = trail.peek();
        if (current == null) {
            throw new IllegalStateException("empty trail");
        }
        return current;
    }

    public @NotNull List<DirEntry> entries() {
        List<DirEntry> ordered = new ArrayList<>(current().children());
        ordered.sort(sortKey.comparator());
        return ordered;
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
    public @Nullable DirEntry selectedEntry() {
        List<DirEntry> entries = entries();
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
        selected = Math.max(0, Math.min(selected, Math.max(0, entries().size() - 1)));
        scroll = Math.max(0, Math.min(scroll, selected));
    }

    /**
     * Absolute path of the directory currently shown, rebuilt from the navigation trail.
     */
    public @NotNull String currentPath() {
        Path path = rootPath;
        Iterator<DirEntry> ancestors = trail.descendingIterator();
        ancestors.next();
        while (ancestors.hasNext()) {
            path = path.resolve(ancestors.next().name());
        }
        return path.toString();
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
        List<DirEntry> entries = entries();
        if (selected < 0 || selected >= entries.size()) {
            return;
        }
        trail.push(entries.get(selected));
        selected = 0;
        scroll = 0;
    }

    private void ascend() {
        if (trail.size() <= 1) {
            return;
        }
        trail.pop();
        selected = 0;
        scroll = 0;
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
