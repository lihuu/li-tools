package io.github.lihuu.clicommon.system;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SystemUtilsTest {

    @Test
    void shouldDetectPlatformFromOsName() {
        assertEquals(Platform.OS.MACOS, Platform.detect("Mac OS X"));
        assertEquals(Platform.OS.MACOS, Platform.detect("Darwin"));
        assertEquals(Platform.OS.WINDOWS, Platform.detect("Windows 11"));
        assertEquals(Platform.OS.LINUX, Platform.detect("Linux"));
        assertEquals(Platform.OS.UNKNOWN, Platform.detect("Solaris"));
    }

    @Test
    void shouldDetectCurrentPlatform() {
        Platform.OS current = Platform.current();
        assertNotNull(current);
        if (System.getProperty("os.name").toLowerCase().contains("mac")) {
            assertTrue(Platform.isMac());
            assertFalse(Platform.isWindows());
            assertFalse(Platform.isLinux());
        }
    }

    @Test
    void shouldFormatShellExportForPosix() {
        String exported = ShellFormatter.formatExport("zsh", "API_KEY", "secret$123\"quoted`");
        assertEquals("export API_KEY=\"secret\\$123\\\"quoted\\`\"", exported);

        String bashExported = ShellFormatter.formatExport("bash", "TOKEN", "val");
        assertEquals("export TOKEN=\"val\"", bashExported);
    }

    @Test
    void shouldFormatShellExportForPowerShell() {
        String exported = ShellFormatter.formatExport("pwsh", "API_KEY", "secret`123\"quoted$");
        assertEquals("$env:API_KEY = \"secret``123`\"quoted\\$\"", exported);
    }

    @Test
    void shouldFormatShellExportForFish() {
        String exported = ShellFormatter.formatExport("fish", "API_KEY", "secret$123\"quoted");
        assertEquals("set -gx API_KEY \"secret\\$123\\\"quoted\"", exported);
    }

    @Test
    void shouldEnforceNonNullForClipboard() {
        assertThrows(NullPointerException.class, () -> ClipboardUtil.copyToClipboard(null));
    }
}
