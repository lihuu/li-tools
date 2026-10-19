package io.github.lihuu.clicommon.cli;

import org.jetbrains.annotations.NotNull;

/**
 * An anticipated business or operational failure to be printed cleanly on stderr without stack traces.
 */
public class CliExecutionException extends RuntimeException {
    public CliExecutionException(@NotNull String message) {
        super(message);
    }

    public CliExecutionException(@NotNull String message, @NotNull Throwable cause) {
        super(message, cause);
    }
}
