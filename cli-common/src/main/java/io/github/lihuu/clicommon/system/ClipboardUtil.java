package io.github.lihuu.clicommon.system;

import org.jetbrains.annotations.NotNull;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * Cross-platform system clipboard integration.
 */
public final class ClipboardUtil {
    private ClipboardUtil() {}

    public static boolean copyToClipboard(@NotNull String text) {
        String[] command;

        if (Platform.isMac()) {
            command = new String[]{"/usr/bin/pbcopy"};
        } else if (Platform.isWindows()) {
            command = new String[]{"clip.exe"};
        } else {
            command = new String[]{"sh", "-c", "command -v wl-copy >/dev/null 2>&1 && wl-copy || xclip -selection clipboard"};
        }

        try {
            Process process = new ProcessBuilder(command).start();
            try (Writer writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
                writer.write(text);
            }
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
