package io.github.lihuu.gpgkit;

import io.github.lihuu.clicommon.cli.CliExecutionException;

/**
 * A failure the user is meant to read.
 *
 * <p>Thrown for "the operation did not work", never for "you typed the command wrong": a usage
 * error is a {@link Defaults#EXIT_USAGE} return value, because it is part of the documented
 * interface (SPEC §2) rather than an exception.
 *
 * <p>Main prints the message on stderr and turns it into exit code 1. Going through one place is
 * what keeps a stack trace from ever reaching the terminal: a failure here is a sentence, and a
 * trace would be noise the user cannot act on.
 */
final class Failure extends CliExecutionException {
    Failure(String message) {
        super(message);
    }
}
