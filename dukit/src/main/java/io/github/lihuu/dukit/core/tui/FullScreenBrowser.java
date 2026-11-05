package io.github.lihuu.dukit.core.tui;

import io.github.lihuu.dukit.core.model.ScanResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.NonBlockingReader;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

/**
 * The full-screen browser: alternate screen, raw mode, one-key navigation.
 *
 * <p>Rendering is a full repaint per key press rather than a diff. A dukit frame is a few
 * dozen rows of plain text, so repainting costs nothing measurable, and it removes the class
 * of bugs where a stale cell survives because a diff forgot about it. The cursor is parked
 * with {@code ESC[H}, each row is erased with {@code ESC[K}, and the tail below the frame is
 * cleared with {@code ESC[J} so a shrunken terminal cannot leave remnants.
 *
 * <p>JLine supplies the platform work: raw mode, key reading with timeouts, terminal size,
 * and — through {@code jline-terminal-ffm} — the Windows console driver via the Foreign
 * Function &amp; Memory API, which is what lets the same code run in a native image on all
 * three platforms.
 */
public final class FullScreenBrowser {
    private static final String ENTER_ALTERNATE_SCREEN = "\033[?1049h";
    private static final String LEAVE_ALTERNATE_SCREEN = "\033[?1049l";
    private static final String HIDE_CURSOR = "\033[?25l";
    private static final String SHOW_CURSOR = "\033[?25h";
    private static final int DEFAULT_WIDTH = 80;
    private static final int DEFAULT_HEIGHT = 24;

    private FullScreenBrowser() {}

    /**
     * Runs the browser until the user quits.
     *
     * @return true when the full-screen session ran; false when no usable terminal could be
     *         opened and the caller should fall back to the line-based browser
     */
    public static boolean run(@NotNull ScanResult result, boolean color) throws IOException {
        Terminal terminal = openTerminal();
        if (terminal == null) {
            return false;
        }
        Attributes originalAttributes = null;
        PrintWriter writer = terminal.writer();
        Thread restoreHook = new Thread(() -> {
            writer.print(SHOW_CURSOR + LEAVE_ALTERNATE_SCREEN);
            writer.flush();
        });
        try {
            originalAttributes = terminal.enterRawMode();
            Runtime.getRuntime().addShutdownHook(restoreHook);
            writer.print(ENTER_ALTERNATE_SCREEN + HIDE_CURSOR);
            writer.flush();

            TuiState state = new TuiState(result);
            ScreenRenderer renderer = new ScreenRenderer(color);
            KeyParser parser = new KeyParser(inputOf(terminal));

            while (true) {
                int width = terminal.getWidth() > 0 ? terminal.getWidth() : DEFAULT_WIDTH;
                int height = terminal.getHeight() > 0 ? terminal.getHeight() : DEFAULT_HEIGHT;
                draw(writer, renderer.render(state, width, height));
                if (!state.handle(parser.next(), Math.max(1, height - 4))) {
                    break;
                }
            }
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(restoreHook);
            } catch (IllegalStateException alreadyShuttingDown) {
                // The hook is about to run anyway.
            }
            writer.print(SHOW_CURSOR + LEAVE_ALTERNATE_SCREEN);
            writer.flush();
            if (originalAttributes != null) {
                terminal.setAttributes(originalAttributes);
            }
            terminal.close();
        }
        return true;
    }

    private static @Nullable Terminal openTerminal() {
        try {
            return TerminalBuilder.builder()
                    .name("dukit")
                    .system(true)
                    .provider("ffm")
                    .dumb(false)
                    .jansi(false)
                    .jna(false)
                    .build();
        } catch (IOException | RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    private static @NotNull IntReader inputOf(@NotNull Terminal terminal) {
        NonBlockingReader reader = terminal.reader();
        return timeoutMillis -> {
            try {
                int value = reader.read(timeoutMillis);
                return value == NonBlockingReader.READ_EXPIRED ? IntReader.EXPIRED : value;
            } catch (IOException failure) {
                return IntReader.END_OF_INPUT;
            }
        };
    }

    private static void draw(@NotNull PrintWriter writer, @NotNull List<String> lines) {
        writer.print("\033[H");
        for (int index = 0; index < lines.size(); index++) {
            if (index > 0) {
                writer.print("\r\n");
            }
            writer.print(lines.get(index));
            writer.print("\033[K");
        }
        writer.print("\033[J");
        writer.flush();
    }
}
