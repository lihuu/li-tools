package io.github.lihuu.clicommon.cli;

import org.jetbrains.annotations.NotNull;
import java.util.Objects;

/**
 * A user input, parameter, or syntax usage error resulting in exit code 2.
 */
public class CliUsageException extends RuntimeException {
    public CliUsageException(@NotNull String message) {
        super(Objects.requireNonNull(message, "message must not be null"));
    }
}
