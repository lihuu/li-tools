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
        // The heap's root must be the entry most worth evicting: smallest occupied size,
        // and among equals the largest path (short paths are the ones we want to keep).
        this.heap = new PriorityQueue<>(Math.max(1, capacity),
                Comparator.comparingLong(TopEntry::diskSize)
                        .thenComparing(entry -> entry.path().toString(), Comparator.reverseOrder()));
    }

    public synchronized void offer(@NotNull Path path, long size, long diskSize) {
        if (capacity == 0) {
            return;
        }
        TopEntry candidate = new TopEntry(path, size, diskSize);
        if (heap.size() < capacity) {
            heap.add(candidate);
            return;
        }
        TopEntry smallest = heap.peek();
        if (smallest != null && ranksAbove(candidate, smallest)) {
            heap.poll();
            heap.add(candidate);
        }
    }

    /**
     * Total order over candidates: occupied size first, then path. The path tiebreak is what
     * makes a parallel scan produce the same ranking as a serial one — many files share a
     * block-rounded size, and without it the winner would depend on visit order.
     */
    private static boolean ranksAbove(@NotNull TopEntry candidate, @NotNull TopEntry incumbent) {
        int byDiskSize = Long.compare(candidate.diskSize(), incumbent.diskSize());
        if (byDiskSize != 0) {
            return byDiskSize > 0;
        }
        return candidate.path().toString().compareTo(incumbent.path().toString()) < 0;
    }

    /**
     * Returns the collected entries sorted largest first.
     */
    public synchronized @NotNull List<TopEntry> result() {
        List<TopEntry> sorted = new ArrayList<>(heap);
        sorted.sort(Comparator.comparingLong(TopEntry::diskSize).reversed().thenComparing(entry -> entry.path().toString()));
        return List.copyOf(sorted);
    }
}
