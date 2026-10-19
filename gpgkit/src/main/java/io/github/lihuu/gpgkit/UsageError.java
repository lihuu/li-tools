package io.github.lihuu.gpgkit;

import io.github.lihuu.clicommon.cli.CliUsageException;

/**
 * A command line this tool will not act on.
 *
 * <p>Kept apart from {@link Failure} because the contract gives them different exit codes: a
 * malformed invocation is 2, an operation that did not work is 1 (SPEC §2). Main only knows how
 * to turn a {@code Failure} into 1, so a command that parses its own arguments catches this
 * itself and returns {@link Defaults#EXIT_USAGE} — which keeps the distinction in one place
 * instead of spreading "is this a usage error" over every check.
 */
final class UsageError extends CliUsageException {
    UsageError(String message) {
        super(message);
    }
}
