package io.github.lihuu.tokenkit.util;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;

public final class ClipboardUtil {
    private ClipboardUtil() {
    }

    public static boolean copyToClipboard(@NotNull String text) {
        Objects.requireNonNull(text, "text must not be null");
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String[] command;

        if (os.contains("mac") || os.contains("darwin")) {
            command = new String[]{"/usr/bin/pbcopy"};
        } else if (os.contains("win")) {
            command = new String[]{"clip.exe"};
        } else {
            // Linux check for wl-copy or xclip
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
