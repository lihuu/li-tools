package io.github.lihuu.clicommon.cli;

import org.jetbrains.annotations.NotNull;
import java.util.Objects;

/**
 * An anticipated business or operational failure to be printed cleanly on stderr without stack traces.
 */
public class CliExecutionException extends RuntimeException {
    public CliExecutionException(@NotNull String message) {
        super(Objects.requireNonNull(message, "message must not be null"));
    }

    public CliExecutionException(@NotNull String message, @NotNull Throwable cause) {
        super(Objects.requireNonNull(message, "message must not be null"), Objects.requireNonNull(cause, "cause must not be null"));
    }
}
