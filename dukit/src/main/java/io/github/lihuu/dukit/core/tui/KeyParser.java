package io.github.lihuu.dukit.core.tui;

import org.jetbrains.annotations.NotNull;

/**
 * Decodes the byte soup a terminal sends into {@link Key} values.
 *
 * <p>Arrow keys and friends arrive as escape sequences ({@code ESC [ A}, {@code ESC [ 5 ~},
 * {@code ESC O A}); on Windows the same sequences come from the console driver once raw mode
 * is on. Plain keys are single bytes. A lone ESC with nothing after it is ignored rather than
 * treated as a key, because it is usually the beginning of a sequence that got split.
 */
public final class KeyParser {
    private static final int ESC = 27;
    private static final int CSI = '[';
    private static final int SS3 = 'O';
    private static final int SEQUENCE_TIMEOUT_MS = 250;

    private final IntReader input;

    public KeyParser(@NotNull IntReader input) {
        this.input = input;
    }

    public @NotNull Key next() {
        int first = input.read(SEQUENCE_TIMEOUT_MS);
        if (first == IntReader.END_OF_INPUT || first == 3 || first == 'q') {
            return Key.QUIT;
        }
        if (first == IntReader.EXPIRED) {
            return Key.NONE;
        }
        if (first == ESC) {
            return escapeSequence();
        }
        return switch (first) {
            case '\r', '\n' -> Key.ENTER;
            case 127, 8 -> Key.BACK;
            case 'j', 'J' -> Key.DOWN;
            case 'k', 'K' -> Key.UP;
            case 'h', 'H' -> Key.BACK;
            case 'l', 'L' -> Key.ENTER;
            case 's', 'S' -> Key.SORT;
            case 'd', 'D' -> Key.DELETE;
            case 'y', 'Y' -> Key.CONFIRM;
            case 'n', 'N' -> Key.CANCEL;
            case 't', 'T' -> Key.TOGGLE_FILES;
            case 'g' -> Key.HOME;
            case 'G' -> Key.END;
            default -> Key.NONE;
        };
    }

    private @NotNull Key escapeSequence() {
        int second = input.poll();
        if (second == IntReader.EXPIRED || second == IntReader.END_OF_INPUT) {
            // A lone Esc is the universal "back out of this".
            return Key.CANCEL;
        }
        if (second != CSI && second != SS3) {
            return Key.NONE;
        }
        int code = input.poll();
        if (code == IntReader.EXPIRED || code == IntReader.END_OF_INPUT) {
            return Key.NONE;
        }
        if (Character.isLetter(code)) {
            return letterKey((char) code);
        }
        if (Character.isDigit(code)) {
            return numberedSequence(code);
        }
        return Key.NONE;
    }

    /**
     * Handles the {@code ESC [ <n> ~} family used by Page Up/Down and Home/End on many
     * keyboards (and by all of them when the terminal is in application cursor mode).
     */
    private @NotNull Key numberedSequence(int firstDigit) {
        StringBuilder digits = new StringBuilder().append((char) firstDigit);
        while (true) {
            int code = input.poll();
            if (code == IntReader.EXPIRED || code == IntReader.END_OF_INPUT) {
                return Key.NONE;
            }
            if (code == '~') {
                break;
            }
            if (Character.isDigit(code)) {
                digits.append((char) code);
                continue;
            }
            if (code != ';') {
                return Key.NONE;
            }
        }
        return switch (digits.toString()) {
            case "1", "7" -> Key.HOME;
            case "4", "8" -> Key.END;
            case "5" -> Key.PAGE_UP;
            case "6" -> Key.PAGE_DOWN;
            default -> Key.NONE;
        };
    }

    private static @NotNull Key letterKey(char code) {
        return switch (code) {
            case 'A' -> Key.UP;
            case 'B' -> Key.DOWN;
            case 'C' -> Key.ENTER;
            case 'D' -> Key.BACK;
            case 'H' -> Key.HOME;
            case 'F' -> Key.END;
            default -> Key.NONE;
        };
    }
}
