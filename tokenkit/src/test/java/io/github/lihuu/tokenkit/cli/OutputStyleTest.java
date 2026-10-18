package io.github.lihuu.tokenkit.cli;

import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputStyleTest {

    @Test
    void shouldRenderPlainWhenNoColorEnvSet() {
        OutputStyle style = OutputStyle.of(Map.of("NO_COLOR", "1"), true, false);
        assertFalse(style.isAnsi());
        assertEquals("✔ done", style.success("done"));
        assertEquals("⚠ alert", style.warning("alert"));
        assertEquals("✖ failed", style.error("failed"));
        assertEquals("path", style.highlight("path"));
        assertEquals("note", style.muted("note"));
    }

    @Test
    void shouldRenderPlainWhenNoColorFlagGiven() {
        OutputStyle style = OutputStyle.of(Map.of("TERM", "xterm-256color"), true, true);
        assertFalse(style.isAnsi());
        assertEquals("✔ done", style.success("done"));
    }

    @Test
    void shouldRenderPlainWhenNonInteractive() {
        OutputStyle style = OutputStyle.of(Map.of("TERM", "xterm-256color"), false, false);
        assertFalse(style.isAnsi());
        assertEquals("✔ done", style.success("done"));
    }

    @Test
    void shouldRenderAnsiWhenInteractiveWithColorTerminal() {
        OutputStyle style = OutputStyle.of(Map.of("TERM", "xterm-256color"), true, false);
        assertTrue(style.isAnsi());
        assertTrue(style.success("done").contains("\u001b[32;1m"));
        assertTrue(style.warning("alert").contains("\u001b[33;1m"));
        assertTrue(style.error("failed").contains("\u001b[31;1m"));
        assertTrue(style.highlight("path").contains("\u001b[36;1m"));
        assertTrue(style.muted("note").contains("\u001b[90m"));
    }
}
