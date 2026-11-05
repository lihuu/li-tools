package io.github.lihuu.dukit.core.tui;

import org.jetbrains.annotations.NotNull;

/**
 * Minimal seam over a non-blocking terminal input stream, so the decoder can be tested
 * without a terminal. Mirrors {@code org.jline.utils.NonBlockingReader#read(long)}:
 *
 * @see KeyParser
 */
@FunctionalInterface
public interface IntReader {
    /** Returned when no byte arrived within the timeout. */
    int EXPIRED = Integer.MIN_VALUE;
    /** Returned at end of input. */
    int END_OF_INPUT = -1;

    /**
     * @param timeoutMillis how long to wait for a byte
     * @return the byte, {@link #EXPIRED}, or {@link #END_OF_INPUT}
     */
    int read(int timeoutMillis);

    /**
     * Reads a byte without waiting, used to drain the tail of an escape sequence.
     */
    default int poll() {
        return read(10);
    }

    static @NotNull IntReader of(@NotNull java.util.function.Supplier<Integer> supplier) {
        return timeoutMillis -> {
            Integer value = supplier.get();
            return value == null ? EXPIRED : value;
        };
    }
}
