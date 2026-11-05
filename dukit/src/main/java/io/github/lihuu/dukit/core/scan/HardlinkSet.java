package io.github.lihuu.dukit.core.scan;

/**
 * Open-addressed set of {@code (device, inode)} pairs with primitive storage. A scan of a
 * few hundred thousand files otherwise retains one JDK file-key object plus one hash-table
 * node per file; this keeps only the links that actually can be shared.
 *
 * <p>Insertion is synchronized: only entries whose link count is greater than one reach
 * this set, so contention is limited to genuinely hardlinked content. Load factor is one
 * half, which keeps linear probing short.
 */
final class HardlinkSet {
    private long[] keys = new long[32];
    private boolean[] used = new boolean[16];
    private int size;

    /**
     * @return {@code true} when this is the first occurrence of the inode, {@code false}
     *         when another path already claimed it within this scan.
     */
    synchronized boolean add(long device, long inode) {
        int mask = used.length - 1;
        int slot = spread(device, inode) & mask;
        while (used[slot]) {
            int key = slot << 1;
            if (keys[key] == device && keys[key + 1] == inode) {
                return false;
            }
            slot = (slot + 1) & mask;
        }
        used[slot] = true;
        int key = slot << 1;
        keys[key] = device;
        keys[key + 1] = inode;
        size++;
        if (size * 2 >= used.length) {
            resize();
        }
        return true;
    }

    private void resize() {
        long[] oldKeys = keys;
        boolean[] oldUsed = used;
        int capacity = oldUsed.length << 1;
        keys = new long[capacity << 1];
        used = new boolean[capacity];
        size = 0;
        for (int oldSlot = 0; oldSlot < oldUsed.length; oldSlot++) {
            if (oldUsed[oldSlot]) {
                int oldKey = oldSlot << 1;
                insert(oldKeys[oldKey], oldKeys[oldKey + 1], capacity - 1);
            }
        }
    }

    private void insert(long device, long inode, int mask) {
        int slot = spread(device, inode) & mask;
        while (used[slot]) {
            slot = (slot + 1) & mask;
        }
        used[slot] = true;
        int key = slot << 1;
        keys[key] = device;
        keys[key + 1] = inode;
        size++;
    }

    private static int spread(long device, long inode) {
        long hash = device * 0x9E3779B97F4A7C15L + inode;
        hash ^= hash >>> 30;
        hash *= 0xBF58476D1CE4E5B9L;
        hash ^= hash >>> 27;
        hash *= 0x94D049BB133111EBL;
        hash ^= hash >>> 31;
        return (int) hash;
    }
}
