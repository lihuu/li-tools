package io.github.lihuu.dukit.core.report;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Human-readable byte sizes in binary units, e.g. {@code 1.5 KiB}. One decimal below ten
 * units, none from ten up.
 */
public final class HumanBytes {
    private static final String[] UNITS = {"B", "KiB", "MiB", "GiB", "TiB", "PiB"};

    private HumanBytes() {}

    public static @NotNull String format(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("negative size: " + bytes);
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < UNITS.length - 1) {
            value /= 1024;
            unit++;
        }
        if (value >= 10) {
            return (long) value + " " + UNITS[unit];
        }
        return String.format(Locale.US, "%.1f %s", value, UNITS[unit]);
    }
}
