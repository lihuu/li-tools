package io.github.lihuu.clicommon.style;

import org.junit.jupiter.api.Test;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OutputStyleTest {

    @Test
    void shouldEnableAnsiWhenInteractiveAndColorAllowed() {
        OutputStyle style = OutputStyle.of(Map.of("TERM", "xterm-256color"), true, false);
        assertTrue(style.isAnsi());
        assertTrue(style.success("done").contains("\u001b[32;1m✔ done\u001b[0m"));
        assertTrue(style.warning("caution").contains("\u001b[33;1m⚠ caution\u001b[0m"));
        assertTrue(style.error("failed").contains("\u001b[31;1m✖ failed\u001b[0m"));
        assertTrue(style.info("note").contains("\u001b[34;1mℹ note\u001b[0m"));
        assertTrue(style.tip("idea").contains("💡 idea"));
        assertTrue(style.highlight("name").contains("\u001b[36;1mname\u001b[0m"));
        assertTrue(style.muted("quiet").contains("\u001b[90mquiet\u001b[0m"));
        assertTrue(style.bold("strong").contains("\u001b[1mstrong\u001b[0m"));
    }

    @Test
    void shouldDisableAnsiWhenNoColorEnvSet() {
        OutputStyle style = OutputStyle.of(Map.of("TERM", "xterm-256color", "NO_COLOR", "1"), true, false);
        assertFalse(style.isAnsi());
        assertEquals("✔ done", style.success("done"));
        assertEquals("⚠ caution", style.warning("caution"));
        assertEquals("✖ failed", style.error("failed"));
        assertEquals("ℹ note", style.info("note"));
        assertEquals("💡 idea", style.tip("idea"));
        assertEquals("name", style.highlight("name"));
        assertEquals("quiet", style.muted("quiet"));
        assertEquals("strong", style.bold("strong"));
    }

    @Test
    void shouldDisableAnsiWhenTermDumb() {
        OutputStyle style = OutputStyle.of(Map.of("TERM", "dumb"), true, false);
        assertFalse(style.isAnsi());
    }

    @Test
    void shouldDisableAnsiWhenNotInteractive() {
        OutputStyle style = OutputStyle.of(Map.of("TERM", "xterm-256color"), false, false);
        assertFalse(style.isAnsi());
    }

    @Test
    void shouldDisableAnsiWhenNoColorFlagGiven() {
        OutputStyle style = OutputStyle.of(Map.of("TERM", "xterm-256color"), true, true);
        assertFalse(style.isAnsi());
    }

    @Test
    void shouldSupportPlainAndAnsiConstants() {
        assertTrue(OutputStyle.ansi().isAnsi());
        assertFalse(OutputStyle.plain().isAnsi());
    }
}
