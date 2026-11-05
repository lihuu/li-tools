package io.github.lihuu.dukit.core.tui;

/**
 * A logical key press decoded from the terminal byte stream, independent of how the
 * terminal encoded it.
 */
public enum Key {
    UP,
    DOWN,
    ENTER,
    BACK,
    HOME,
    END,
    PAGE_UP,
    PAGE_DOWN,
    SORT,
    TOGGLE_FILES,
    QUIT,
    NONE
}
