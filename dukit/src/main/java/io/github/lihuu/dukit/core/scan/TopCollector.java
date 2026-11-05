package io.github.lihuu.dukit.core.scan;

import io.github.lihuu.dukit.core.model.TopEntry;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Keeps the {@code capacity} largest entries seen so far while a scan streams past them.
 *
 * <p>Backed by a min-heap of bounded size, so memory stays at {@code capacity} regardless of
 * how many entries the scan visits. Nothing needs to be collected up front or sorted
 * afterwards: an entry replaces the current smallest only when it is bigger.
 */
public final class TopCollector {
    private final int capacity;
    private final PriorityQueue<TopEntry> heap;

    public TopCollector(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity must not be negative: " + capacity);
        }
        this.capacity = capacity;
        this.heap = new PriorityQueue<>(Math.max(1, capacity), Comparator.comparingLong(TopEntry::size));
    }

    public void offer(@NotNull Path path, long size) {
        if (capacity == 0) {
            return;
        }
        if (heap.size() < capacity) {
            heap.add(new TopEntry(path, size));
            return;
        }
        TopEntry smallest = heap.peek();
        if (smallest != null && size > smallest.size()) {
            heap.poll();
            heap.add(new TopEntry(path, size));
        }
    }

    /**
     * Returns the collected entries sorted largest first.
     */
    public @NotNull List<TopEntry> result() {
        List<TopEntry> sorted = new ArrayList<>(heap);
        sorted.sort(Comparator.comparingLong(TopEntry::size).reversed().thenComparing(entry -> entry.path().toString()));
        return List.copyOf(sorted);
    }
}
