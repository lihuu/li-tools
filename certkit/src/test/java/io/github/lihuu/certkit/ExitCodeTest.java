package io.github.lihuu.certkit;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ExitCodeTest {
    @Test
    void helpReturnsZero() {
        assertEquals(0, Main.run("--help"));
    }

    @Test
    void versionReturnsZero() {
        assertEquals(0, Main.run("--version"));
    }

    @Test
    void unknownCommandReturnsTwo() {
        assertEquals(2, Main.run("nonexistent-command"));
    }
}
