package io.github.lihuu.dukit.core.browse;

import io.github.lihuu.dukit.core.model.DirEntry;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;

/**
 * Ordering applied to a directory's children. Every key ends with a name tiebreak so the
 * listing is stable and reproducible.
 */
public enum SortKey {
    SIZE(Comparator.comparingLong(DirEntry::size).reversed().thenComparing(DirEntry::name)),
    NAME(Comparator.comparing(DirEntry::name)),
    COUNT(Comparator.comparingLong(DirEntry::fileCount).reversed().thenComparing(DirEntry::name));

    private final Comparator<DirEntry> comparator;

    SortKey(@NotNull Comparator<DirEntry> comparator) {
        this.comparator = comparator;
    }

    public @NotNull Comparator<DirEntry> comparator() {
        return comparator;
    }

    public @NotNull SortKey next() {
        SortKey[] keys = values();
        return keys[(ordinal() + 1) % keys.length];
    }

    public @NotNull String label() {
        return name().toLowerCase(java.util.Locale.US);
    }
}
